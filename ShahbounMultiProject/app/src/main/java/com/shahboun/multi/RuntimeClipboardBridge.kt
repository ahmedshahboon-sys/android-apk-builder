package com.shahboun.multi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/** Clone-local clipboard facade implemented at ClipboardManager's Binder boundary. */
object RuntimeClipboardBridge {
    @Volatile private var installed = false
    private val clips = ConcurrentHashMap<String, ClipData>()

    fun install(context: Context): Result<Unit> = runCatching {
        if (installed) return@runCatching
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: error("ClipboardManager غير متاح")
        val handle = RuntimeCompatibility.findService(
            manager,
            interfaceHints = listOf("IClipboard", "ClipboardService"),
            candidateNames = listOf("mService", "sService", "mClipboardService")
        ) ?: error("ClipboardManager binder service غير متاح")
        val field = handle.field
        val delegate = handle.delegate
        if (Proxy.isProxyClass(delegate.javaClass) && Proxy.getInvocationHandler(delegate) is Handler) {
            installed = true
            return@runCatching
        }
        val interfaces = RuntimeCompatibility.collectInterfaces(delegate.javaClass)
        require(interfaces.isNotEmpty()) { "واجهة IClipboard غير متاحة" }
        val proxy = Proxy.newProxyInstance(interfaces.first().classLoader, interfaces, Handler(delegate))
        require(RuntimeCompatibility.write(field, manager, proxy)) { "تعذر تثبيت Clipboard proxy" }
        installed = true
        RuntimeDiagnostics.log("CLIP", "clone-local clipboard bridge installed field=${field.name} owner=${field.declaringClass.name}")
    }

    fun clearClone(packageName: String, slot: Int) { clips.remove(key(packageName, slot)) }

    private class Handler(private val delegate: Any) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return invokeDelegate(method, args)
            val session = RuntimeExecutionScope.current() ?: return invokeDelegate(method, args)
            val identity = key(session.runtimePackage.packageName, session.runtimePackage.slot)
            return when (method.name) {
                "setPrimaryClip" -> {
                    val clip = args?.firstOrNull { it is ClipData } as? ClipData
                    if (clip != null) clips[identity] = clip
                    RuntimeDiagnostics.log("CLIP", "set ${session.runtimePackage.packageName}/${session.runtimePackage.slot} items=${clip?.itemCount ?: 0}")
                    defaultFor(method.returnType)
                }
                "clearPrimaryClip" -> { clips.remove(identity); defaultFor(method.returnType) }
                "getPrimaryClip" -> clips[identity]
                "getPrimaryClipDescription" -> clips[identity]?.description
                "hasPrimaryClip" -> clips.containsKey(identity)
                "hasClipboardText" -> clips[identity]?.let { clip -> (0 until clip.itemCount).any { clip.getItemAt(it).text?.isNotEmpty() == true } } == true
                else -> invokeDelegate(method, rewriteHostPackage(session, args))
            }
        }

        private fun rewriteHostPackage(session: RuntimeSession, args: Array<out Any?>?): Array<out Any?>? {
            val source = args ?: return null
            val host = MultiApplication.current?.packageName ?: return source
            val guest = session.runtimePackage.packageName
            return Array<Any?>(source.size) { i -> if (source[i] is String && source[i] == guest) host else source[i] }
        }

        private fun invokeDelegate(method: Method, args: Array<out Any?>?): Any? = try {
            method.invoke(delegate, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) { throw (e.targetException ?: e) }
    }

    private fun key(packageName: String, slot: Int) = "$packageName#$slot"
    private fun defaultFor(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        else -> null
    }
}

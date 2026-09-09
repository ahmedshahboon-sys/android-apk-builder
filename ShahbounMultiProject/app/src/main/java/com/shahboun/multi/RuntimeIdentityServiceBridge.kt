package com.shahboun.multi

import android.accounts.AccountManager
import android.app.AppOpsManager
import android.content.Context
import android.os.UserManager
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Binder-facing identity rewrite for Android system services. */
object RuntimeIdentityServiceBridge {
    @Volatile private var accountPublicApiOnly = false

    fun install(context: Context): Result<Unit> = runCatching {
        val app = context.applicationContext
        installManager(app, app.getSystemService(AppOpsManager::class.java), "APPOPS", listOf("IAppOpsService"), listOf("mService"))
        installManager(app, AccountManager.get(app), "ACCOUNT", listOf("IAccountManager", "AccountManagerService"), listOf("mService", "sService"))
        installManager(app, app.getSystemService(UserManager::class.java), "USER", listOf("IUserManager", "UserManagerService"), listOf("mService"))
        RuntimeDiagnostics.log("IDENTITY", "AppOps/Account/User identity bridges ready")
    }

    fun accountManagerFor(context: Context, session: RuntimeSession): AccountManager {
        if (accountPublicApiOnly) {
            RuntimeDiagnostics.log("IDENTITY", "ACCOUNT public-api service ${session.runtimePackage.packageName}/${session.runtimePackage.slot}")
        }
        return AccountManager.get(context.applicationContext)
    }

    fun accountUsesPublicApi(): Boolean = accountPublicApiOnly

    private fun installManager(
        context: Context,
        manager: Any?,
        label: String,
        hints: List<String>,
        candidateNames: List<String>
    ) {
        if (manager == null) {
            if (label == "ACCOUNT") {
                accountPublicApiOnly = true
                RuntimeDiagnostics.log("IDENTITY", "ACCOUNT public-api passthrough active reason=manager unavailable")
            } else RuntimeDiagnostics.log("IDENTITY", "$label manager unavailable")
            return
        }
        val handle = RuntimeCompatibility.findService(manager, hints, candidateNames)
        if (handle == null) {
            if (label == "ACCOUNT") {
                accountPublicApiOnly = true
                RuntimeDiagnostics.log("IDENTITY", "ACCOUNT public-api passthrough active reason=hidden binder unavailable")
            } else RuntimeDiagnostics.log("IDENTITY", "$label binder service unavailable")
            return
        }
        val field = handle.field
        val delegate = handle.delegate
        if (Proxy.isProxyClass(delegate.javaClass) && Proxy.getInvocationHandler(delegate) is Handler) {
            if (label == "ACCOUNT") accountPublicApiOnly = false
            return
        }
        val interfaces = RuntimeCompatibility.collectInterfaces(delegate.javaClass)
        if (interfaces.isEmpty()) {
            if (label == "ACCOUNT") {
                accountPublicApiOnly = true
                RuntimeDiagnostics.log("IDENTITY", "ACCOUNT public-api passthrough active reason=hidden interface unavailable")
            } else RuntimeDiagnostics.log("IDENTITY", "$label binder interfaces unavailable")
            return
        }
        val proxy = Proxy.newProxyInstance(interfaces.first().classLoader, interfaces, Handler(context.applicationContext, delegate, label))
        if (!RuntimeCompatibility.write(field, manager, proxy)) {
            if (label == "ACCOUNT") {
                accountPublicApiOnly = true
                RuntimeDiagnostics.log("IDENTITY", "ACCOUNT public-api passthrough active reason=proxy write blocked")
            } else RuntimeDiagnostics.log("IDENTITY", "$label proxy write failed field=${field.name}")
            return
        }
        if (label == "ACCOUNT") accountPublicApiOnly = false
        RuntimeDiagnostics.log("IDENTITY", "$label identity proxy installed field=${field.name} owner=${field.declaringClass.name}")
    }

    private class Handler(private val context: Context, private val delegate: Any, private val label: String) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return invokeDelegate(method, args, null)
            val session = RuntimeExecutionScope.current()
            return invokeDelegate(method, args, session)
        }

        private fun invokeDelegate(method: Method, args: Array<out Any?>?, session: RuntimeSession?): Any? = try {
            val safe = RuntimeBinderIdentitySanitizer.sanitize(context, session, method, args)
            if (session != null && safe != null && args != null && safe.indices.any { safe[it] != args[it] }) {
                RuntimeDiagnostics.log("IDENTITY", "$label ${method.name} ${session.runtimePackage.packageName}/${session.runtimePackage.slot} -> host UID")
            }
            val result = method.invoke(delegate, *(safe ?: emptyArray()))
            RuntimeBinderIdentitySanitizer.restoreResult(session, result)
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }
}

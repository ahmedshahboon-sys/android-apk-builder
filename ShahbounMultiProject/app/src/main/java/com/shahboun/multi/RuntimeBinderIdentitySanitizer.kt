package com.shahboun.multi

import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method

/** Central guest<->physical identity translation at Binder boundaries. */
internal object RuntimeBinderIdentitySanitizer {
    private val physicalPackage: String get() = BuildConfig.APPLICATION_ID

    fun sanitize(context: Context, session: RuntimeSession?, args: Array<out Any?>?): Array<Any?>? =
        sanitize(context, session, null, args)

    fun sanitize(context: Context, session: RuntimeSession?, method: Method?, args: Array<out Any?>?): Array<Any?>? {
        if (args == null) return null
        if (session == null) return Array(args.size) { args[it] }
        val guestPackage = session.runtimePackage.packageName
        var changed = false
        val out = Array<Any?>(args.size) { index ->
            val expected = method?.parameterTypes?.getOrNull(index)
            val original = args[index]
            val rewritten = rewriteOutboundValue(context, original, guestPackage, expected)
            if (rewritten !== original || rewritten != original) changed = true
            rewritten
        }
        if (changed) RuntimeDiagnostics.log("IDENTITY7", "Binder outbound identity sanitized ${guestPackage}/${session.runtimePackage.slot} -> $physicalPackage method=${method?.name ?: "unknown"}")
        return out
    }

    private fun rewriteOutboundValue(context: Context, value: Any?, guestPackage: String, expectedType: Class<*>?): Any? {
        if (value == null) return null
        return when {
            value is String && value == guestPackage -> physicalPackage
            value is ComponentName && value.packageName == guestPackage -> ComponentName(physicalPackage, value.className)
            value is AttributionSource && value.packageName == guestPackage -> physicalAttribution(context, value)
            value.javaClass.isArray -> rewriteArray(value, guestPackage, expectedType) { item, itemExpected -> rewriteOutboundValue(context, item, guestPackage, itemExpected) }
            value is List<*> -> {
                var changed = false
                val mapped = ArrayList<Any?>(value.size)
                value.forEach { item ->
                    val out = rewriteOutboundValue(context, item, guestPackage, null)
                    if (out !== item || out != item) changed = true
                    mapped += out
                }
                if (changed) mapped else value
            }
            value is Set<*> -> {
                var changed = false
                val mapped = LinkedHashSet<Any?>(value.size)
                value.forEach { item ->
                    val out = rewriteOutboundValue(context, item, guestPackage, null)
                    if (out !== item || out != item) changed = true
                    mapped += out
                }
                if (changed) mapped else value
            }
            else -> value
        }
    }

    private fun rewriteArray(source: Any, guestPackage: String, expectedType: Class<*>?, rewrite: (Any?, Class<*>?) -> Any?): Any {
        val sourceType = source.javaClass
        val component = when {
            expectedType?.isArray == true -> expectedType.componentType
            sourceType.isArray -> sourceType.componentType
            else -> Any::class.java
        }
        val size = ReflectArray.getLength(source)
        val out = ReflectArray.newInstance(component, size)
        var changed = false
        for (index in 0 until size) {
            val before = ReflectArray.get(source, index)
            val after = rewrite(before, component)
            if (after !== before || after != before) changed = true
            ReflectArray.set(out, index, after)
        }
        if (!changed && expectedType == null && out.javaClass == sourceType) return source
        if (out.javaClass != sourceType && expectedType == null) RuntimeDiagnostics.log("IDENTITY7", "array type normalized ${sourceType.name} -> ${out.javaClass.name} guest=$guestPackage")
        return out
    }

    fun restoreResult(session: RuntimeSession?, value: Any?): Any? {
        if (session == null || value == null) return value
        val guest = session.runtimePackage.packageName
        return when {
            value is String -> if (value == physicalPackage) guest else value
            value is ComponentName -> if (value.packageName == physicalPackage) ComponentName(guest, value.className) else value
            value is ApplicationInfo -> if (value.packageName == physicalPackage) ApplicationInfo(value).apply { packageName = guest } else value
            value is PackageInfo -> if (value.packageName == physicalPackage) PackageInfo(value).apply {
                packageName = guest
                applicationInfo = applicationInfo?.let { info -> ApplicationInfo(info).apply { packageName = guest } }
            } else value
            value.javaClass.isArray -> restoreArray(session, value)
            value is List<*> -> value.map { restoreResult(session, it) }
            value is Set<*> -> value.mapTo(LinkedHashSet()) { restoreResult(session, it) }
            else -> value
        }
    }

    private fun restoreArray(session: RuntimeSession, source: Any): Any {
        val component = source.javaClass.componentType
        val size = ReflectArray.getLength(source)
        val out = ReflectArray.newInstance(component, size)
        var changed = false
        for (index in 0 until size) {
            val before = ReflectArray.get(source, index)
            val after = restoreResult(session, before)
            if (after !== before || after != before) changed = true
            ReflectArray.set(out, index, after)
        }
        return if (changed) out else source
    }

    internal fun rewriteStringArrayForTest(source: Array<String>, guest: String, host: String): Any {
        val out = ReflectArray.newInstance(String::class.java, source.size)
        source.indices.forEach { index -> ReflectArray.set(out, index, if (source[index] == guest) host else source[index]) }
        return out
    }

    private fun physicalAttribution(context: Context, original: AttributionSource): AttributionSource {
        val host = context.applicationContext.attributionSource
        if (host.packageName == physicalPackage) return host
        return runCatching {
            AttributionSource.Builder(android.os.Process.myUid())
                .setPackageName(physicalPackage)
                .setAttributionTag(original.attributionTag)
                .build()
        }.getOrElse { host }
    }
}

package com.shahboun.multi

import android.os.Build
import android.os.IBinder
import java.lang.reflect.Field

/** Central Android/OEM compatibility layer for Shahboun Clone Engine 2.0. */
object RuntimeCompatibility {
    data class DeviceProfile(
        val sdk: Int,
        val release: String,
        val manufacturer: String,
        val brand: String,
        val model: String,
        val samsung: Boolean,
        val xiaomi: Boolean,
        val pixel: Boolean
    ) {
        val modernBinderLayout: Boolean get() = sdk >= 34
        val resourcesLoaderPreferred: Boolean get() = sdk >= 30
        val strictForegroundServices: Boolean get() = sdk >= 34
    }

    val profile: DeviceProfile by lazy {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        DeviceProfile(
            sdk = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE.orEmpty(),
            manufacturer = manufacturer,
            brand = brand,
            model = Build.MODEL.orEmpty(),
            samsung = manufacturer.equals("samsung", true) || brand.equals("samsung", true),
            xiaomi = manufacturer.equals("xiaomi", true) || brand.equals("xiaomi", true) || brand.equals("redmi", true),
            pixel = brand.equals("google", true)
        )
    }

    data class ServiceHandle(val field: Field, val delegate: Any)

    fun logProfile() {
        val p = profile
        RuntimeDiagnostics.log(
            "COMPAT",
            "profile sdk=${p.sdk} android=${p.release} manufacturer=${p.manufacturer} brand=${p.brand} model=${p.model} " +
                "resourcesLoader=${p.resourcesLoaderPreferred} modernBinder=${p.modernBinderLayout}"
        )
    }

    /** Finds a cached framework service, with a ServiceManager fallback when OEM reflection is restricted. */
    fun findService(instance: Any, interfaceHints: List<String>, candidateNames: List<String> = emptyList()): ServiceHandle? {
        val fields = allFields(instance.javaClass)
        candidateNames.forEach { name ->
            fields.firstOrNull { it.name == name }?.let { field ->
                read(field, instance)?.let { return ServiceHandle(field, it) }
            }
        }
        fields.forEach { field ->
            val value = read(field, instance) ?: return@forEach
            val names = buildList {
                add(field.type.name)
                add(value.javaClass.name)
                field.type.interfaces.forEach { add(it.name) }
                value.javaClass.interfaces.forEach { add(it.name) }
                var c: Class<*>? = value.javaClass.superclass
                while (c != null) {
                    add(c.name)
                    c.interfaces.forEach { add(it.name) }
                    c = c.superclass
                }
            }
            if (interfaceHints.any { hint -> names.any { it.contains(hint, ignoreCase = true) } }) return ServiceHandle(field, value)
        }

        val fallback = fallbackService(interfaceHints) ?: return null
        val field = candidateNames.asSequence().mapNotNull { name -> fields.firstOrNull { it.name == name } }.firstOrNull()
            ?: fields.firstOrNull { f -> interfaceHints.any { hint -> f.type.name.contains(hint, ignoreCase = true) } }
            ?: return null
        val delegate = serviceManagerInterface(fallback.first, fallback.second) ?: return null
        RuntimeDiagnostics.log(
            "COMPAT",
            "ServiceManager recovered ${fallback.first} field=${field.name} owner=${field.declaringClass.name}"
        )
        return ServiceHandle(field, delegate)
    }

    private fun fallbackService(hints: List<String>): Pair<String, String>? {
        val joined = hints.joinToString("|")
        return when {
            joined.contains("IJobScheduler", true) -> "jobscheduler" to "android.app.job.IJobScheduler\$Stub"
            joined.contains("IClipboard", true) -> "clipboard" to "android.content.IClipboard\$Stub"
            joined.contains("IAccountManager", true) -> "account" to "android.accounts.IAccountManager\$Stub"
            joined.contains("IAppOpsService", true) -> "appops" to "com.android.internal.app.IAppOpsService\$Stub"
            joined.contains("IUserManager", true) -> "user" to "android.os.IUserManager\$Stub"
            else -> null
        }
    }

    /** Obtains a platform AIDL interface directly from ServiceManager without relying on OEM cache layout. */
    fun serviceManagerInterface(serviceName: String, stubClassName: String): Any? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
        val binder = getService.invoke(null, serviceName) as? IBinder ?: return@runCatching null
        val stub = Class.forName(stubClassName)
        val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java).apply { isAccessible = true }
        asInterface.invoke(null, binder)
    }.onFailure {
        RuntimeDiagnostics.log("COMPAT", "ServiceManager fallback $serviceName/$stubClassName failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    fun findField(type: Class<*>, vararg names: String): Field? {
        val fields = allFields(type)
        names.forEach { name -> fields.firstOrNull { it.name == name }?.let { return it } }
        return null
    }

    fun findFieldAssignable(type: Class<*>, target: Class<*>): Field? = allFields(type).firstOrNull { target.isAssignableFrom(it.type) }

    fun allFields(type: Class<*>): List<Field> {
        val out = ArrayList<Field>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.forEach { field ->
                runCatching { field.isAccessible = true }
                out += field
            }
            current = current.superclass
        }
        return out
    }

    fun collectInterfaces(type: Class<*>): Array<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        var current: Class<*>? = type
        while (current != null) {
            out.addAll(current.interfaces)
            current.interfaces.forEach { collectParentInterfaces(it, out) }
            current = current.superclass
        }
        return out.toTypedArray()
    }

    fun write(field: Field, instance: Any?, value: Any?): Boolean = runCatching {
        field.isAccessible = true
        field.set(instance, value)
        true
    }.getOrDefault(false)

    private fun read(field: Field, instance: Any?): Any? = runCatching {
        field.isAccessible = true
        field.get(instance)
    }.getOrNull()

    private fun collectParentInterfaces(type: Class<*>, out: MutableSet<Class<*>>) {
        type.interfaces.forEach { if (out.add(it)) collectParentInterfaces(it, out) }
    }
}

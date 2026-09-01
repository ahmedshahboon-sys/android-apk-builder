package com.shahboun.multi

import android.os.Build
import android.os.IBinder
import java.lang.reflect.Field

/**
 * Central Android/OEM compatibility layer for Shahboun Clone Engine 2.0.
 *
 * All framework-private discovery should go through this object instead of each bridge
 * guessing one hard-coded field name. This keeps Samsung/Pixel/Xiaomi differences and
 * Android API drift in one place and lets diagnostics report the exact path selected.
 */
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

    /** Finds a framework field by candidate names first, then by type/interface hints. */
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
        return null
    }

    /**
     * Fallback for OEMs where the cached manager field cannot be read. It obtains the real Binder
     * from Android's ServiceManager and converts it with the platform AIDL Stub.asInterface method.
     */
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

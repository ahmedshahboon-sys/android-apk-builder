package com.shahboun.multi

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import java.io.File

/** Per-clone component enabled-state persistence; no SharedPreferences are shared across clone processes. */
object Runtime3ComponentStateStore {
    fun get(context: Context, packageName: String, slot: Int, component: ComponentName): Int {
        val file = stateFile(context, packageName, slot, component)
        if (!file.isFile) return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        return runCatching { file.readText().trim().toInt() }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    }

    fun put(context: Context, packageName: String, slot: Int, component: ComponentName, state: Int) {
        val dir = File(Runtime3ProcessMetadata.slotDir(context, packageName, slot), "component_states")
        require(dir.exists() || dir.mkdirs()) { "Unable to create Runtime 3 component-state directory" }
        val target = File(dir, fileName(component))
        val temp = File(dir, ".${target.name}.${android.os.Process.myPid()}.tmp")
        temp.writeText(state.toString())
        if (target.exists()) require(target.delete()) { "Unable to replace Runtime 3 component state" }
        require(temp.renameTo(target)) { "Unable to commit Runtime 3 component state" }
    }

    fun clear(context: Context, packageName: String, slot: Int) {
        File(Runtime3ProcessMetadata.slotDir(context, packageName, slot), "component_states").deleteRecursively()
    }

    private fun stateFile(context: Context, packageName: String, slot: Int, component: ComponentName): File =
        File(Runtime3ProcessMetadata.slotDir(context, packageName, slot), "component_states/${fileName(component)}")

    private fun fileName(component: ComponentName): String {
        val raw = component.flattenToString().toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(raw, Base64.NO_WRAP or Base64.URL_SAFE) + ".state"
    }
}

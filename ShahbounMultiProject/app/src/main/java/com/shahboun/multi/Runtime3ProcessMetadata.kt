package com.shahboun.multi

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Cross-process-safe immutable process assignment stored inside each Runtime 3 clone directory. */
object Runtime3ProcessMetadata {
    private const val FILE_NAME = "process.meta"

    fun read(context: Context, packageName: String, slot: Int, poolSize: Int): Int? = runCatching {
        val file = File(slotDir(context, packageName, slot), FILE_NAME)
        if (!file.isFile) return@runCatching null
        val value = file.readText().trim().toInt()
        value.takeIf { it in 0 until poolSize }
    }.getOrNull()

    fun write(context: Context, packageName: String, slot: Int, processIndex: Int) {
        val dir = slotDir(context, packageName, slot)
        require(dir.exists() || dir.mkdirs()) { "Unable to create Runtime 3 slot metadata directory" }
        val target = File(dir, FILE_NAME)
        val temp = File(dir, ".$FILE_NAME.tmp")
        temp.writeText(processIndex.toString())
        if (target.exists()) require(target.delete()) { "Unable to replace Runtime 3 process metadata" }
        require(temp.renameTo(target)) { "Unable to commit Runtime 3 process metadata" }
    }

    fun delete(context: Context, packageName: String, slot: Int) {
        File(slotDir(context, packageName, slot), FILE_NAME).delete()
    }

    private fun slotDir(context: Context, packageName: String, slot: Int): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(packageName.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(20)
        return File(context.applicationContext.filesDir, "clone_engine_v3/$digest/$slot")
    }
}

package com.shahboun.multi

import android.os.Process
import android.system.Os
import java.io.File

/** Process-local environment applied once before guest Application startup. */
object Runtime3ProcessEnvironment {
    private val lock = Any()
    @Volatile private var owner: String? = null

    fun activate(session: RuntimeSession, slotDir: File) = synchronized(lock) {
        val pkg = session.runtimePackage
        val identity = "${pkg.packageName}#${pkg.slot}"
        owner?.let { existing ->
            check(existing == identity) { "Runtime 3 process environment already owned by $existing, refused $identity" }
            return@synchronized
        }

        // Keep every Java/native process path aligned with RuntimeGuestContext and LoadedApk.
        // Previous builds accidentally recreated legacy <slot>/cache and <slot>/files while
        // Context APIs used <slot>/data/cache and <slot>/data/files, giving libraries two homes.
        val data = File(slotDir, "data").apply { require(exists() || mkdirs()) }
        val cache = File(data, "cache").apply { require(exists() || mkdirs()) }
        val files = File(data, "files").apply { require(exists() || mkdirs()) }
        File(data, "code_cache").apply { require(exists() || mkdirs()) }
        File(data, "no_backup").apply { require(exists() || mkdirs()) }

        System.setProperty("java.io.tmpdir", cache.absolutePath)
        System.setProperty("user.home", data.absolutePath)
        System.setProperty("shahboun.runtime.package", pkg.packageName)
        System.setProperty("shahboun.runtime.slot", pkg.slot.toString())

        runCatching { Os.setenv("HOME", data.absolutePath, true) }
            .onFailure { RuntimeDiagnostics.log("ENV3", "HOME fallback ${it.javaClass.simpleName}: ${it.message}") }
        runCatching { Os.setenv("TMPDIR", cache.absolutePath, true) }
            .onFailure { RuntimeDiagnostics.log("ENV3", "TMPDIR fallback ${it.javaClass.simpleName}: ${it.message}") }
        runCatching { Os.setenv("FILES_DIR", files.absolutePath, true) }
            .onFailure { RuntimeDiagnostics.log("ENV3", "FILES_DIR fallback ${it.javaClass.simpleName}: ${it.message}") }

        // Framework-visible process aliases are handled separately. argv[0] is best-effort only;
        // kernel pid/uid and SELinux identity are never forged.
        runCatching {
            val method = Process::class.java.getDeclaredMethod("setArgV0", String::class.java).apply { isAccessible = true }
            method.invoke(null, pkg.packageName)
        }.onSuccess {
            RuntimeDiagnostics.log("ENV3", "native argv0 pinned package=${pkg.packageName} slot=${pkg.slot}")
        }.onFailure {
            RuntimeDiagnostics.log("ENV3", "argv0 unavailable ${it.javaClass.simpleName}: ${it.message}")
        }

        owner = identity
        RuntimeDiagnostics.log(
            "ENV3",
            "activated $identity data=${data.absolutePath} cache=${cache.absolutePath} files=${files.absolutePath}"
        )
    }
}

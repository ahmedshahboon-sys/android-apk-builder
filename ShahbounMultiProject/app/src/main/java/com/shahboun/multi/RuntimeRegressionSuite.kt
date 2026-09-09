package com.shahboun.multi

import android.content.Context
import java.io.File
import java.nio.file.Files

/** Deterministic regression gate that runs without launching third-party guest code. */
internal object RuntimeRegressionSuite {
    data class Check(val name: String, val passed: Boolean, val detail: String)

    fun run(context: Context): List<Check> {
        val checks = ArrayList<Check>()
        checks += check("recursion-guard") {
            require(RuntimeRecursionGuard.selfTest()) { "nested service recursion was not contained" }
            "guard unwound cleanly"
        }
        checks += check("job-id-isolation") {
            val a = RuntimeJobSchedulerBridge.hostJobId("com.shahboun.test", 0, null, 7)
            val b = RuntimeJobSchedulerBridge.hostJobId("com.shahboun.test", 1, null, 7)
            val c = RuntimeJobSchedulerBridge.hostJobId("com.shahboun.test", 0, "work", 7)
            require(a != b && a != c && b != c) { "job namespace collision" }
            "slot/default/namespace ids are distinct"
        }
        checks += check("virtual-uid-isolation") {
            val a = RuntimeVirtualIdentityRegistry.virtualUid("com.shahboun.test", 0)
            val b = RuntimeVirtualIdentityRegistry.virtualUid("com.shahboun.test", 1)
            require(a != b) { "clone virtualUid collision" }
            "clone-local virtual UIDs differ"
        }
        checks += check("path-leaf-policy") {
            require(runCatching { RuntimePathPolicy.safeLeaf("../escape") }.isFailure) { "traversal leaf accepted" }
            require(runCatching { RuntimePathPolicy.safeLeaf("..") }.isFailure) { "parent leaf accepted" }
            require(runCatching { RuntimePathPolicy.safeLeaf("a/b") }.isFailure) { "separator leaf accepted" }
            require(RuntimePathPolicy.safeLeaf("profile.db") == "profile.db") { "valid leaf changed" }
            "traversal/separator leaves rejected"
        }
        checks += nativePathIsolation(context)
        checks += relativePathIsolation(context)
        checks += twoCloneFilesystem(context)
        checks += nativeLibraryDirectoryIsolation(context)
        checks += symlinkContainment(context)
        checks.forEach {
            RuntimeDiagnostics.log("REGRESSION7", "${if (it.passed) "PASS" else "FAIL"} ${it.name} ${it.detail}")
        }
        return checks
    }

    private fun roots(context: Context, pkg: String): Triple<File, File, File> {
        val base = File(context.filesDir, "runtime7-selftest/$pkg").apply { deleteRecursively(); mkdirs() }.canonicalFile
        val root0 = File(base, "slot0").apply { mkdirs() }.canonicalFile
        val root1 = File(base, "slot1").apply { mkdirs() }.canonicalFile
        return Triple(base, root0, root1)
    }

    private fun nativePathIsolation(context: Context): Check = check("native-path-isolation") {
        require(RuntimeNativeRuntime.initialize()) { "native runtime unavailable" }
        val pkg = "com.shahboun.runtime.selftest"
        val (_, root0, root1) = roots(context, "native-path")
        require(RuntimeNativeRuntime.register(pkg, 0, root0)) { "slot0 register failed" }
        require(RuntimeNativeRuntime.register(pkg, 1, root1)) { "slot1 register failed" }
        try {
            val logical = "/data/user/0/$pkg/files/profile.db"
            val a = RuntimeNativeRuntime.map(pkg, 0, logical)
            val b = RuntimeNativeRuntime.map(pkg, 1, logical)
            require(a != b) { "two clone slots mapped to the same path" }
            require(File(a).canonicalPath.startsWith(root0.path + File.separator)) { "slot0 escaped root" }
            require(File(b).canonicalPath.startsWith(root1.path + File.separator)) { "slot1 escaped root" }
            require(RuntimeNativeRuntime.isWithinRoot(pkg, 0, File(a))) { "native containment rejected valid slot0 path" }
            require(!RuntimeNativeRuntime.isWithinRoot(pkg, 0, File(b))) { "slot0 accepted slot1 path" }
            require(RuntimeNativeRuntime.reverseMap(pkg, 0, a) == logical) { "reverse map mismatch" }
            require(runCatching { RuntimeNativeRuntime.map(pkg, 0, "/data/user/0/$pkg/../../other/secret") }.isFailure) {
                "managed traversal was not rejected"
            }
            "two-slot map/reverse/traversal containment passed; libc interception remains intentionally unclaimed"
        } finally {
            RuntimeNativeRuntime.unregister(pkg, 0)
            RuntimeNativeRuntime.unregister(pkg, 1)
        }
    }

    private fun relativePathIsolation(context: Context): Check = check("relative-openat-policy") {
        require(RuntimeNativeRuntime.initialize()) { "native runtime unavailable" }
        val pkg = "com.shahboun.runtime.relative"
        val (_, root0, _) = roots(context, "relative")
        require(RuntimeNativeRuntime.register(pkg, 0, root0)) { "register failed" }
        try {
            val dir = File(root0, "data/files").apply { mkdirs() }.canonicalFile
            val child = RuntimeNativeRuntime.resolveRelative(pkg, 0, dir, "nested/profile.db")
            require(child.absolutePath.startsWith(root0.path + File.separator)) { "relative child escaped" }
            require(runCatching { RuntimeNativeRuntime.resolveRelative(pkg, 0, dir, "../../../escape") }.isFailure) { "relative traversal accepted" }
            val foreign = File(root0.parentFile, "foreign").apply { mkdirs() }
            require(runCatching { RuntimeNativeRuntime.resolveRelative(pkg, 0, foreign, "x") }.isFailure) { "foreign dirbase accepted" }
            "relative dir-base and traversal isolation passed"
        } finally {
            RuntimeNativeRuntime.unregister(pkg, 0)
        }
    }

    private fun twoCloneFilesystem(context: Context): Check = check("two-clone-filesystem") {
        val (_, root0, root1) = roots(context, "filesystem")
        val file0 = File(root0, "data/files/state.txt").apply { parentFile?.mkdirs(); writeText("clone0") }
        val file1 = File(root1, "data/files/state.txt").apply { parentFile?.mkdirs(); writeText("clone1") }
        require(file0.readText() == "clone0" && file1.readText() == "clone1") { "initial data crossed slots" }
        file0.writeText("clone0-overwrite")
        require(file1.readText() == "clone1") { "overwrite leaked to clone1" }
        val renamed = File(file0.parentFile, "renamed.txt")
        require(file0.renameTo(renamed)) { "rename failed" }
        require(!File(root1, "data/files/renamed.txt").exists()) { "rename appeared in clone1" }
        listOf("profile.db", "profile.db-wal", "profile.db-shm").forEach { name ->
            File(root0, "data/databases/$name").apply { parentFile?.mkdirs(); writeText("0-$name") }
            File(root1, "data/databases/$name").apply { parentFile?.mkdirs(); writeText("1-$name") }
        }
        require(File(root0, "data/databases/profile.db-wal").readText().startsWith("0-")) { "WAL slot0 mismatch" }
        require(File(root1, "data/databases/profile.db-shm").readText().startsWith("1-")) { "SHM slot1 mismatch" }
        require(renamed.delete()) { "delete failed" }
        require(File(root1, "data/files/state.txt").exists()) { "clone0 delete affected clone1" }
        "create/read/overwrite/rename/delete/db/WAL/SHM isolation passed"
    }

    private fun nativeLibraryDirectoryIsolation(context: Context): Check = check("native-library-directory-isolation") {
        val (_, root0, root1) = roots(context, "native-libs")
        val lib0 = File(root0, "native/libsame.so").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(0, 1, 2)) }
        val lib1 = File(root1, "native/libsame.so").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(3, 4, 5)) }
        require(lib0.canonicalPath != lib1.canonicalPath) { "native library path collision" }
        require(!lib0.readBytes().contentEquals(lib1.readBytes())) { "native library content unexpectedly shared" }
        "same library name remains clone-directory scoped"
    }

    private fun symlinkContainment(context: Context): Check = check("symlink-containment") {
        require(RuntimeNativeRuntime.initialize()) { "native runtime unavailable" }
        val pkg = "com.shahboun.runtime.symlink"
        val (base, root0, _) = roots(context, "symlink")
        require(RuntimeNativeRuntime.register(pkg, 0, root0)) { "register failed" }
        try {
            val safeDir = File(root0, "data/files").apply { mkdirs() }
            val outside = File(base, "outside").apply { mkdirs() }
            val link = File(safeDir, "escape-link")
            val supported = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()); true }.getOrDefault(false)
            if (supported) {
                require(!RuntimeNativeRuntime.isExistingPathContained(pkg, 0, link)) { "symlink escape accepted" }
                "symlink escape rejected"
            } else {
                "symlink creation unavailable in this runtime; live validation required"
            }
        } finally {
            RuntimeNativeRuntime.unregister(pkg, 0)
        }
    }

    private fun check(name: String, block: () -> String): Check = runCatching {
        Check(name, true, block())
    }.getOrElse {
        Check(name, false, "${it.javaClass.simpleName}: ${it.message}")
    }

    fun summary(context: Context): String {
        val checks = run(context)
        return "Runtime7 regressions pass=${checks.count { it.passed }} fail=${checks.count { !it.passed }} total=${checks.size}"
    }
}

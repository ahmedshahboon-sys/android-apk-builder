package com.shahboun.multi

import android.content.Context
import java.io.File

/** Small deterministic regression gate that runs without launching third-party guest code. */
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
        checks += nativePathIsolation(context)
        checks.forEach {
            RuntimeDiagnostics.log("REGRESSION5", "${if (it.passed) "PASS" else "FAIL"} ${it.name} ${it.detail}")
        }
        return checks
    }

    private fun nativePathIsolation(context: Context): Check = check("native-path-isolation") {
        require(RuntimeNativeRuntime.initialize()) { "native runtime unavailable" }
        val root0 = File(context.filesDir, "runtime5-selftest/slot0").apply { mkdirs() }.canonicalFile
        val root1 = File(context.filesDir, "runtime5-selftest/slot1").apply { mkdirs() }.canonicalFile
        val pkg = "com.shahboun.runtime.selftest"
        require(RuntimeNativeRuntime.register(pkg, 0, root0)) { "slot0 register failed" }
        require(RuntimeNativeRuntime.register(pkg, 1, root1)) { "slot1 register failed" }
        try {
            val logical = "/data/user/0/$pkg/files/profile.db"
            val a = RuntimeNativeRuntime.map(pkg, 0, logical)
            val b = RuntimeNativeRuntime.map(pkg, 1, logical)
            require(a != b) { "two clone slots mapped to the same path" }
            require(File(a).canonicalPath.startsWith(root0.path + File.separator)) { "slot0 escaped root" }
            require(File(b).canonicalPath.startsWith(root1.path + File.separator)) { "slot1 escaped root" }
            val reversed = RuntimeNativeRuntime.reverseMap(pkg, 0, a)
            require(reversed == logical) { "reverse map mismatch: $reversed" }
            val external = RuntimeNativeRuntime.map(pkg, 0, "/storage/emulated/0/Android/media/$pkg/DCIM/a.jpg")
            require(File(external).canonicalPath.startsWith(root0.path + File.separator)) { "external media escaped root" }
            "internal/external mapping and reverse mapping passed"
        } finally {
            RuntimeNativeRuntime.unregister(pkg, 0)
            RuntimeNativeRuntime.unregister(pkg, 1)
        }
    }

    private fun check(name: String, block: () -> String): Check = runCatching {
        Check(name, true, block())
    }.getOrElse {
        Check(name, false, "${it.javaClass.simpleName}: ${it.message}")
    }

    fun summary(context: Context): String {
        val checks = run(context)
        return "Runtime5 regressions pass=${checks.count { it.passed }} fail=${checks.count { !it.passed }} total=${checks.size}"
    }
}

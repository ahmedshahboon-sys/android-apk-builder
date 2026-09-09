package com.shahboun.multi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimeRegressionPolicyTest {
    @Test
    fun recursionGuardContainsNestedCalls() {
        assertTrue(RuntimeRecursionGuard.selfTest())
    }

    @Test
    fun jobIdsAreCloneAndNamespaceScoped() {
        val base = RuntimeJobSchedulerBridge.hostJobId("com.shahboun.test", 0, null, 17)
        val otherClone = RuntimeJobSchedulerBridge.hostJobId("com.shahboun.test", 1, null, 17)
        val namespace = RuntimeJobSchedulerBridge.hostJobId("com.shahboun.test", 0, "work", 17)
        val otherPackage = RuntimeJobSchedulerBridge.hostJobId("com.shahboun.other", 0, null, 17)
        assertNotEquals(base, otherClone)
        assertNotEquals(base, namespace)
        assertNotEquals(base, otherPackage)
    }

    @Test
    fun virtualUidIsStableAndCloneScoped() {
        val a1 = RuntimeVirtualIdentityRegistry.virtualUid("com.shahboun.test", 0)
        val a2 = RuntimeVirtualIdentityRegistry.virtualUid("com.shahboun.test", 0)
        val b = RuntimeVirtualIdentityRegistry.virtualUid("com.shahboun.test", 1)
        val c = RuntimeVirtualIdentityRegistry.virtualUid("com.shahboun.other", 0)
        assertEquals(a1, a2)
        assertNotEquals(a1, b)
        assertNotEquals(a1, c)
        assertTrue(a1 in 200_000..899_999)
    }

    @Test
    fun processNamesNormalizeDeterministically() {
        assertEquals("com.test", RuntimeVirtualIdentityRegistry.normalizeProcessName("com.test", null))
        assertEquals("com.test", RuntimeVirtualIdentityRegistry.normalizeProcessName("com.test", "com.test"))
        assertEquals("com.test:remote", RuntimeVirtualIdentityRegistry.normalizeProcessName("com.test", ":remote"))
        assertEquals("com.test:remote", RuntimeVirtualIdentityRegistry.normalizeProcessName("com.test", "com.test:remote"))
    }

    @Test
    fun intentAuthPayloadBindsCloneComponentAndSession() {
        val a = RuntimeIntentSecurity.payload("activity", "com.test", 0, "com.test.Main", "s1")
        val b = RuntimeIntentSecurity.payload("activity", "com.test", 1, "com.test.Main", "s1")
        val c = RuntimeIntentSecurity.payload("activity", "com.test", 0, "com.test.Other", "s1")
        val d = RuntimeIntentSecurity.payload("activity", "com.test", 0, "com.test.Main", "s2")
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, d)
        assertEquals(a, RuntimeIntentSecurity.payload("activity", "com.test", 0, "com.test.Main", "s1"))
    }

    @Test
    fun gapAuditNeverClaimsUnsafeNativeHooks() {
        val audit = RuntimeGapAudit.current().associateBy { it.area }
        assertEquals(RuntimeGapAudit.State.UNSUPPORTED, audit.getValue("Native libc interception").state)
        assertEquals(RuntimeGapAudit.State.UNSUPPORTED, audit.getValue("Linker namespace virtualization").state)
        assertEquals(RuntimeGapAudit.State.LIVE_VALIDATION_REQUIRED, audit.getValue("Samsung SM-G990E Android 16 heavy apps").state)
    }

    @Test
    fun leafPolicyRejectsTraversalAndSeparators() {
        assertEquals("profile.db", RuntimePathPolicy.safeLeaf("profile.db"))
        assertRejected("..")
        assertRejected(".")
        assertRejected("../secret")
        assertRejected("a/b")
        assertRejected("a\\b")
        assertRejected("bad\u0000name")
    }

    @Test
    fun childPolicyCannotEscapeRoot() {
        val root = File(System.getProperty("java.io.tmpdir"), "shahboun-path-test").canonicalFile
        val child = RuntimePathPolicy.child(root, "data/files")
        assertTrue(RuntimePathPolicy.isContained(root, child))
        assertFalse(RuntimePathPolicy.isContained(root, File(root, "../escape")))
        try {
            RuntimePathPolicy.child(root, "data/../../escape")
            fail("Traversal was accepted")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun assertRejected(value: String) {
        try {
            RuntimePathPolicy.safeLeaf(value)
            fail("Unsafe leaf was accepted: $value")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}

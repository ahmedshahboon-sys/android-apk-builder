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

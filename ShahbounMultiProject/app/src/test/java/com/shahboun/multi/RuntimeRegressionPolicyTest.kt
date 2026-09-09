package com.shahboun.multi

import java.io.File
import java.nio.file.Files
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
    fun binderIdentityRewritePreservesStringArrayRuntimeType() {
        val guest = "com.example.guest"
        val host = "com.shahboun.multi"
        val source = arrayOf(guest, "feature", guest)
        val rewritten = RuntimeBinderIdentitySanitizer.rewriteStringArrayForTest(source, guest, host)
        assertEquals(Array<String>::class.java, rewritten.javaClass)
        @Suppress("UNCHECKED_CAST")
        rewritten as Array<String>
        assertEquals(host, rewritten[0])
        assertEquals("feature", rewritten[1])
        assertEquals(host, rewritten[2])
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

    @Test
    fun relativeOpenAtStylePathCannotEscapeRoot() {
        val root = Files.createTempDirectory("shahboun-relative").toFile().canonicalFile
        val files = File(root, "data/files").apply { mkdirs() }.canonicalFile
        val valid = RuntimePathPolicy.resolveRelativeWithin(root, files, "nested/profile.db")
        assertTrue(RuntimePathPolicy.isContained(root, valid))
        try {
            RuntimePathPolicy.resolveRelativeWithin(root, files, "../../../outside.db")
            fail("Relative traversal was accepted")
        } catch (_: IllegalArgumentException) {
            // expected
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingSymlinkEscapeIsRejected() {
        val root = Files.createTempDirectory("shahboun-symlink-root").toFile().canonicalFile
        val outside = Files.createTempDirectory("shahboun-symlink-outside").toFile().canonicalFile
        val files = File(root, "data/files").apply { mkdirs() }.canonicalFile
        val link = File(files, "escape")
        try {
            val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()); true }.getOrDefault(false)
            if (!created) return
            try {
                RuntimePathPolicy.resolveRelativeWithin(root, files, "escape/secret.db")
                fail("Symlink escape was accepted")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
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

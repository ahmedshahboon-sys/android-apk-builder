package com.shahboun.multi

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
}

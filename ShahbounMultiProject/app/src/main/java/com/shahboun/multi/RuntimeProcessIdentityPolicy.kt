package com.shahboun.multi

/**
 * Separates the trusted physical Android process from guest-visible process aliases.
 *
 * The guest alias may intentionally become `com.example.app`, while the security boundary stays
 * `com.shahboun.multi:cloneN`. Never authorize a clone from the guest-visible name.
 */
internal object RuntimeProcessIdentityPolicy {
    fun expectedPhysicalProcess(applicationId: String, processIndex: Int): String =
        "$applicationId:clone$processIndex"

    fun isExpectedPhysicalProcess(physicalProcess: String, expectedPhysicalProcess: String): Boolean =
        physicalProcess == expectedPhysicalProcess
}

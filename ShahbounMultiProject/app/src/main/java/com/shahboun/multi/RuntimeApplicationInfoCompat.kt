package com.shahboun.multi

import android.content.pm.ApplicationInfo

/**
 * ApplicationInfo no longer exposes credentialProtectedDataDir in the public SDK used by
 * compileSdk 36. Runtime7 keeps one credential-protected clone root in dataDir, so callers that
 * need the semantic value use this package-local compatibility property.
 */
internal var ApplicationInfo.credentialProtectedDataDir: String?
    get() = dataDir
    set(value) {
        if (dataDir.isNullOrBlank() && !value.isNullOrBlank()) dataDir = value
    }

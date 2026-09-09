package com.shahboun.multi

import android.content.Context
import android.content.Intent
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val EXTRA_RUNTIME_AUTH = "shahboun.runtime.auth"
internal const val EXTRA_RUNTIME_SESSION = "shahboun.runtime.session"

/**
 * Authenticates host-stub intents so cloneId/session/component extras cannot be forged by another app.
 * The key is app-private and never leaves Shahboun storage. Extras are not trusted until HMAC verifies.
 */
internal object RuntimeIntentSecurity {
    private const val PREFS = "shahboun_runtime_auth_v1"
    private const val KEY_SECRET = "hmac_secret"
    private const val HMAC = "HmacSHA256"

    fun sign(context: Context, intent: Intent, session: RuntimeSession, purpose: String, component: String): Intent {
        val identity = RuntimeVirtualIdentityRegistry.forSession(session)
        return signFields(context, intent, purpose, identity.guestPackage, identity.cloneId, component, identity.sessionId)
    }

    /** Host-side signing path used before a clone RuntimeSession exists. */
    fun sign(context: Context, intent: Intent, pkg: RuntimePackage, purpose: String, component: String): Intent =
        signFields(context, intent, purpose, pkg.packageName, pkg.slot, component, RuntimeVirtualIdentityRegistry.sessionId(pkg))

    fun verify(context: Context, intent: Intent, session: RuntimeSession, purpose: String, component: String): Boolean {
        val identity = RuntimeVirtualIdentityRegistry.forSession(session)
        return verifyFields(context, intent, purpose, identity.guestPackage, identity.cloneId, component, identity.sessionId)
    }

    /** Verification path for control messages that intentionally do not bootstrap a guest session. */
    fun verify(context: Context, intent: Intent, pkg: RuntimePackage, purpose: String, component: String): Boolean =
        verifyFields(context, intent, purpose, pkg.packageName, pkg.slot, component, RuntimeVirtualIdentityRegistry.sessionId(pkg))

    private fun signFields(
        context: Context,
        intent: Intent,
        purpose: String,
        packageName: String,
        slot: Int,
        component: String,
        sessionId: String
    ): Intent {
        intent.putExtra(EXTRA_RUNTIME_SESSION, sessionId)
        intent.putExtra(EXTRA_RUNTIME_AUTH, mac(context, payload(purpose, packageName, slot, component, sessionId)))
        return intent
    }

    private fun verifyFields(
        context: Context,
        intent: Intent,
        purpose: String,
        packageName: String,
        slot: Int,
        component: String,
        sessionId: String
    ): Boolean {
        val suppliedSession = intent.getStringExtra(EXTRA_RUNTIME_SESSION) ?: return reject("missing-session", purpose, packageName, slot)
        if (suppliedSession != sessionId) return reject("session-mismatch", purpose, packageName, slot)
        val supplied = intent.getStringExtra(EXTRA_RUNTIME_AUTH) ?: return reject("missing-auth", purpose, packageName, slot)
        val expected = mac(context, payload(purpose, packageName, slot, component, sessionId))
        val ok = MessageDigest.isEqual(supplied.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))
        if (!ok) reject("hmac-mismatch", purpose, packageName, slot)
        return ok
    }

    /** Pure canonical payload used by tests; changing field order is a protocol version change. */
    internal fun payload(purpose: String, packageName: String, slot: Int, component: String, sessionId: String): String =
        listOf("v1", purpose, packageName, slot.toString(), component, sessionId).joinToString("\u001f")

    private fun mac(context: Context, payload: String): String {
        val key = secret(context.applicationContext)
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return Base64.encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun secret(context: Context): ByteArray = synchronized(this) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_SECRET, null)
        if (!existing.isNullOrBlank()) {
            return@synchronized runCatching { Base64.decode(existing, Base64.NO_WRAP or Base64.URL_SAFE) }
                .getOrNull()?.takeIf { it.size >= 32 } ?: createSecret(prefs)
        }
        createSecret(prefs)
    }

    private fun createSecret(prefs: android.content.SharedPreferences): ByteArray {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
        check(prefs.edit().putString(KEY_SECRET, encoded).commit()) { "Unable to persist Runtime auth key" }
        return bytes
    }

    private fun reject(reason: String, purpose: String, packageName: String, slot: Int): Boolean {
        RuntimeDiagnostics.log("SECURITY7", "runtime intent rejected reason=$reason purpose=$purpose guest=$packageName/$slot")
        return false
    }
}

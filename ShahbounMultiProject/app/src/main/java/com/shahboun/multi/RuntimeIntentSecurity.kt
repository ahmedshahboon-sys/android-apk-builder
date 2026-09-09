package com.shahboun.multi

import android.content.Context
import android.content.Intent
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val EXTRA_RUNTIME_AUTH = "shahboun.runtime.auth"
internal const val EXTRA_RUNTIME_SESSION = "shahboun.runtime.session"

/**
 * Authenticates host-stub intents so cloneId/session/component extras cannot be forged by another app.
 *
 * Runtime7.0.3 deliberately does NOT store the HMAC key in SharedPreferences. Android
 * SharedPreferences are not a reliable multi-process coordination primitive; the host and a freshly
 * spawned :clone process can otherwise observe different cached values and reject a route that was
 * correctly signed milliseconds earlier. The key below is a raw app-private file protected by the
 * Linux app sandbox and created under an OS file lock, so every Shahboun process consumes exactly
 * the same bytes.
 */
internal object RuntimeIntentSecurity {
    private const val HMAC = "HmacSHA256"
    private const val KEY_FILE = "shahboun_runtime_auth_v2.key"
    private const val LOCK_FILE = "shahboun_runtime_auth_v2.lock"
    private const val KEY_SIZE = 32

    @Volatile private var cachedSecret: ByteArray? = null
    @Volatile private var cachedPath: String? = null

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
        val suppliedSession = intent.getStringExtra(EXTRA_RUNTIME_SESSION)
            ?: return reject("missing-session", purpose, packageName, slot)
        if (suppliedSession != sessionId) {
            return reject("session-mismatch supplied=${shortId(suppliedSession)} expected=${shortId(sessionId)}", purpose, packageName, slot)
        }
        val supplied = intent.getStringExtra(EXTRA_RUNTIME_AUTH)
            ?: return reject("missing-auth", purpose, packageName, slot)
        val expected = mac(context, payload(purpose, packageName, slot, component, sessionId))
        val ok = MessageDigest.isEqual(
            supplied.toByteArray(Charsets.US_ASCII),
            expected.toByteArray(Charsets.US_ASCII)
        )
        if (!ok) reject("hmac-mismatch key=${keyFingerprint(context)} component=$component", purpose, packageName, slot)
        return ok
    }

    /** Pure canonical payload used by tests; changing field order is a protocol version change. */
    internal fun payload(purpose: String, packageName: String, slot: Int, component: String, sessionId: String): String =
        listOf("v1", purpose, packageName, slot.toString(), component, sessionId).joinToString("\u001f")

    private fun mac(context: Context, payload: String): String {
        val key = secret(context)
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return Base64.encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    /**
     * Returns one cross-process key for the installed host package.
     * The physical host Application owns this directory even while a guest LoadedApk is active.
     */
    private fun secret(context: Context): ByteArray {
        val keyFile = authKeyFile(context)
        val path = keyFile.absolutePath
        cachedSecret?.let { cached -> if (cachedPath == path) return cached.copyOf() }

        synchronized(this) {
            cachedSecret?.let { cached -> if (cachedPath == path) return cached.copyOf() }
            keyFile.parentFile?.let { parent -> require(parent.exists() || parent.mkdirs()) { "Unable to create Runtime auth directory" } }
            val lockFile = File(keyFile.parentFile, LOCK_FILE)
            val resolved = RandomAccessFile(lockFile, "rw").channel.use { channel ->
                channel.lock().use {
                    readKey(keyFile) ?: createKeyAtomically(keyFile)
                }
            }
            require(resolved.size == KEY_SIZE) { "Invalid Runtime auth key length=${resolved.size}" }
            cachedPath = path
            cachedSecret = resolved.copyOf()
            RuntimeDiagnostics.log("SECURITY7", "route auth key ready process=${RuntimeGuestProcessIdentity.hostProcessName()} fp=${fingerprint(resolved)}")
            return resolved.copyOf()
        }
    }

    private fun authKeyFile(context: Context): File {
        val physicalApp = MultiApplication.current
        val dataDir = runCatching { physicalApp?.applicationInfo?.dataDir }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { context.applicationInfo.dataDir }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: error("Physical host dataDir unavailable for Runtime auth")
        // Reject an accidentally virtualized clone dataDir. Auth is host-global by design.
        require(!dataDir.contains("/clone_engine_v3/")) { "Runtime auth attempted from virtual clone dataDir" }
        return File(File(dataDir, "no_backup"), KEY_FILE)
    }

    private fun readKey(file: File): ByteArray? = runCatching {
        if (!file.isFile) return@runCatching null
        file.readBytes().takeIf { it.size == KEY_SIZE }
    }.getOrNull()

    private fun createKeyAtomically(file: File): ByteArray {
        readKey(file)?.let { return it }
        val bytes = ByteArray(KEY_SIZE).also(SecureRandom()::nextBytes)
        val temp = File(file.parentFile, ".$KEY_FILE.${android.os.Process.myPid()}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (file.exists()) {
            temp.delete()
            return readKey(file) ?: error("Runtime auth key disappeared during creation")
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            return readKey(file) ?: error("Unable to commit Runtime auth key")
        }
        return readKey(file) ?: error("Unable to read committed Runtime auth key")
    }

    private fun keyFingerprint(context: Context): String = runCatching { fingerprint(secret(context)) }.getOrDefault("unknown")

    private fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .take(6)
        .joinToString("") { "%02x".format(it) }

    private fun shortId(value: String): String = if (value.length <= 24) value else value.take(24) + "…"

    private fun reject(reason: String, purpose: String, packageName: String, slot: Int): Boolean {
        RuntimeDiagnostics.log("SECURITY7", "runtime intent rejected reason=$reason purpose=$purpose guest=$packageName/$slot process=${RuntimeGuestProcessIdentity.hostProcessName()}")
        return false
    }
}

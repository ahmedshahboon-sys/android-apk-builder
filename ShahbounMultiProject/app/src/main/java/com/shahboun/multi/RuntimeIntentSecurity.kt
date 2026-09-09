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
 * Authenticates every private host-stub envelope.
 *
 * exported=false is not sufficient because guest code executes under the same installed host UID
 * and can manufacture explicit intents to another clone slot. The MAC binds package, clone slot,
 * component, purpose and immutable snapshot identity to one host-owned cross-process key.
 */
internal object RuntimeIntentSecurity {
    private const val HMAC = "HmacSHA256"
    private const val KEY_FILE = "shahboun_runtime_auth_v4.key"
    private const val LOCK_FILE = "shahboun_runtime_auth_v4.lock"
    private const val KEY_SIZE = 32

    @Volatile private var cachedSecret: ByteArray? = null
    @Volatile private var cachedPath: String? = null
    @Volatile private var physicalHostDataDir: String? = null

    fun initializeHost(context: Context) {
        val root = resolvePhysicalHostDataDir(context)
        physicalHostDataDir = root
        secret(context)
        RuntimeDiagnostics.log("SECURITY7", "runtime auth root=$root process=${RuntimeGuestProcessIdentity.hostProcessName()}")
    }

    fun sign(context: Context, intent: Intent, pkg: RuntimePackage, purpose: String, component: String): Intent {
        val sessionId = sessionId(pkg)
        intent.putExtra(EXTRA_RUNTIME_SESSION, sessionId)
        intent.putExtra(EXTRA_RUNTIME_AUTH, mac(context, payload(purpose, pkg.packageName, pkg.slot, component, sessionId)))
        return intent
    }

    fun sign(context: Context, intent: Intent, session: RuntimeSession, purpose: String, component: String): Intent =
        sign(context, intent, session.runtimePackage, purpose, component)

    fun verify(context: Context, intent: Intent, pkg: RuntimePackage, purpose: String, component: String): Boolean {
        val expectedSession = sessionId(pkg)
        val suppliedSession = intent.getStringExtra(EXTRA_RUNTIME_SESSION)
            ?: return reject("missing-session", purpose, pkg.packageName, pkg.slot)
        if (!MessageDigest.isEqual(suppliedSession.toByteArray(), expectedSession.toByteArray())) {
            return reject("session-mismatch", purpose, pkg.packageName, pkg.slot)
        }
        val supplied = intent.getStringExtra(EXTRA_RUNTIME_AUTH)
            ?: return reject("missing-auth", purpose, pkg.packageName, pkg.slot)
        val expected = mac(context, payload(purpose, pkg.packageName, pkg.slot, component, expectedSession))
        val ok = MessageDigest.isEqual(supplied.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))
        if (!ok) reject("hmac-mismatch", purpose, pkg.packageName, pkg.slot)
        return ok
    }

    fun verify(context: Context, intent: Intent, session: RuntimeSession, purpose: String, component: String): Boolean =
        verify(context, intent, session.runtimePackage, purpose, component)

    internal fun sessionId(pkg: RuntimePackage): String =
        "${pkg.packageName}#${pkg.slot}:${pkg.versionCode}:${pkg.sha256.take(12)}"

    internal fun payload(purpose: String, packageName: String, slot: Int, component: String, sessionId: String): String =
        listOf("v2", purpose, packageName, slot.toString(), component, sessionId).joinToString("\u001f")

    private fun mac(context: Context, input: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(secret(context), HMAC))
        return Base64.encodeToString(mac.doFinal(input.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun secret(context: Context): ByteArray {
        val keyFile = authKeyFile(context)
        val path = keyFile.absolutePath
        cachedSecret?.let { if (cachedPath == path) return it.copyOf() }
        synchronized(this) {
            cachedSecret?.let { if (cachedPath == path) return it.copyOf() }
            keyFile.parentFile?.let { require(it.exists() || it.mkdirs()) { "Unable to create runtime auth directory" } }
            val lock = File(keyFile.parentFile, LOCK_FILE)
            val key = RandomAccessFile(lock, "rw").channel.use { channel ->
                channel.lock().use { readKey(keyFile) ?: createKeyAtomically(keyFile) }
            }
            require(key.size == KEY_SIZE) { "Invalid runtime auth key length=${key.size}" }
            cachedPath = path
            cachedSecret = key.copyOf()
            return key.copyOf()
        }
    }

    private fun authKeyFile(context: Context): File {
        val root = physicalHostDataDir ?: synchronized(this) {
            physicalHostDataDir ?: resolvePhysicalHostDataDir(context).also { physicalHostDataDir = it }
        }
        require(!root.contains("/clone_engine_v3/")) { "Runtime auth root resolved into clone storage" }
        return File(File(root, "no_backup"), KEY_FILE)
    }

    private fun resolvePhysicalHostDataDir(context: Context): String {
        val candidates = listOfNotNull(
            runCatching { context.dataDir?.canonicalPath }.getOrNull(),
            runCatching { context.filesDir.parentFile?.canonicalPath }.getOrNull(),
            runCatching { context.noBackupFilesDir.parentFile?.canonicalPath }.getOrNull()
        )
        return candidates.firstOrNull { it.isNotBlank() && !it.contains("/clone_engine_v3/") }
            ?: error("Physical host dataDir unavailable")
    }

    private fun readKey(file: File): ByteArray? = runCatching {
        if (!file.isFile) return@runCatching null
        file.readBytes().takeIf { it.size == KEY_SIZE }
    }.getOrNull()

    private fun createKeyAtomically(file: File): ByteArray {
        readKey(file)?.let { return it }
        val bytes = ByteArray(KEY_SIZE).also(SecureRandom()::nextBytes)
        val tmp = File(file.parentFile, ".$KEY_FILE.${android.os.Process.myPid()}.tmp")
        FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            return readKey(file) ?: error("Unable to commit runtime auth key")
        }
        return readKey(file) ?: error("Unable to read runtime auth key")
    }

    private fun reject(reason: String, purpose: String, packageName: String, slot: Int): Boolean {
        RuntimeDiagnostics.log("SECURITY7", "runtime envelope rejected reason=$reason purpose=$purpose guest=$packageName/$slot")
        return false
    }
}

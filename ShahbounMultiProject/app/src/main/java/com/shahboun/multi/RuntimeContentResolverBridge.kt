package com.shahboun.multi

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentValues
import android.content.OperationApplicationException
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.util.ArrayList

/**
 * ContentResolver compatibility bridge.
 *
 * Android's ContentResolver.wrap(ContentProvider) is intentionally test-oriented and its hidden
 * acquireProvider/acquireUnstableProvider methods throw UnsupportedOperationException. Modern apps
 * routinely acquire ContentProviderClient instances, so Android 10+ must keep the framework's real
 * ApplicationContentResolver. Clone providers are still created by RuntimeComponentHost; this file
 * keeps the old local multiplexer only for legacy releases.
 */
class RuntimeContentResolverBridge(
    private val session: RuntimeSession,
    private val host: RuntimeComponentHost,
    private val systemResolver: ContentResolver
) {
    val resolver: ContentResolver by lazy {
        if (Build.VERSION.SDK_INT >= 29) {
            RuntimeDiagnostics.log(
                "CONTENT",
                "resolver mode=system-client-safe ${session.runtimePackage.packageName}/${session.runtimePackage.slot} sdk=${Build.VERSION.SDK_INT}"
            )
            systemResolver
        } else {
            RuntimeDiagnostics.log(
                "CONTENT",
                "resolver mode=legacy-multiplexer ${session.runtimePackage.packageName}/${session.runtimePackage.slot}"
            )
            ContentResolver.wrap(Multiplexer(session, host, systemResolver))
        }
    }

    private class Multiplexer(
        private val session: RuntimeSession,
        private val host: RuntimeComponentHost,
        private val system: ContentResolver
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true

        private fun local(uri: Uri): ContentProvider? = host.providerForAuthority(uri.authority)
        private fun local(authority: String?): ContentProvider? = host.providerForAuthority(authority)

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = scoped(uri) { provider ->
            @Suppress("UNCHECKED_CAST")
            provider.query(uri, projection as? Array<String>, selection, selectionArgs as? Array<String>, sortOrder)
        } ?: system.query(uri, projection, selection, selectionArgs, sortOrder)

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?
        ): Cursor? = scoped(uri) { provider ->
            @Suppress("UNCHECKED_CAST")
            provider.query(uri, projection as? Array<String>, queryArgs, cancellationSignal)
        } ?: system.query(uri, projection, queryArgs, cancellationSignal)

        override fun getType(uri: Uri): String? = scoped(uri) { it.getType(uri) } ?: system.getType(uri)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = scoped(uri) { it.insert(uri, values) } ?: system.insert(uri, values)
        override fun insert(uri: Uri, values: ContentValues?, extras: Bundle?): Uri? = scoped(uri) { it.insert(uri, values, extras) } ?: system.insert(uri, values, extras)
        override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int = scoped(uri) { provider ->
            @Suppress("UNCHECKED_CAST") provider.bulkInsert(uri, values as Array<ContentValues>)
        } ?: system.bulkInsert(uri, values)
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = scoped(uri) { provider ->
            @Suppress("UNCHECKED_CAST") provider.delete(uri, selection, selectionArgs as? Array<String>)
        } ?: system.delete(uri, selection, selectionArgs)
        override fun delete(uri: Uri, extras: Bundle?): Int = scoped(uri) { it.delete(uri, extras) } ?: system.delete(uri, extras)
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = scoped(uri) { provider ->
            @Suppress("UNCHECKED_CAST") provider.update(uri, values, selection, selectionArgs as? Array<String>)
        } ?: system.update(uri, values, selection, selectionArgs)
        override fun update(uri: Uri, values: ContentValues?, extras: Bundle?): Int = scoped(uri) { it.update(uri, values, extras) } ?: system.update(uri, values, extras)

        @Throws(OperationApplicationException::class)
        override fun applyBatch(authority: String, operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            val provider = local(authority)
            if (provider != null) {
                RuntimeDiagnostics.log("CONTENT", "local batch $authority ${session.runtimePackage.packageName}/${session.runtimePackage.slot} count=${operations.size}")
                return RuntimeExecutionScope.withSession(session) { provider.applyBatch(authority, operations) }
            }
            return system.applyBatch(authority, operations)
        }

        override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? {
            val provider = local(authority)
            if (provider != null) {
                RuntimeDiagnostics.log("CONTENT", "local call $authority method=$method ${session.runtimePackage.packageName}/${session.runtimePackage.slot}")
                return RuntimeExecutionScope.withSession(session) { provider.call(authority, method, arg, extras) }
            }
            return system.call(authority, method, arg, extras)
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = super.call(method, arg, extras)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = scoped(uri) { it.openFile(uri, mode) } ?: system.openFileDescriptor(uri, mode)
        override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? = scoped(uri) { it.openFile(uri, mode, signal) } ?: system.openFileDescriptor(uri, mode, signal)
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? = scoped(uri) { it.openAssetFile(uri, mode) } ?: system.openAssetFileDescriptor(uri, mode)
        override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor? = scoped(uri) { it.openAssetFile(uri, mode, signal) } ?: system.openAssetFileDescriptor(uri, mode, signal)
        override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor? = scoped(uri) { it.openTypedAssetFile(uri, mimeTypeFilter, opts) } ?: system.openTypedAssetFileDescriptor(uri, mimeTypeFilter, opts)
        override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor? = scoped(uri) { it.openTypedAssetFile(uri, mimeTypeFilter, opts, signal) } ?: system.openTypedAssetFileDescriptor(uri, mimeTypeFilter, opts, signal)

        private fun <T> scoped(uri: Uri, block: (ContentProvider) -> T): T? {
            val provider = local(uri) ?: return null
            RuntimeDiagnostics.log("CONTENT", "local ${uri.authority} ${session.runtimePackage.packageName}/${session.runtimePackage.slot}")
            return RuntimeExecutionScope.withSession(session) { block(provider) }
        }
    }
}

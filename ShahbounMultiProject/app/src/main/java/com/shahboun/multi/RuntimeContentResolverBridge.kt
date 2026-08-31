package com.shahboun.multi

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor

/**
 * Public-API ContentResolver bridge (API 29+) that keeps clone-owned authorities inside
 * the clone runtime while forwarding unrelated authorities (contacts/media/etc.) to Android.
 */
class RuntimeContentResolverBridge(
    private val session: RuntimeSession,
    private val host: RuntimeComponentHost,
    private val systemResolver: ContentResolver
) {
    val resolver: ContentResolver by lazy {
        ContentResolver.wrap(Multiplexer(session, host, systemResolver))
    }

    private class Multiplexer(
        private val session: RuntimeSession,
        private val host: RuntimeComponentHost,
        private val system: ContentResolver
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true

        private fun local(uri: Uri): ContentProvider? = host.providerForAuthority(uri.authority)

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

        override fun insert(uri: Uri, values: ContentValues?): Uri? =
            scoped(uri) { it.insert(uri, values) } ?: system.insert(uri, values)

        override fun insert(uri: Uri, values: ContentValues?, extras: Bundle?): Uri? =
            scoped(uri) { it.insert(uri, values, extras) } ?: system.insert(uri, values, extras)

        override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int =
            scoped(uri) { provider ->
                @Suppress("UNCHECKED_CAST")
                provider.bulkInsert(uri, values as Array<ContentValues>)
            } ?: system.bulkInsert(uri, values)

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            scoped(uri) { provider ->
                @Suppress("UNCHECKED_CAST")
                provider.delete(uri, selection, selectionArgs as? Array<String>)
            } ?: system.delete(uri, selection, selectionArgs)

        override fun delete(uri: Uri, extras: Bundle?): Int =
            scoped(uri) { it.delete(uri, extras) } ?: system.delete(uri, extras)

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = scoped(uri) { provider ->
            @Suppress("UNCHECKED_CAST")
            provider.update(uri, values, selection, selectionArgs as? Array<String>)
        } ?: system.update(uri, values, selection, selectionArgs)

        override fun update(uri: Uri, values: ContentValues?, extras: Bundle?): Int =
            scoped(uri) { it.update(uri, values, extras) } ?: system.update(uri, values, extras)

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            return super.call(method, arg, extras)
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
            scoped(uri) { it.openFile(uri, mode) } ?: system.openFileDescriptor(uri, mode)

        private fun <T> scoped(uri: Uri, block: (ContentProvider) -> T): T? {
            val provider = local(uri) ?: return null
            RuntimeDiagnostics.log(
                "CONTENT",
                "local ${uri.authority} ${session.runtimePackage.packageName}/${session.runtimePackage.slot}"
            )
            return RuntimeExecutionScope.withSession(session) { block(provider) }
        }
    }
}

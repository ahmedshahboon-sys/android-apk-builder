package com.shahboun.multi

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads the official Cairo font once, stores it privately, then reuses it offline.
 * System sans is only a first-run fallback while Cairo is not cached yet.
 */
object CairoFontManager {
    private const val REGULAR_URL = "https://fonts.gstatic.com/s/cairo/v28/SLXgc1nY6HkvangtZmpQdkhzfH5lkSs2SgRjCAGMQ1z0hOA-W1ToLQ-HmkA.ttf"
    private const val MEDIUM_URL = "https://fonts.gstatic.com/s/cairo/v28/SLXgc1nY6HkvangtZmpQdkhzfH5lkSs2SgRjCAGMQ1z0hNI-W1ToLQ-HmkA.ttf"
    private const val BOLD_URL = "https://fonts.gstatic.com/s/cairo/v28/SLXgc1nY6HkvangtZmpQdkhzfH5lkSs2SgRjCAGMQ1z0hAc5W1ToLQ-HmkA.ttf"

    private val cache = ConcurrentHashMap<Int, Typeface>()
    @Volatile private var preparing = false

    fun typeface(context: Context, weight: Int): Typeface {
        val normalized = when {
            weight >= 700 -> 700
            weight >= 500 -> 500
            else -> 400
        }
        cache[normalized]?.let { return it }
        val file = fontFile(context, normalized)
        val face = if (file.exists() && file.length() > 1024) {
            runCatching { Typeface.createFromFile(file) }.getOrNull()
        } else null
        return (face ?: Typeface.create("sans-serif", if (normalized >= 700) Typeface.BOLD else Typeface.NORMAL)).also {
            if (face != null) cache[normalized] = it
        }
    }

    fun prepare(context: Context, onReady: (() -> Unit)? = null) {
        if (isReady(context)) {
            onReady?.invoke()
            return
        }
        if (preparing) return
        preparing = true
        val app = context.applicationContext
        Thread({
            try {
                downloadIfMissing(app, 400, REGULAR_URL)
                downloadIfMissing(app, 500, MEDIUM_URL)
                downloadIfMissing(app, 700, BOLD_URL)
                cache.clear()
                RuntimeDiagnostics.log("UI", "Cairo font cache ready")
                onReady?.invoke()
            } catch (t: Throwable) {
                RuntimeDiagnostics.log("UI", "Cairo font download deferred: ${t.message}")
            } finally {
                preparing = false
            }
        }, "shahboun-cairo-font").start()
    }

    fun applyTo(view: View, context: Context) {
        if (view is TextView) {
            val weight = if (view.typeface?.isBold == true) 700 else 400
            view.typeface = typeface(context, weight)
            view.includeFontPadding = false
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyTo(view.getChildAt(i), context)
        }
    }

    fun isReady(context: Context): Boolean = listOf(400, 500, 700).all {
        fontFile(context, it).let { file -> file.exists() && file.length() > 1024 }
    }

    private fun fontFile(context: Context, weight: Int): File {
        val dir = File(context.filesDir, "fonts").apply { mkdirs() }
        return File(dir, "cairo_$weight.ttf")
    }

    private fun downloadIfMissing(context: Context, weight: Int, source: String) {
        val target = fontFile(context, weight)
        if (target.exists() && target.length() > 1024) return
        val temp = File(target.parentFile, target.name + ".tmp")
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ShahbounMulti/0.2")
        }
        try {
            require(connection.responseCode in 200..299) { "font HTTP ${connection.responseCode}" }
            connection.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            require(temp.length() > 1024) { "font response is empty" }
            if (target.exists()) target.delete()
            require(temp.renameTo(target)) { "cannot store Cairo font" }
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }
}

package com.shahboun.numberlookup

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class LookupClient {
    fun search(c: SourceConfig, query: String, byPhone: Boolean): SourceOutcome {
        return try {
            require(c.baseUrl.startsWith("https://")) { "HTTPS فقط" }
            val path = if (byPhone) c.phonePath else c.namePath
            val endpoint = c.baseUrl.trimEnd('/') + "/" + path.trimStart('/')
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val method = c.method.trim().uppercase().ifBlank { "GET" }
            require(method == "GET" || method == "POST") { "طريقة الطلب يجب أن تكون GET أو POST" }
            val separator = if (endpoint.contains('?')) "&" else "?"
            val url = if (method == "GET") URL("$endpoint$separator${c.queryParam}=$encoded") else URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 6000
                readTimeout = 9000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "LibyaNumberAggregator/0.2")
                if (c.bearerToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${c.bearerToken}")
                if (method == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    outputStream.use { it.write("${c.queryParam}=$encoded".toByteArray()) }
                }
            }
            try {
                val status = conn.responseCode
                val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val msg = when (status) {
                        401, 403 -> "يتطلب مصادقة/صلاحية (HTTP $status)"
                        404 -> "المسار غير موجود (HTTP 404)"
                        429 -> "تم بلوغ حد الطلبات (HTTP 429)"
                        else -> "خطأ HTTP $status"
                    }
                    SourceOutcome(c.name, false, message = msg)
                } else {
                    val parsed = parse(text, c.name)
                    SourceOutcome(c.name, true, parsed, if (parsed.isEmpty()) "اتصل بنجاح، لا نتائج" else "متصل")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            SourceOutcome(c.name, false, message = friendlyError(e))
        }
    }

    private fun friendlyError(e: Exception): String {
        val n = e.javaClass.simpleName
        return when {
            n.contains("UnknownHost", true) -> "تعذر الوصول للمضيف"
            n.contains("Timeout", true) -> "انتهت مهلة الاتصال"
            n.contains("SSL", true) -> "خطأ اتصال آمن SSL"
            else -> e.message?.take(100) ?: n
        }
    }

    private fun parse(text: String, source: String): List<LookupResult> {
        if (text.isBlank()) return emptyList()
        val root: Any = try { JSONArray(text) } catch (_: Exception) {
            try { JSONObject(text) } catch (_: Exception) { return emptyList() }
        }
        val arr = when (root) {
            is JSONArray -> root
            is JSONObject -> when {
                root.optJSONArray("results") != null -> root.getJSONArray("results")
                root.optJSONArray("data") != null -> root.getJSONArray("data")
                root.optJSONArray("contacts") != null -> root.getJSONArray("contacts")
                root.optJSONArray("items") != null -> root.getJSONArray("items")
                root.optJSONObject("data") != null -> JSONArray().put(root.getJSONObject("data"))
                else -> JSONArray().put(root)
            }
            else -> JSONArray()
        }
        val out = mutableListOf<LookupResult>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val number = first(o, "number", "phone", "mobile", "msisdn", "telephone")
            val name = first(o, "name", "full_name", "fullname", "title", "contact_name", "display_name")
            if (number.isNotBlank() || name.isNotBlank()) out += LookupResult(number, name, source)
        }
        return out
    }

    private fun first(o: JSONObject, vararg keys: String): String {
        keys.forEach { k ->
            val v = o.optString(k, "").trim()
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }
}

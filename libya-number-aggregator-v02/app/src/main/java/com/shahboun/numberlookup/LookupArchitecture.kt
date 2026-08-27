package com.shahboun.numberlookup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/** Unified phone representation used before any provider is called. */
data class NormalizedPhone(
    val originalInput: String,
    val localNumber: String,
    val e164Number: String,
    val countryCode: String = "LY",
    val callingCode: String = "+218",
    val isValidFormat: Boolean
)

object LibyanPhoneNormalizer {
    fun normalize(input: String): NormalizedPhone {
        val original = input.trim()
        var digits = original.replace(Regex("[^0-9+]"), "")
        if (digits.startsWith("00218")) digits = "+218" + digits.removePrefix("00218")
        if (digits.startsWith("218") && !digits.startsWith("+")) digits = "+$digits"
        val national = when {
            digits.startsWith("+218") -> digits.removePrefix("+218").trimStart('0')
            digits.startsWith("0") -> digits.removePrefix("0")
            else -> digits.trimStart('+')
        }
        val local = if (national.isBlank()) "" else "0$national"
        val e164 = if (national.isBlank()) "" else "+218$national"
        val valid = national.length == 9 && national.startsWith(Regex("9[1234]"))
        return NormalizedPhone(original, local, e164, isValidFormat = valid)
    }
}

enum class ProviderState { READY, NEEDS_CONFIGURATION, DISABLED, RATE_LIMITED, ERROR }

data class ProviderHealth(
    val id: String,
    val displayName: String,
    val enabled: Boolean,
    val priority: Int,
    val state: ProviderState,
    val lastMessage: String = "",
    val lastResponseTimeMs: Long? = null,
    val successCount: Int = 0
)

data class UnifiedLookupResult(
    val phoneNumber: String,
    val normalizedNumber: String,
    val primaryName: String? = null,
    val aliases: List<String> = emptyList(),
    val carrier: String? = null,
    val country: String? = "LY",
    val lineType: String? = null,
    val spamScore: Double? = null,
    val confidence: Double? = null,
    val source: String,
    val sourcesMatched: List<String> = listOf(source),
    val responseTimeMs: Long? = null,
    val isValid: Boolean? = null,
    val found: Boolean = !primaryName.isNullOrBlank(),
    val metadata: Map<String, String> = emptyMap()
)

data class LookupEnvelope(
    val results: List<UnifiedLookupResult>,
    val providerHealth: List<ProviderHealth>,
    val fromCache: Boolean = false,
    val normalizedPhone: NormalizedPhone? = null
)

interface LookupProvider {
    val id: String
    val displayName: String
    val priority: Int
    val enabled: Boolean
    val supportsPhoneLookup: Boolean get() = true
    val supportsNameSearch: Boolean get() = false
    fun isConfigured(): Boolean
    suspend fun lookupByPhone(phone: NormalizedPhone): List<UnifiedLookupResult>
    suspend fun searchByName(name: String): List<UnifiedLookupResult> = emptyList()
    suspend fun healthCheck(): ProviderHealth
}

/** Local result cache. No external API keys or raw API responses are stored. */
class LookupCache(context: Context) {
    private val p = context.getSharedPreferences("lookup_cache_v1", Context.MODE_PRIVATE)
    private val ttlMs = 7L * 24 * 60 * 60 * 1000

    fun get(phone: NormalizedPhone): UnifiedLookupResult? {
        val raw = p.getString("r_${phone.e164Number}", null) ?: return null
        return try {
            val o = JSONObject(raw)
            val ts = o.optLong("ts")
            if (System.currentTimeMillis() - ts > ttlMs) return null
            UnifiedLookupResult(
                phoneNumber = o.optString("phone"),
                normalizedNumber = o.optString("normalized"),
                primaryName = o.optString("name").ifBlank { null },
                aliases = jsonArrayToList(o.optJSONArray("aliases")),
                carrier = o.optString("carrier").ifBlank { null },
                country = o.optString("country", "LY"),
                lineType = o.optString("lineType").ifBlank { null },
                spamScore = if (o.has("spamScore")) o.optDouble("spamScore") else null,
                confidence = if (o.has("confidence")) o.optDouble("confidence") else null,
                source = o.optString("source", "Local Cache"),
                sourcesMatched = jsonArrayToList(o.optJSONArray("sources")),
                isValid = if (o.has("isValid")) o.optBoolean("isValid") else null,
                found = o.optBoolean("found", true)
            )
        } catch (_: Exception) { null }
    }

    fun put(result: UnifiedLookupResult) {
        if (!result.found || result.primaryName.isNullOrBlank()) return
        val o = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("phone", result.phoneNumber)
            .put("normalized", result.normalizedNumber)
            .put("name", result.primaryName)
            .put("aliases", JSONArray(result.aliases))
            .put("carrier", result.carrier)
            .put("country", result.country)
            .put("lineType", result.lineType)
            .put("source", result.source)
            .put("sources", JSONArray(result.sourcesMatched))
            .put("found", result.found)
        result.spamScore?.let { o.put("spamScore", it) }
        result.confidence?.let { o.put("confidence", it) }
        result.isValid?.let { o.put("isValid", it) }
        p.edit().putString("r_${result.normalizedNumber}", o.toString()).apply()
    }

    fun clearNumber(phone: NormalizedPhone) { p.edit().remove("r_${phone.e164Number}").apply() }

    private fun jsonArrayToList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }
}

/** Existing project sources 1..5 remain intact and are adapted into the new interface. */
class ExistingProvider(private val config: SourceConfig, private val client: LookupClient = LookupClient()) : LookupProvider {
    override val id = "existing_${config.id}"
    override val displayName = config.name
    override val priority = config.id * 10
    override val enabled = config.enabled
    override val supportsNameSearch = config.namePath.isNotBlank()
    override fun isConfigured() = config.baseUrl.startsWith("https://")

    override suspend fun lookupByPhone(phone: NormalizedPhone): List<UnifiedLookupResult> = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val outcome = client.search(config, phone.e164Number, true)
        val elapsed = System.currentTimeMillis() - started
        if (!outcome.ok) emptyList() else outcome.results.map {
            UnifiedLookupResult(
                phoneNumber = it.number.ifBlank { phone.localNumber },
                normalizedNumber = phone.e164Number,
                primaryName = it.name.ifBlank { null },
                source = displayName,
                responseTimeMs = elapsed,
                found = it.name.isNotBlank()
            )
        }
    }

    override suspend fun searchByName(name: String): List<UnifiedLookupResult> = withContext(Dispatchers.IO) {
        if (!supportsNameSearch) return@withContext emptyList()
        val started = System.currentTimeMillis()
        val outcome = client.search(config, name, false)
        val elapsed = System.currentTimeMillis() - started
        if (!outcome.ok) emptyList() else outcome.results.map {
            val normalized = runCatching { LibyanPhoneNormalizer.normalize(it.number).e164Number }.getOrDefault(it.number)
            UnifiedLookupResult(it.number, normalized, it.name.ifBlank { null }, source = displayName, responseTimeMs = elapsed, found = it.name.isNotBlank())
        }
    }

    override suspend fun healthCheck() = ProviderHealth(id, displayName, enabled, priority,
        when { !enabled -> ProviderState.DISABLED; !isConfigured() -> ProviderState.NEEDS_CONFIGURATION; else -> ProviderState.READY })
}

class LocalContactsProvider(private val context: Context) : LookupProvider {
    override val id = "local_contacts"
    override val displayName = "جهات اتصال الهاتف"
    override val priority = 1
    override val enabled = true
    override val supportsNameSearch = true
    override fun isConfigured() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override suspend fun lookupByPhone(phone: NormalizedPhone): List<UnifiedLookupResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext emptyList()
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val out = mutableListOf<UnifiedLookupResult>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val di = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            while (c.moveToNext()) {
                coroutineContext.ensureActive()
                val raw = c.getString(ni).orEmpty()
                val n = LibyanPhoneNormalizer.normalize(raw)
                if (n.e164Number == phone.e164Number) {
                    val name = c.getString(di).orEmpty().trim()
                    if (name.isNotBlank()) out += UnifiedLookupResult(raw, phone.e164Number, name, source = displayName, confidence = 1.0)
                }
            }
        }
        out
    }

    override suspend fun searchByName(name: String): List<UnifiedLookupResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext emptyList()
        val q = name.trim()
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val out = mutableListOf<UnifiedLookupResult>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val di = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            while (c.moveToNext()) {
                coroutineContext.ensureActive()
                val display = c.getString(di).orEmpty()
                if (display.contains(q, ignoreCase = true)) {
                    val raw = c.getString(ni).orEmpty()
                    out += UnifiedLookupResult(raw, LibyanPhoneNormalizer.normalize(raw).e164Number, display, source = displayName, confidence = 1.0)
                }
            }
        }
        out.take(100)
    }

    override suspend fun healthCheck() = ProviderHealth(id, displayName, enabled, priority,
        if (isConfigured()) ProviderState.READY else ProviderState.NEEDS_CONFIGURATION,
        if (isConfigured()) "محلي فقط" else "يحتاج إذن جهات الاتصال")
}

abstract class HttpLookupProvider(protected val context: Context) : LookupProvider {
    protected val prefs = context.getSharedPreferences("provider_config_v1", Context.MODE_PRIVATE)
    protected open val connectTimeoutMs = 6000
    protected open val readTimeoutMs = 9000
    protected open val retryCount = 1

    protected suspend fun requestJson(
        url: URL,
        headers: Map<String, String> = emptyMap()
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        var last: Exception? = null
        repeat(retryCount + 1) { attempt ->
            coroutineContext.ensureActive()
            try {
                return@withContext withTimeout((connectTimeoutMs + readTimeoutMs + 2000).toLong()) {
                    val c = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = connectTimeoutMs
                        readTimeout = readTimeoutMs
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("User-Agent", "LibyaNumberAggregator/0.5")
                        headers.forEach { (k, v) -> if (v.isNotBlank()) setRequestProperty(k, v) }
                    }
                    try {
                        val code = c.responseCode
                        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                        if (code == 429) return@withTimeout code to body
                        if (code in 500..599 && attempt < retryCount) throw RuntimeException("HTTP $code")
                        code to body
                    } finally { c.disconnect() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                last = e
                if (attempt < retryCount) delay(250L * (attempt + 1))
            }
        }
        throw last ?: RuntimeException("Temporary lookup error")
    }

    protected fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
}

class CallerKitProvider(context: Context) : HttpLookupProvider(context) {
    override val id = "callerkit"
    override val displayName = "CallerKit"
    override val priority get() = prefs.getInt("CALLERKIT_PRIORITY", 100)
    override val enabled get() = prefs.getBoolean("CALLERKIT_ENABLED", true)
    override val supportsNameSearch = true
    private val base get() = prefs.getString("CALLERKIT_BASE_URL", "").orEmpty().trim()
    private val phonePath get() = prefs.getString("CALLERKIT_LOOKUP_PATH", "").orEmpty().trim()
    private val namePath get() = prefs.getString("CALLERKIT_NAME_SEARCH_PATH", "").orEmpty().trim()
    private val key get() = prefs.getString("CALLERKIT_API_KEY", "").orEmpty().trim()
    override fun isConfigured() = base.startsWith("https://") && phonePath.isNotBlank() && key.isNotBlank()

    override suspend fun lookupByPhone(phone: NormalizedPhone): List<UnifiedLookupResult> {
        if (!enabled || !isConfigured()) return emptyList()
        val path = base.trimEnd('/') + "/" + phonePath.trimStart('/')
        // CallerKit query parameter/auth shape can vary by current docs/dashboard. No endpoint is invented here.
        val url = URL(path + (if (path.contains('?')) "&" else "?") + "phone=${enc(phone.e164Number)}")
        val started = System.currentTimeMillis()
        val (code, body) = requestJson(url, mapOf("Authorization" to "Bearer $key"))
        if (code !in 200..299) return emptyList()
        val elapsed = System.currentTimeMillis() - started
        return parseCallerKit(body, phone, elapsed)
    }

    override suspend fun searchByName(name: String): List<UnifiedLookupResult> {
        if (!enabled || base.isBlank() || namePath.isBlank() || key.isBlank()) return emptyList()
        val path = base.trimEnd('/') + "/" + namePath.trimStart('/')
        val url = URL(path + (if (path.contains('?')) "&" else "?") + "name=${enc(name)}")
        val started = System.currentTimeMillis()
        val (code, body) = requestJson(url, mapOf("Authorization" to "Bearer $key"))
        if (code !in 200..299) return emptyList()
        val elapsed = System.currentTimeMillis() - started
        return parseGenericNameResults(body, displayName, elapsed)
    }

    private fun parseCallerKit(body: String, phone: NormalizedPhone, elapsed: Long): List<UnifiedLookupResult> {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = o.optJSONObject("data") ?: o.optJSONObject("result") ?: o
        val primary = firstString(data, "primary_name", "primaryName", "name", "full_name")
        val aliases = mutableListOf<String>()
        data.optJSONArray("aliases")?.let { a -> for (i in 0 until a.length()) aliases += a.optString(i) }
        return listOf(UnifiedLookupResult(
            phone.localNumber, phone.e164Number, primary.ifBlank { null }, aliases.filter(String::isNotBlank),
            carrier = firstString(data, "carrier"), country = firstString(data, "country").ifBlank { "LY" },
            spamScore = data.optDouble("spam_score").takeUnless(Double::isNaN),
            confidence = data.optDouble("confidence").takeUnless(Double::isNaN),
            source = displayName, responseTimeMs = elapsed, found = primary.isNotBlank()
        ))
    }

    override suspend fun healthCheck() = ProviderHealth(id, displayName, enabled, priority,
        when { !enabled -> ProviderState.DISABLED; !isConfigured() -> ProviderState.NEEDS_CONFIGURATION; else -> ProviderState.READY },
        if (isConfigured()) "جاهز" else "أدخل Base URL / paths / API key من لوحة الخدمة الرسمية")
}

class TrestleProvider(context: Context) : HttpLookupProvider(context) {
    override val id = "trestle"
    override val displayName = "Trestle Reverse Phone"
    override val priority get() = prefs.getInt("TRESTLE_PRIORITY", 200)
    override val enabled get() = prefs.getBoolean("TRESTLE_ENABLED", false)
    private val base get() = prefs.getString("TRESTLE_BASE_URL", "").orEmpty().trim()
    private val key get() = prefs.getString("TRESTLE_API_KEY", "").orEmpty().trim()
    override fun isConfigured() = base.startsWith("https://") && key.isNotBlank()

    override suspend fun lookupByPhone(phone: NormalizedPhone): List<UnifiedLookupResult> {
        if (!enabled || !isConfigured()) return emptyList()
        val root = base.trimEnd('/') + "/3.2/phone"
        val url = URL("$root?phone=${enc(phone.e164Number)}&phone.country_hint=LY")
        val started = System.currentTimeMillis()
        val (code, body) = requestJson(url, mapOf("x-api-key" to key))
        if (code !in 200..299) return emptyList()
        val elapsed = System.currentTimeMillis() - started
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val owners = o.optJSONArray("owners") ?: JSONArray()
        val names = mutableListOf<String>()
        for (i in 0 until owners.length()) {
            val owner = owners.optJSONObject(i) ?: continue
            firstString(owner, "name", "full_name").takeIf(String::isNotBlank)?.let(names::add)
            owner.optJSONArray("alternate_names")?.let { a -> for (j in 0 until a.length()) a.optString(j).takeIf(String::isNotBlank)?.let(names::add) }
        }
        val primary = names.firstOrNull()
        return listOf(UnifiedLookupResult(
            phone.localNumber, phone.e164Number, primary, names.drop(1).distinct(),
            carrier = firstString(o, "carrier"), country = firstString(o, "country_code", "country").ifBlank { "LY" },
            lineType = firstString(o, "line_type"), source = displayName, responseTimeMs = elapsed,
            isValid = if (o.has("is_valid")) o.optBoolean("is_valid") else null, found = !primary.isNullOrBlank(),
            metadata = mapOfNotNull(
                "is_prepaid" to o.opt("is_prepaid")?.toString(),
                "is_commercial" to o.opt("is_commercial")?.toString()
            )
        ))
    }

    override suspend fun healthCheck() = ProviderHealth(id, displayName, enabled, priority,
        when { !enabled -> ProviderState.DISABLED; !isConfigured() -> ProviderState.NEEDS_CONFIGURATION; else -> ProviderState.READY },
        if (isConfigured()) "جاهز — استخدم فقط إذا كانت الخطة/حالة الاستخدام تسمح دوليًا" else "يحتاج Base URL وAPI key")
}

class AbstractValidationProvider(context: Context) : HttpLookupProvider(context) {
    override val id = "abstract_validation"
    override val displayName = "Abstract Phone Validation"
    override val priority = 900
    override val enabled get() = prefs.getBoolean("ABSTRACT_ENABLED", false)
    private val base get() = prefs.getString("ABSTRACT_BASE_URL", "").orEmpty().trim()
    private val key get() = prefs.getString("ABSTRACT_API_KEY", "").orEmpty().trim()
    override fun isConfigured() = base.startsWith("https://") && key.isNotBlank()

    override suspend fun lookupByPhone(phone: NormalizedPhone): List<UnifiedLookupResult> {
        if (!enabled || !isConfigured()) return emptyList()
        val root = base.trimEnd('/') + "/v1/"
        val url = URL("$root?api_key=${enc(key)}&phone=${enc(phone.e164Number)}")
        val started = System.currentTimeMillis()
        val (code, body) = requestJson(url)
        if (code !in 200..299) return emptyList()
        val elapsed = System.currentTimeMillis() - started
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        return listOf(UnifiedLookupResult(
            phone.localNumber, phone.e164Number, primaryName = null,
            carrier = firstString(o, "carrier"), country = firstString(o, "country", "country_code").ifBlank { "LY" },
            lineType = firstString(o, "type", "line_type"), source = displayName, responseTimeMs = elapsed,
            isValid = if (o.has("valid")) o.optBoolean("valid") else null, found = false,
            metadata = mapOfNotNull(
                "international" to o.optString("format", "").takeIf(String::isNotBlank),
                "location" to o.optString("location", "").takeIf(String::isNotBlank)
            )
        ))
    }

    override suspend fun healthCheck() = ProviderHealth(id, displayName, enabled, priority,
        when { !enabled -> ProviderState.DISABLED; !isConfigured() -> ProviderState.NEEDS_CONFIGURATION; else -> ProviderState.READY },
        "تحقق من الرقم/الشركة/نوع الخط فقط — ليس مصدر أسماء")
}

class ProviderRegistry(private val context: Context) {
    fun all(): List<LookupProvider> {
        val existing = ConfigStore(context).load().map { ExistingProvider(it) }
        return buildList {
            add(LocalContactsProvider(context))
            addAll(existing)
            add(CallerKitProvider(context))
            add(TrestleProvider(context))
            add(AbstractValidationProvider(context))
        }.sortedBy { it.priority }
    }
}

class LookupRepository(private val context: Context) {
    private val registry = ProviderRegistry(context)
    private val cache = LookupCache(context)

    suspend fun lookupByPhone(input: String, forceRefresh: Boolean = false): LookupEnvelope {
        val phone = LibyanPhoneNormalizer.normalize(input)
        if (!phone.isValidFormat) return LookupEnvelope(emptyList(), health(), normalizedPhone = phone)
        if (!forceRefresh) cache.get(phone)?.let { cached ->
            return LookupEnvelope(listOf(cached.copy(source = "Local Cache", sourcesMatched = cached.sourcesMatched + "Local Cache")), health(), true, phone)
        }

        val providers = registry.all().filter { it.enabled && it.supportsPhoneLookup }
        val collected = mutableListOf<UnifiedLookupResult>()
        val health = mutableListOf<ProviderHealth>()

        for (provider in providers) {
            coroutineContext.ensureActive()
            if (!provider.isConfigured()) {
                health += ProviderHealth(provider.id, provider.displayName, provider.enabled, provider.priority, ProviderState.NEEDS_CONFIGURATION, "يحتاج إعداد")
                continue
            }
            val started = System.currentTimeMillis()
            try {
                val results = withTimeout(18_000) { provider.lookupByPhone(phone) }
                val elapsed = System.currentTimeMillis() - started
                collected += results
                health += ProviderHealth(provider.id, provider.displayName, true, provider.priority, ProviderState.READY,
                    if (results.any { it.found }) "وجد نتيجة" else "استجاب بدون اسم", elapsed, if (results.isNotEmpty()) 1 else 0)
                // Waterfall: local/existing sources run first. Once a reliable name is found, skip paid name APIs,
                // but validation providers may still enrich metadata.
                val reliableName = collected.filter { it.found }.groupingBy { it.primaryName?.trim()?.lowercase() }.eachCount().values.maxOrNull() ?: 0
                if (reliableName >= 2 && provider.priority < 900) break
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - started
                val state = if (e.message?.contains("429") == true) ProviderState.RATE_LIMITED else ProviderState.ERROR
                health += ProviderHealth(provider.id, provider.displayName, true, provider.priority, state, friendly(e), elapsed)
            }
        }

        // If a strong local/existing result stopped the loop before validation, optionally enrich with Abstract if configured.
        val abstract = providers.filterIsInstance<AbstractValidationProvider>().firstOrNull()
        if (abstract != null && abstract.isConfigured() && collected.none { it.source == abstract.displayName }) {
            runCatching { collected += abstract.lookupByPhone(phone) }
        }

        val merged = merge(collected, phone)
        merged.firstOrNull { it.found }?.let(cache::put)
        return LookupEnvelope(merged, health, false, phone)
    }

    suspend fun searchByName(name: String): LookupEnvelope {
        val q = name.trim()
        if (q.length < 2) return LookupEnvelope(emptyList(), health())
        val results = mutableListOf<UnifiedLookupResult>()
        val health = mutableListOf<ProviderHealth>()
        for (p in registry.all().filter { it.enabled && it.supportsNameSearch }) {
            coroutineContext.ensureActive()
            if (!p.isConfigured()) {
                health += ProviderHealth(p.id, p.displayName, p.enabled, p.priority, ProviderState.NEEDS_CONFIGURATION, "يحتاج إعداد")
                continue
            }
            val started = System.currentTimeMillis()
            try {
                val r = withTimeout(18_000) { p.searchByName(q) }
                results += r
                health += ProviderHealth(p.id, p.displayName, true, p.priority, ProviderState.READY, "${r.size} نتيجة", System.currentTimeMillis() - started)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                health += ProviderHealth(p.id, p.displayName, true, p.priority, ProviderState.ERROR, friendly(e), System.currentTimeMillis() - started)
            }
        }
        return LookupEnvelope(results.distinctBy { "${it.normalizedNumber}|${it.primaryName}|${it.source}" }, health)
    }

    suspend fun health(): List<ProviderHealth> = registry.all().map { p ->
        runCatching { p.healthCheck() }.getOrElse { ProviderHealth(p.id, p.displayName, p.enabled, p.priority, ProviderState.ERROR, friendly(it)) }
    }

    fun forceRefresh(phone: String) { cache.clearNumber(LibyanPhoneNormalizer.normalize(phone)) }

    private fun merge(raw: List<UnifiedLookupResult>, phone: NormalizedPhone): List<UnifiedLookupResult> {
        if (raw.isEmpty()) return emptyList()
        val nameRows = raw.filter { !it.primaryName.isNullOrBlank() }
        val grouped = nameRows.groupBy { normalizeName(it.primaryName.orEmpty()) }
        val ranked = grouped.map { (_, rows) ->
            val names = rows.mapNotNull { it.primaryName }.filter(String::isNotBlank)
            val sources = rows.map { it.source }.distinct()
            val avgConfidence = rows.mapNotNull { it.confidence }.average().takeUnless(Double::isNaN)
            val score = sources.size * 1000 + (avgConfidence?.times(100)?.toInt() ?: 0) + max(0, 500 - rows.minOf { providerPriority(it.source) })
            Triple(score, names.first(), rows)
        }.sortedByDescending { it.first }
        val bestRows = ranked.firstOrNull()?.third.orEmpty()
        val bestName = ranked.firstOrNull()?.second
        val aliases = buildList {
            ranked.drop(1).forEach { add(it.second) }
            raw.flatMap { it.aliases }.forEach { add(it) }
        }.filter { it.isNotBlank() && normalizeName(it) != normalizeName(bestName.orEmpty()) }.distinct()
        val enrichment = raw.firstOrNull { it.carrier != null || it.lineType != null || it.isValid != null }
        val sources = raw.map { it.source }.distinct()
        return listOf(UnifiedLookupResult(
            phone.localNumber, phone.e164Number, bestName, aliases,
            carrier = raw.mapNotNull { it.carrier }.firstOrNull(),
            country = raw.mapNotNull { it.country }.firstOrNull() ?: "LY",
            lineType = raw.mapNotNull { it.lineType }.firstOrNull(),
            spamScore = raw.mapNotNull { it.spamScore }.maxOrNull(),
            confidence = if (bestRows.isEmpty()) null else (bestRows.mapNotNull { it.confidence }.average().takeUnless(Double::isNaN)
                ?: (bestRows.map { it.source }.distinct().size.coerceAtMost(3) / 3.0)),
            source = bestRows.firstOrNull()?.source ?: enrichment?.source ?: sources.first(),
            sourcesMatched = sources,
            responseTimeMs = raw.mapNotNull { it.responseTimeMs }.maxOrNull(),
            isValid = raw.mapNotNull { it.isValid }.firstOrNull(),
            found = !bestName.isNullOrBlank(),
            metadata = raw.flatMap { it.metadata.entries }.associate { it.key to it.value }
        ))
    }

    private fun providerPriority(source: String): Int = registry.all().firstOrNull { it.displayName == source }?.priority ?: 500
    private fun normalizeName(v: String) = v.trim().lowercase().replace(Regex("\\s+"), " ")
}

private fun firstString(o: JSONObject, vararg keys: String): String {
    for (k in keys) {
        val v = o.optString(k, "").trim()
        if (v.isNotBlank() && v != "null") return v
    }
    return ""
}

private fun parseGenericNameResults(body: String, source: String, elapsed: Long): List<UnifiedLookupResult> {
    val root: Any = runCatching { JSONArray(body) }.getOrElse { runCatching { JSONObject(body) }.getOrNull() ?: return emptyList() }
    val arr = when (root) {
        is JSONArray -> root
        is JSONObject -> root.optJSONArray("results") ?: root.optJSONArray("data") ?: root.optJSONArray("items") ?: JSONArray().put(root)
        else -> JSONArray()
    }
    val out = mutableListOf<UnifiedLookupResult>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val number = firstString(o, "phone", "phone_number", "number", "mobile")
        val name = firstString(o, "name", "primary_name", "full_name", "display_name")
        if (name.isNotBlank() || number.isNotBlank()) {
            val norm = LibyanPhoneNormalizer.normalize(number)
            out += UnifiedLookupResult(number, norm.e164Number.ifBlank { number }, name.ifBlank { null }, source = source, responseTimeMs = elapsed, found = name.isNotBlank())
        }
    }
    return out
}

private fun friendly(e: Throwable): String = when {
    e is java.net.SocketTimeoutException || e.javaClass.simpleName.contains("Timeout", true) -> "انتهت مهلة المصدر"
    e is java.net.UnknownHostException -> "لا يوجد إنترنت أو تعذر الوصول للمصدر"
    else -> "خطأ مؤقت في المصدر"
}

private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (k, v) -> v?.takeIf(String::isNotBlank)?.let { k to it } }.toMap()

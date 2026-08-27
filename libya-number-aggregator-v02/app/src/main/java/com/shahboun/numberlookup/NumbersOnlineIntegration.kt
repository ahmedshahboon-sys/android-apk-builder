package com.shahboun.numberlookup

import android.content.Context
import org.json.JSONObject
import java.net.URL

class NumbersOnlineProvider(context: Context) : HttpLookupProvider(context) {
    override val id = "numbers_online"
    override val displayName = "Numbers Online"
    override val priority = 50
    override val enabled: Boolean get() = prefs.getBoolean("NUMBERS_ONLINE_ENABLED", true)
    override val supportsNameSearch = false

    private val key: String get() = prefs.getString("NUMBERS_ONLINE_API_KEY", "").orEmpty().trim()

    override fun isConfigured(): Boolean = key.isNotBlank()

    fun saveKey(value: String) {
        prefs.edit()
            .putString("NUMBERS_ONLINE_API_KEY", value.trim())
            .putBoolean("NUMBERS_ONLINE_ENABLED", value.isNotBlank())
            .apply()
    }

    fun maskedKey(): String {
        if (key.isBlank()) return ""
        return if (key.length <= 8) "••••••••" else key.take(4) + "••••••••" + key.takeLast(4)
    }

    override suspend fun lookupByPhone(phone: NormalizedPhone): List<UnifiedLookupResult> {
        if (!enabled || !isConfigured()) return emptyList()
        val started = System.currentTimeMillis()
        val url = URL("https://numbers.online/api/v1/lookup/${enc(phone.e164Number)}")
        val (code, body) = requestJson(url, mapOf("X-API-Key" to key))
        if (code == 429) throw RuntimeException("429")
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        val elapsed = System.currentTimeMillis() - started
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = root.optJSONObject("data") ?: root
        val cnam = firstNonBlank(data, "cnam", "caller_name", "name", "display_name")
        val number = firstNonBlank(data, "formatted", "phone", "number", "e164").ifBlank { phone.localNumber }
        val carrier = firstNonBlank(data, "carrier")
        val lineType = firstNonBlank(data, "line_type", "lineType")
        val country = firstNonBlank(data, "country", "country_code").ifBlank { "LY" }
        val spam = when {
            data.has("spam_score") && !data.isNull("spam_score") -> data.optDouble("spam_score").takeUnless(Double::isNaN)?.div(99.0)
            else -> null
        }
        val valid = when {
            data.has("valid") -> data.optBoolean("valid")
            data.has("is_valid") -> data.optBoolean("is_valid")
            else -> null
        }
        return listOf(
            UnifiedLookupResult(
                phoneNumber = number,
                normalizedNumber = phone.e164Number,
                primaryName = cnam.ifBlank { null },
                carrier = carrier.ifBlank { null },
                country = country,
                lineType = lineType.ifBlank { null },
                spamScore = spam,
                source = displayName,
                sourcesMatched = listOf(displayName),
                responseTimeMs = elapsed,
                isValid = valid,
                found = cnam.isNotBlank()
            )
        )
    }

    override suspend fun healthCheck(): ProviderHealth = ProviderHealth(
        id = id,
        displayName = displayName,
        enabled = enabled,
        priority = priority,
        state = when {
            !enabled -> ProviderState.DISABLED
            !isConfigured() -> ProviderState.NEEDS_CONFIGURATION
            else -> ProviderState.READY
        },
        lastMessage = when {
            !enabled -> "متوقف"
            !isConfigured() -> "أدخل مفتاح الخدمة مرة واحدة"
            else -> "مُعدّ للبحث"
        }
    )

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            val v = o.optString(key, "").trim()
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }
}

class LookupRepositoryV2(private val context: Context) {
    private val legacy = LookupRepository(context)
    private val numbers = NumbersOnlineProvider(context)

    suspend fun lookupByPhone(input: String, forceRefresh: Boolean = false): LookupEnvelope {
        val base = legacy.lookupByPhone(input, forceRefresh)
        val phone = base.normalizedPhone ?: LibyanPhoneNormalizer.normalize(input)
        if (!phone.isValidFormat) return base

        val alreadyHasName = base.results.any { it.found && !it.primaryName.isNullOrBlank() }
        val extraHealth = mutableListOf<ProviderHealth>()
        if (!numbers.enabled) {
            extraHealth += numbers.healthCheck()
            return base.copy(providerHealth = mergeHealth(base.providerHealth, extraHealth))
        }
        if (!numbers.isConfigured()) {
            extraHealth += numbers.healthCheck()
            return base.copy(providerHealth = mergeHealth(base.providerHealth, extraHealth))
        }
        if (alreadyHasName && !forceRefresh) {
            extraHealth += numbers.healthCheck().copy(lastMessage = "لم يُستخدم لأن اسمًا وُجد محليًا")
            return base.copy(providerHealth = mergeHealth(base.providerHealth, extraHealth))
        }

        val started = System.currentTimeMillis()
        return try {
            val online = numbers.lookupByPhone(phone)
            val elapsed = System.currentTimeMillis() - started
            extraHealth += ProviderHealth(
                numbers.id, numbers.displayName, true, numbers.priority, ProviderState.READY,
                if (online.any { it.found }) "وجد اسمًا" else "استجاب بدون اسم", elapsed,
                if (online.any { it.found }) 1 else 0
            )
            val merged = mergeSimple(base.results + online, phone)
            base.copy(results = merged, providerHealth = mergeHealth(base.providerHealth, extraHealth), fromCache = false)
        } catch (e: Exception) {
            val state = if (e.message?.contains("429") == true) ProviderState.RATE_LIMITED else ProviderState.ERROR
            extraHealth += ProviderHealth(numbers.id, numbers.displayName, true, numbers.priority, state,
                if (state == ProviderState.RATE_LIMITED) "تم بلوغ حد الطلبات" else "تعذر الاتصال مؤقتًا",
                System.currentTimeMillis() - started)
            base.copy(providerHealth = mergeHealth(base.providerHealth, extraHealth))
        }
    }

    suspend fun searchByName(name: String): LookupEnvelope = legacy.searchByName(name)

    suspend fun health(): List<ProviderHealth> = mergeHealth(legacy.health(), listOf(numbers.healthCheck()))

    fun forceRefresh(phone: String) = legacy.forceRefresh(phone)

    fun saveNumbersOnlineKey(value: String) = numbers.saveKey(value)
    fun numbersOnlineMaskedKey(): String = numbers.maskedKey()
    fun numbersOnlineConfigured(): Boolean = numbers.isConfigured()

    private fun mergeHealth(a: List<ProviderHealth>, b: List<ProviderHealth>): List<ProviderHealth> =
        (a + b).associateBy { it.id }.values.sortedBy { it.priority }

    private fun mergeSimple(raw: List<UnifiedLookupResult>, phone: NormalizedPhone): List<UnifiedLookupResult> {
        if (raw.isEmpty()) return emptyList()
        val withNames = raw.filter { !it.primaryName.isNullOrBlank() }
        val bestName = withNames.groupBy { normalizeName(it.primaryName.orEmpty()) }
            .maxByOrNull { it.value.map { row -> row.source }.distinct().size }
            ?.value?.firstOrNull()?.primaryName
        val aliases = withNames.mapNotNull { it.primaryName }
            .filter { normalizeName(it) != normalizeName(bestName.orEmpty()) }
            .distinct()
        return listOf(
            UnifiedLookupResult(
                phoneNumber = raw.firstOrNull { it.phoneNumber.isNotBlank() }?.phoneNumber ?: phone.localNumber,
                normalizedNumber = phone.e164Number,
                primaryName = bestName,
                aliases = aliases,
                carrier = raw.mapNotNull { it.carrier }.firstOrNull(),
                country = raw.mapNotNull { it.country }.firstOrNull() ?: "LY",
                lineType = raw.mapNotNull { it.lineType }.firstOrNull(),
                spamScore = raw.mapNotNull { it.spamScore }.maxOrNull(),
                confidence = if (bestName == null) null else (withNames.count { normalizeName(it.primaryName.orEmpty()) == normalizeName(bestName) }.coerceAtMost(3) / 3.0),
                source = withNames.firstOrNull()?.source ?: raw.first().source,
                sourcesMatched = raw.map { it.source }.distinct(),
                responseTimeMs = raw.mapNotNull { it.responseTimeMs }.maxOrNull(),
                isValid = raw.mapNotNull { it.isValid }.firstOrNull(),
                found = !bestName.isNullOrBlank(),
                metadata = raw.flatMap { it.metadata.entries }.associate { it.key to it.value }
            )
        )
    }

    private fun normalizeName(v: String) = v.trim().lowercase().replace(Regex("\\s+"), " ")
}

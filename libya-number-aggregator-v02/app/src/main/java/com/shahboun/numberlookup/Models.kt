package com.shahboun.numberlookup

data class SourceConfig(
    val id: Int,
    val name: String,
    val enabled: Boolean,
    val baseUrl: String,
    val phonePath: String,
    val namePath: String,
    val method: String = "GET",
    val queryParam: String = "q",
    val bearerToken: String = ""
)

data class LookupResult(val number: String, val name: String, val source: String)

data class SourceOutcome(
    val source: String,
    val ok: Boolean,
    val results: List<LookupResult> = emptyList(),
    val message: String = ""
)

data class MergedResult(
    val number: String,
    val names: List<String>,
    val sources: List<String>
) {
    val confidence: Int get() = when (sources.distinct().size) {
        0 -> 0
        1 -> 35
        2 -> 70
        3 -> 90
        else -> 100
    }
}

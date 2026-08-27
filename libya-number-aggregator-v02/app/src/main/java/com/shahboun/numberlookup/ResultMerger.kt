package com.shahboun.numberlookup

object ResultMerger {
    fun merge(items: List<LookupResult>): List<MergedResult> {
        return items.groupBy { normalize(it.number.ifBlank { it.name }) }.values.map { group ->
            MergedResult(
                number = group.firstNotNullOfOrNull { it.number.ifBlank { null } } ?: "—",
                names = group.map { it.name }.filter { it.isNotBlank() }.distinct(),
                sources = group.map { it.source }.distinct()
            )
        }.sortedWith(compareByDescending<MergedResult> { it.sources.size }.thenBy { it.number })
    }

    private fun normalize(v: String) = v.filter { it.isLetterOrDigit() }.lowercase()
}

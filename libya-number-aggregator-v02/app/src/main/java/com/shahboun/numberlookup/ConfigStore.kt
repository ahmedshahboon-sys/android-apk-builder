package com.shahboun.numberlookup

import android.content.Context

class ConfigStore(context: Context) {
    private val p = context.getSharedPreferences("sources", Context.MODE_PRIVATE)

    private val defaults = listOf(
        SourceConfig(1, "Libya Mobile Lookup (مرجع)", false,
            "https://app.libyamobilelookup.com", "/api/lookup", "/api/lookupall", "GET", "q"),
        SourceConfig(2, "Lookup 2.2.2 (المضيف غير محسوم)", false,
            "", "/API/search3.php", "/API/search_all2.php", "GET", "q"),
        SourceConfig(3, "WhoLY (مرجع)", false,
            "https://filess.site", "/ly/api/index.php?action=lookup", "/ly/api/index.php?action=search_name", "GET", "q"),
        SourceConfig(4, "المصدر 4", false, "", "/lookup", "/search-name"),
        SourceConfig(5, "المصدر 5", false, "", "/lookup", "/search-name")
    )

    fun load(): List<SourceConfig> = defaults.map { d ->
        SourceConfig(
            d.id,
            p.getString("name_${d.id}", d.name) ?: d.name,
            p.getBoolean("enabled_${d.id}", d.enabled),
            p.getString("url_${d.id}", d.baseUrl) ?: d.baseUrl,
            p.getString("phone_${d.id}", d.phonePath) ?: d.phonePath,
            p.getString("namepath_${d.id}", d.namePath) ?: d.namePath,
            p.getString("method_${d.id}", d.method) ?: d.method,
            p.getString("param_${d.id}", d.queryParam) ?: d.queryParam,
            p.getString("token_${d.id}", d.bearerToken) ?: d.bearerToken
        )
    }

    fun save(c: SourceConfig) = p.edit()
        .putString("name_${c.id}", c.name)
        .putBoolean("enabled_${c.id}", c.enabled)
        .putString("url_${c.id}", c.baseUrl.trim())
        .putString("phone_${c.id}", c.phonePath.trim())
        .putString("namepath_${c.id}", c.namePath.trim())
        .putString("method_${c.id}", c.method.uppercase())
        .putString("param_${c.id}", c.queryParam.trim())
        .putString("token_${c.id}", c.bearerToken.trim())
        .apply()
}

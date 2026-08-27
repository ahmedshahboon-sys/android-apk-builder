package com.shahboun.numberlookup

import android.content.Context
import android.provider.ContactsContract

class ContactLookup(private val context: Context) {
    fun search(query: String, byPhone: Boolean): List<LookupResult> {
        val out = mutableListOf<LookupResult>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            val nameCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val qName = query.trim().lowercase()
            val qPhone = canonicalPhone(query)
            while (c.moveToNext()) {
                val name = c.getString(nameCol).orEmpty().trim()
                val number = c.getString(numberCol).orEmpty().trim()
                val matches = if (byPhone) {
                    val n = canonicalPhone(number)
                    n.isNotBlank() && qPhone.isNotBlank() && (n == qPhone || n.takeLast(9) == qPhone.takeLast(9))
                } else {
                    name.lowercase().contains(qName)
                }
                if (matches) out += LookupResult(number = number, name = name, source = "جهات اتصال الهاتف")
            }
        }
        return out.distinctBy { canonicalPhone(it.number) + "|" + it.name.lowercase() }
    }

    private fun canonicalPhone(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("00218")) d = d.drop(2)
        if (d.startsWith("218") && d.length >= 12) d = "0" + d.drop(3)
        return d
    }
}

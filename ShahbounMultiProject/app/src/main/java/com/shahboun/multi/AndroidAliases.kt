package com.shahboun.multi

import android.content.Context

typealias ShortcutManager = android.content.pm.ShortcutManager

object ShortcutInfo {
    fun Builder(context: Context, id: String): android.content.pm.ShortcutInfo.Builder =
        android.content.pm.ShortcutInfo.Builder(context, id)
}

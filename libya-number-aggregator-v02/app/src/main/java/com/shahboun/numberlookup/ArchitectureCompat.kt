package com.shahboun.numberlookup

/** Small compatibility helper so prefix validation stays readable. */
internal fun String.startsWith(regex: Regex): Boolean = regex.find(this)?.range?.first == 0

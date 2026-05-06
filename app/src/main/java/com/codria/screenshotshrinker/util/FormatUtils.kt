package com.codria.screenshotshrinker.util

fun Long.toFileSizeString(): String = when {
    this >= 1_000_000 -> "%.1f MB".format(this / 1_000_000.0)
    this >= 1_000 -> "%.1f KB".format(this / 1_000.0)
    this > 0 -> "$this B"
    else -> ""
}

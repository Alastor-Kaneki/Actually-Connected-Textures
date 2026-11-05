package com.alastor.ctmforge.ui

import android.text.format.DateUtils
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun compactCount(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1e9)
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
    n >= 10_000 -> "${n / 1000}K"
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1e3)
    else -> n.toString()
}.replace(".0", "")

fun fileSize(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

fun isoMillis(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

/** "3 days ago" for recent dates, else a short date. */
fun relativeDate(millis: Long): String {
    val now = System.currentTimeMillis()
    if (now - millis in 0 until DateUtils.MINUTE_IN_MILLIS) return "just now"
    return if (now - millis < 30L * DateUtils.DAY_IN_MILLIS) {
        DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
    } else {
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

fun relativeDate(iso: String): String = isoMillis(iso)?.let { relativeDate(it) } ?: ""

/** "white_stained_glass_pane" -> "White stained glass pane". */
fun prettyBlock(id: String): String = id.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** "ctm-8x6-47-s" -> "Full CTM, 47 tiles". */
fun prettySpec(spec: String): String {
    val method = spec.substringBefore('-')
    val count = spec.split('-').getOrNull(2)
    val name = when (method) {
        "ctm" -> "Full CTM"
        "horizontal" -> "Horizontal"
        "vertical" -> "Vertical"
        "hv" -> "Horizontal + vertical"
        "vh" -> "Vertical + horizontal"
        "top" -> "Top"
        "random" -> "Random"
        "repeat" -> "Repeat"
        "fixed" -> "Fixed"
        else -> method
    }
    return if (count != null) "$name, $count tiles" else name
}

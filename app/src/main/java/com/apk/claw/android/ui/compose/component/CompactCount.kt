package com.apk.claw.android.ui.compose.component

import android.icu.text.CompactDecimalFormat
import java.util.Locale

/**
 * Like and view counts in the reader's language: 12 345 is "1.2万" in Chinese and Japanese, "1.2만" in Korean,
 * "12K" in English and "12 mil" in Spanish and Portuguese. It replaces the hand-rolled "1.2w", which is
 * Chinese internet shorthand and read as noise everywhere else.
 */
fun formatCompactCount(count: Int, locale: Locale = Locale.getDefault()): String =
    if (count < COMPACT_FROM) count.toString()
    else CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT).format(count.toLong())

/** Counts below this are shown in full. */
private const val COMPACT_FROM = 1000

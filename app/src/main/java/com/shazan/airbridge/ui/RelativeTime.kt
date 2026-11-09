package com.shazan.airbridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.shazan.airbridge.R
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * "Now", "10 sec ago" … "50 sec ago", "1 min ago", "5 min ago", "1 hr ago", "3 hr ago", then the date
 * ("9 Nov 2026"). Seconds go in steps of 10 so the label doesn't flicker every second.
 */
@Composable
fun relativeTime(at: Long, now: Long): String {
    if (at <= 0L) return ""
    val s = ((now - at) / 1000).coerceAtLeast(0).toInt()
    return when {
        s < 10 -> stringResource(R.string.time_now)
        s < 60 -> pluralStringResource(R.plurals.time_sec_ago, s / 10 * 10, s / 10 * 10)
        s < 3600 -> pluralStringResource(R.plurals.time_min_ago, s / 60, s / 60)
        s < 86_400 -> pluralStringResource(R.plurals.time_hr_ago, s / 3600, s / 3600)
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at)) // already in the phone's language
    }
}

/** The current time, updated every second while on screen, for [relativeTime] labels. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

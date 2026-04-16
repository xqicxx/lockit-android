package com.lockit.ui.components

import androidx.fragment.app.FragmentActivity
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Walk up the Context chain to find the current FragmentActivity.
 */
fun android.view.View.findActivity(): FragmentActivity? {
    var ctx = context
    while (ctx != null) {
        if (ctx is FragmentActivity) return ctx
        ctx = if (ctx is android.content.ContextWrapper) ctx.baseContext else null
    }
    return null
}

/**
 * Format an Instant as HH:mm:ss in the system default timezone.
 */
fun formatTime(instant: Instant): String {
    return DateTimeFormatter.ofPattern("HH:mm:ss").format(
        java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault()),
    )
}

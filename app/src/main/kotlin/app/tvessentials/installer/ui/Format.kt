package app.tvessentials.installer.ui

import java.util.Locale

/** Plain-language number formatting for sizes and times, free of platform dependencies. */
object Format {
    fun bytes(n: Long): String {
        if (n < 0) return "?"
        val mb = n / (1024.0 * 1024.0)
        return when {
            n < 1024 * 1024 -> String.format(Locale.US, "%d KB", maxOf(1, n / 1024))
            mb < 10 -> String.format(Locale.US, "%.1f MB", mb)
            mb < 1024 -> String.format(Locale.US, "%d MB", Math.round(mb))
            else -> String.format(Locale.US, "%.2f GB", mb / 1024)
        }
    }

    /** "32 MB / 47 MB" - both numbers in the same unit when possible so they scan easily. */
    fun progressPair(done: Long, total: Long?): String =
        if (total == null || total <= 0) bytes(done) else "${bytes(done)} / ${bytes(total)}"

    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"

    fun ago(nowMs: Long, thenMs: Long?): String {
        if (thenMs == null) return "Never"
        val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            s < 45 -> "Just now"
            s < 90 -> "1 minute ago"
            s < 45 * 60 -> "${s / 60} minutes ago"
            s < 90 * 60 -> "1 hour ago"
            s < 22 * 3600 -> "${s / 3600} hours ago"
            s < 36 * 3600 -> "Yesterday"
            else -> "${s / 86400} days ago"
        }
    }
}

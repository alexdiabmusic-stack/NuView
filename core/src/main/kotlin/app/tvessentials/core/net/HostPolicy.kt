package app.tvessentials.core.net

import okhttp3.HttpUrl

/**
 * Allow-list of hosts a download or metadata request may touch, including every redirect hop.
 *
 * Patterns are exact host names ("github.com") or a leading wildcard ("*.githubusercontent.com"),
 * which matches sub-domains but not the apex. Bare/overbroad wildcards and IP literals are rejected
 * when the policy is built, so a catalog typo cannot silently open the allow-list.
 */
class HostPolicy(patterns: List<String>, private val allowedPorts: Set<Int> = setOf(443)) {
    private val exact: Set<String>
    private val suffixes: List<String>

    init {
        val e = LinkedHashSet<String>()
        val s = ArrayList<String>()
        for (raw in patterns) {
            val p = raw.trim().lowercase()
            val problem = problemWith(p)
            require(problem == null) { "Invalid host pattern '$raw': $problem" }
            if (p.startsWith("*.")) s += p.substring(1) else e += p
        }
        exact = e
        suffixes = s
    }

    val patterns: List<String> get() = exact.toList() + suffixes.map { "*$it" }

    fun isAllowed(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.trim().lowercase().trimEnd('.')
        if (!h.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return false
        if (h in exact) return true
        return suffixes.any { h.endsWith(it) && h.length > it.length }
    }

    /** HTTPS only, an allowed port (443 in production), no embedded credentials, and an allowed host. */
    fun check(url: HttpUrl): Violation? = when {
        !url.isHttps -> Violation.NOT_HTTPS
        url.port !in allowedPorts -> Violation.UNEXPECTED_PORT
        url.username.isNotEmpty() || url.password.isNotEmpty() -> Violation.CREDENTIALS_IN_URL
        !isAllowed(url.host) -> Violation.HOST_NOT_ALLOWED
        else -> null
    }

    enum class Violation { NOT_HTTPS, UNEXPECTED_PORT, CREDENTIALS_IN_URL, HOST_NOT_ALLOWED }

    companion object {
        /** Returns a human-readable problem with [pattern] or null if it is acceptable. */
        fun problemWith(pattern: String): String? {
            if (pattern.isEmpty()) return "empty"
            if (pattern.contains("://") || pattern.contains('/') || pattern.contains(':') || pattern.contains(' ')) {
                return "must be a bare host name"
            }
            if (pattern.all { it.isDigit() || it == '.' }) return "IP addresses are not allowed"
            val body = if (pattern.startsWith("*.")) pattern.substring(2) else pattern
            if (body.contains('*')) return "wildcard only allowed as a leading '*.'"
            val labels = body.split('.')
            if (labels.any { it.isEmpty() || it.length > 63 }) return "malformed host name"
            if (!body.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return "invalid characters"
            if (labels.any { it.startsWith("-") || it.endsWith("-") }) return "labels cannot start or end with '-'"
            if (pattern.startsWith("*.") && labels.size < 2) return "wildcard too broad"
            if (labels.size < 2) return "needs at least two labels"
            return null
        }
    }
}

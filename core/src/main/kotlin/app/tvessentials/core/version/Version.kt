package app.tvessentials.core.version

/**
 * A comparable release version.
 *
 * Handles both SemVer ("1.1.0-beta.5") and plain dotted-decimal ("32.56", "3.7.7.2") schemes.
 * Segments are compared as numbers, never as text, so 32.56 > 32.7 and 1.10.0 > 1.9.0.
 * Anything that is not clearly a version parses to `null`; callers must treat that as "unknown"
 * instead of guessing an order.
 */
class Version private constructor(
    val raw: String,
    val numbers: List<Long>,
    val preRelease: List<String>,
) : Comparable<Version> {

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: Version): Int {
        val size = maxOf(numbers.size, other.numbers.size)
        for (i in 0 until size) {
            val c = (numbers.getOrElse(i) { 0L }).compareTo(other.numbers.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        // Same release numbers: a release outranks any of its pre-releases (SemVer rule 11).
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        val shared = minOf(preRelease.size, other.preRelease.size)
        for (i in 0 until shared) {
            val c = compareIdentifier(preRelease[i], other.preRelease[i])
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    /** True when both versions order identically (1.0 and 1.0.0 are equivalent). */
    fun isEquivalentTo(other: Version): Boolean = compareTo(other) == 0

    override fun equals(other: Any?): Boolean = other is Version && raw == other.raw
    override fun hashCode(): Int = raw.hashCode()
    override fun toString(): String = raw

    companion object {
        private const val MAX_SEGMENTS = 8
        private const val MAX_DIGITS = 12

        fun parseOrNull(input: String?): Version? {
            if (input == null) return null
            var s = input.trim()
            if (s.isEmpty() || s.length > 64) return null
            if (s[0] == 'v' || s[0] == 'V') s = s.substring(1)
            val plus = s.indexOf('+')
            if (plus >= 0) s = s.substring(0, plus) // build metadata never affects precedence
            val dash = s.indexOf('-')
            val core = if (dash >= 0) s.substring(0, dash) else s
            val pre = if (dash >= 0) s.substring(dash + 1) else ""
            if (dash >= 0 && pre.isEmpty()) return null

            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > MAX_SEGMENTS) return null
            val numbers = ArrayList<Long>(parts.size)
            for (p in parts) {
                if (p.isEmpty() || p.length > MAX_DIGITS || !p.all { it in '0'..'9' }) return null
                numbers += p.toLong()
            }

            val preIds = if (pre.isEmpty()) emptyList() else pre.split('.')
            for (id in preIds) {
                if (id.isEmpty() || !id.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '-' }) return null
            }
            return Version(input.trim(), numbers, preIds)
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val aNum = a.all { it in '0'..'9' }
            val bNum = b.all { it in '0'..'9' }
            return when {
                aNum && bNum -> {
                    val x = a.trimStart('0').ifEmpty { "0" }
                    val y = b.trimStart('0').ifEmpty { "0" }
                    if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
                }
                aNum -> -1 // numeric identifiers rank below alphanumeric ones
                bNum -> 1
                else -> a.compareTo(b)
            }
        }
    }
}

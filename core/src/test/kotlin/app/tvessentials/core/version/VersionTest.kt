package app.tvessentials.core.version

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
    private fun v(s: String) = assertNotNull(Version.parseOrNull(s), "should parse: $s")

    @Test fun `numeric segments compare as numbers, not text`() {
        assertTrue(v("32.56") > v("32.7"))         // text order would say the opposite
        assertTrue(v("1.10.0") > v("1.9.0"))
        assertTrue(v("10.0") > v("9.99"))
        assertTrue(v("3.7.7.2") > v("3.7.7"))
    }

    @Test fun `missing trailing segments count as zero`() {
        assertEquals(0, v("1.0").compareTo(v("1.0.0")))
        assertTrue(v("1.0").isEquivalentTo(v("1.0.0.0")))
    }

    @Test fun `a release outranks its own pre-releases`() {
        assertTrue(v("1.0.0") > v("1.0.0-beta.5"))
        assertTrue(v("1.1.0-beta.1") > v("1.0.0"))
        assertTrue(v("1.0.0-rc.1") > v("1.0.0-beta.9"))
    }

    @Test fun `pre-release identifiers follow semver precedence`() {
        assertTrue(v("1.1.0-beta.5") > v("1.1.0-beta.4"))
        assertTrue(v("1.1.0-beta.10") > v("1.1.0-beta.9"))   // numeric, not lexical
        assertTrue(v("1.0.0-alpha.1") < v("1.0.0-alpha.beta"))
        assertTrue(v("1.0.0-beta") < v("1.0.0-beta.2"))
    }

    @Test fun `leading v and build metadata are ignored`() {
        assertTrue(v("v1.2.3").isEquivalentTo(v("1.2.3")))
        assertTrue(v("1.2.3+build.5").isEquivalentTo(v("1.2.3+other")))
    }

    @Test fun `old beta tags that look like prereleases parse as prereleases`() {
        assertTrue(v("0.9.5-beta").isPreRelease)
        assertFalse(v("1.0.0").isPreRelease)
        assertTrue(v("1.0.0") > v("0.9.5-beta"))
    }

    @Test fun `things that are not versions are rejected instead of guessed`() {
        for (bad in listOf("", " ", "latest", "beta", "1..2", "1.2.x", "1.2.", ".1", "1.2.3-", "1.2.3-be ta", "v", "32.56s", "99999999999999.1", "1.2.3.4.5.6.7.8.9")) {
            assertNull(Version.parseOrNull(bad), "should reject '$bad'")
        }
        assertNull(Version.parseOrNull(null))
    }

    @Test fun `sorting a realistic mix yields the expected newest`() {
        val tags = listOf("0.9.5-beta", "1.0.0", "1.1.0-beta.5", "1.1.0-beta.2", "0.8.12-beta").map(::v)
        assertEquals("1.1.0-beta.5", tags.max().raw)
        assertEquals("1.0.0", tags.filter { !it.isPreRelease }.max().raw)
    }
}

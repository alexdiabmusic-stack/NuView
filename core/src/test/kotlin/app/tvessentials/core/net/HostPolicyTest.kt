package app.tvessentials.core.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostPolicyTest {
    private val policy = HostPolicy(listOf("github.com", "api.github.com", "*.githubusercontent.com"))

    @Test fun `exact and wildcard hosts match, case and trailing dot insensitive`() {
        assertTrue(policy.isAllowed("github.com"))
        assertTrue(policy.isAllowed("GitHub.com."))
        assertTrue(policy.isAllowed("objects.githubusercontent.com"))
        assertTrue(policy.isAllowed("release-assets.githubusercontent.com"))
        assertTrue(policy.isAllowed("a.b.githubusercontent.com"))
    }

    @Test fun `wildcards never match the apex or look-alikes`() {
        assertFalse(policy.isAllowed("githubusercontent.com"))
        assertFalse(policy.isAllowed("evilgithubusercontent.com"))
        assertFalse(policy.isAllowed("githubusercontent.com.evil.example"))
        assertFalse(policy.isAllowed("github.com.evil.example"))
        assertFalse(policy.isAllowed("raw.github.com"))
        assertFalse(policy.isAllowed("api.github.com@evil.example"))
        assertFalse(policy.isAllowed(""))
        assertFalse(policy.isAllowed(null))
    }

    @Test fun `overbroad or malformed patterns are rejected when the policy is built`() {
        for (bad in listOf("*", "*.com", "**.example.com", "ex*.example.com", "https://github.com", "github.com/x", "github.com:443", "10.0.0.1", "localhost", "", "a b.com", "-.com")) {
            assertFailsWith<IllegalArgumentException>("pattern '$bad'") { HostPolicy(listOf(bad)) }
        }
    }

    @Test fun `url check enforces https, port, credentials and host`() {
        assertNull(policy.check("https://github.com/a/b".toHttpUrl()))
        assertEquals(HostPolicy.Violation.NOT_HTTPS, policy.check("http://github.com/a".toHttpUrl()))
        assertEquals(HostPolicy.Violation.HOST_NOT_ALLOWED, policy.check("https://example.com/a".toHttpUrl()))
        assertEquals(HostPolicy.Violation.UNEXPECTED_PORT, policy.check("https://github.com:8443/a".toHttpUrl()))
        assertEquals(HostPolicy.Violation.CREDENTIALS_IN_URL, policy.check("https://user:pw@github.com/a".toHttpUrl()))
    }
}

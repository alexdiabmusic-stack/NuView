package app.tvessentials.core.net

import app.tvessentials.core.testing.TlsFixture
import app.tvessentials.core.testing.response
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SafeHttpTest {
    private val fx = TlsFixture()
    @AfterTest fun tearDown() = fx.close()

    private fun fail(block: suspend () -> Unit): NetFailure = assertFailsWith<NetFailure> { runBlocking { block() } }

    @Test fun `follows redirects inside the allow-list and records the chain without query strings`(): Unit = runBlocking {
        fx.dispatch { req ->
            when (req.url.encodedPath) {
                "/a" -> response(302, headers = mapOf("Location" to fx.url("objects.githubusercontent.com", "/b?token=secret")))
                "/b" -> response(200, "payload")
                else -> response(404)
            }
        }
        val policy = fx.policy("github.com", "*.githubusercontent.com")
        val (text, chain) = fx.http.fetch(HttpRequest(fx.url("github.com", "/a")), policy) { it.readText() to it.redirectChain }
        assertEquals("payload", text)
        assertEquals(2, chain.size)
        assertTrue(chain.none { it.contains("secret") || it.contains('?') })
    }

    @Test fun `a redirect to a host outside the list is refused before any request reaches it`() {
        var evilHits = 0
        fx.dispatch { req ->
            if (req.headers["Host"]?.startsWith("evil.example") == true) evilHits++
            if (req.url.encodedPath == "/a") response(302, headers = mapOf("Location" to fx.url("evil.example", "/steal"))) else response(200, "x")
        }
        val e = fail { fx.http.fetch(HttpRequest(fx.url("github.com", "/a")), fx.policy("github.com")) { } }
        assertEquals(NetFailure.Kind.BLOCKED_HOST, e.kind)
        assertEquals(0, evilHits)
    }

    @Test fun `a redirect downgrading to http is refused`() {
        fx.dispatch { response(302, headers = mapOf("Location" to "http://github.com/plain")) }
        assertEquals(NetFailure.Kind.INSECURE_REDIRECT, fail { fx.http.fetch(HttpRequest(fx.url("github.com", "/a")), fx.policy("github.com")) { } }.kind)
    }

    @Test fun `redirect loops are cut off`() {
        fx.dispatch { response(302, headers = mapOf("Location" to fx.url("github.com", "/a"))) }
        assertEquals(NetFailure.Kind.TOO_MANY_REDIRECTS, fail { fx.http.fetch(HttpRequest(fx.url("github.com", "/a")), fx.policy("github.com")) { } }.kind)
    }

    @Test fun `non-https and off-list start urls are refused up front`() {
        assertEquals(NetFailure.Kind.INSECURE_REDIRECT, fail { fx.http.fetch(HttpRequest("http://github.com/x"), fx.policy("github.com")) { } }.kind)
        assertEquals(NetFailure.Kind.BLOCKED_HOST, fail { fx.http.fetch(HttpRequest(fx.url("evil.example", "/x")), fx.policy("github.com")) { } }.kind)
        assertEquals(NetFailure.Kind.MALFORMED, fail { fx.http.fetch(HttpRequest("not a url"), fx.policy("github.com")) { } }.kind)
    }

    @Test fun `credentials can never be attached`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { fx.http.fetch(HttpRequest(fx.url("github.com", "/x"), headers = mapOf("Authorization" to "Bearer x")), fx.policy("github.com")) { } }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { fx.http.fetch(HttpRequest(fx.url("github.com", "/x"), headers = mapOf("cookie" to "a=b")), fx.policy("github.com")) { } }
        }
    }

    @Test fun `a cloudflare browser check is reported, not bypassed`() {
        fx.dispatch { response(403, "<title>Just a moment...</title>", mapOf("cf-mitigated" to "challenge", "Server" to "cloudflare")) }
        assertEquals(NetFailure.Kind.BROWSER_CHECK, fail { fx.http.fetch(HttpRequest(fx.url("implayer.tv", "/d")), fx.policy("implayer.tv")) { } }.kind)
    }

    @Test fun `rate limits, missing files and server errors are told apart`() {
        fx.dispatch { response(403, "{}", mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1900000000")) }
        val rl = fail { fx.http.fetch(HttpRequest(fx.url("github.com", "/x")), fx.policy("github.com")) { } }
        assertEquals(NetFailure.Kind.RATE_LIMITED, rl.kind)
        assertEquals(1_900_000_000_000L, rl.retryAtMs)
        fx.dispatch { response(404) }
        assertEquals(NetFailure.Kind.NOT_FOUND, fail { fx.http.fetch(HttpRequest(fx.url("github.com", "/x")), fx.policy("github.com")) { } }.kind)
        fx.dispatch { response(503) }
        assertEquals(NetFailure.Kind.HTTP_ERROR, fail { fx.http.fetch(HttpRequest(fx.url("github.com", "/x")), fx.policy("github.com")) { } }.kind)
        fx.dispatch { response(429, headers = mapOf("Retry-After" to "30")) }
        assertEquals(NetFailure.Kind.RATE_LIMITED, fail { fx.http.fetch(HttpRequest(fx.url("github.com", "/x")), fx.policy("github.com")) { } }.kind)
    }

    @Test fun `an unreachable server is reported as offline`() {
        val url = fx.url("github.com", "/x")
        val policy = fx.policy("github.com")
        fx.close()
        val kind = fail { fx.http.fetch(HttpRequest(url), policy) { } }.kind
        assertTrue(kind == NetFailure.Kind.OFFLINE || kind == NetFailure.Kind.TIMEOUT, "was $kind")
    }

    @Test fun `oversized bodies are cut off instead of buffered`() {
        fx.dispatch { response(200, "x".repeat(10_000)) }
        val e = fail { fx.http.fetch(HttpRequest(fx.url("github.com", "/x")), fx.policy("github.com")) { it.readBytes(1000) } }
        assertEquals(NetFailure.Kind.RESPONSE_TOO_LARGE, e.kind)
    }

    @Test fun `cancelling the caller aborts a slow transfer promptly`() = runBlocking {
        fx.dispatch { MockResponse.Builder().code(200).body("y".repeat(200_000)).throttleBody(1_000, 100, TimeUnit.MILLISECONDS).build() }
        val started = System.nanoTime()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            fx.http.fetch(HttpRequest(fx.url("github.com", "/slow")), fx.policy("github.com")) { it.readBytes(1_000_000) }
        }
        delay(300)
        withTimeout(3_000) { job.cancelAndJoin() }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
        // The aborted call must not poison later requests.
        fx.dispatch { response(200, "ok") }
        assertEquals("ok", fx.http.fetch(HttpRequest(fx.url("github.com", "/ok")), fx.policy("github.com")) { it.readText() })
    }
}

package app.tvessentials.core.trust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrustEvaluatorTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private fun policy(trusted: List<String> = listOf(a), revoked: List<String> = emptyList(), lineage: Boolean = false) =
        TrustPolicy(trusted.map { TrustedSigner(it, "k-$it") }, revoked.map { RevokedSigner(it, "compromised") }, lineage)

    @Test fun `a pinned signer is trusted`() {
        assertIs<TrustVerdict.Trusted>(TrustEvaluator.evaluate(policy(), setOf(a)))
    }

    @Test fun `colon separated and upper case digests are normalised`() {
        val colon = a.uppercase().chunked(2).joinToString(":")
        assertIs<TrustVerdict.Trusted>(TrustEvaluator.evaluate(policy(), setOf(colon)))
    }

    @Test fun `an unknown signer fails closed`() {
        assertIs<TrustVerdict.UntrustedSigner>(TrustEvaluator.evaluate(policy(), setOf(b)))
    }

    @Test fun `an empty policy trusts nothing`() {
        assertEquals(TrustVerdict.PolicyNotConfigured, TrustEvaluator.evaluate(TrustPolicy(), setOf(a)))
    }

    @Test fun `no signer means unsigned`() {
        assertEquals(TrustVerdict.Unsigned, TrustEvaluator.evaluate(policy(), emptySet()))
        assertEquals(TrustVerdict.Unsigned, TrustEvaluator.evaluate(policy(), setOf("not-hex")))
    }

    @Test fun `every signer of a multi-signer package must be trusted`() {
        assertIs<TrustVerdict.UntrustedSigner>(TrustEvaluator.evaluate(policy(), setOf(a, b)))
        assertIs<TrustVerdict.Trusted>(TrustEvaluator.evaluate(policy(listOf(a, b)), setOf(a, b)))
    }

    @Test fun `a revoked certificate is rejected even if also listed as trusted or only in the lineage`() {
        assertIs<TrustVerdict.Revoked>(TrustEvaluator.evaluate(policy(listOf(a), listOf(a)), setOf(a)))
        assertIs<TrustVerdict.Revoked>(TrustEvaluator.evaluate(policy(listOf(a), listOf(c), lineage = true), setOf(a), lineage = setOf(c, a)))
    }

    @Test fun `deliberate rotation works by adding the new key while the old stays trusted`() {
        val during = policy(listOf(a, b))
        assertIs<TrustVerdict.Trusted>(TrustEvaluator.evaluate(during, setOf(a)))
        assertIs<TrustVerdict.Trusted>(TrustEvaluator.evaluate(during, setOf(b)))
        val after = policy(listOf(b), revoked = listOf(a))
        assertIs<TrustVerdict.Revoked>(TrustEvaluator.evaluate(after, setOf(a)))
        assertIs<TrustVerdict.Trusted>(TrustEvaluator.evaluate(after, setOf(b)))
    }

    @Test fun `lineage rotation is only honoured when the policy opts in`() {
        assertIs<TrustVerdict.UntrustedSigner>(TrustEvaluator.evaluate(policy(lineage = false), setOf(b), lineage = setOf(a, b)))
        assertIs<TrustVerdict.Trusted>(TrustEvaluator.evaluate(policy(lineage = true), setOf(b), lineage = setOf(a, b)))
        assertIs<TrustVerdict.UntrustedSigner>(TrustEvaluator.evaluate(policy(lineage = true), setOf(c), lineage = setOf(b, c)))
    }

    @Test fun `signing identity continuity mirrors the platform rule`() {
        assertTrue(TrustEvaluator.isSameSigningIdentity(setOf(a), setOf(a), emptySet()))
        assertFalse(TrustEvaluator.isSameSigningIdentity(setOf(a), setOf(b), emptySet()))
        assertTrue(TrustEvaluator.isSameSigningIdentity(setOf(a), setOf(b), setOf(a, b)))
        assertFalse(TrustEvaluator.isSameSigningIdentity(emptySet(), setOf(a), emptySet()))
    }
}

package app.tvessentials.core.trust

import app.tvessentials.core.util.Hex
import kotlinx.serialization.Serializable

/** A signing certificate the maintainers have reviewed and chosen to trust for one app. */
@Serializable
data class TrustedSigner(
    /** SHA-256 of the DER-encoded certificate, lower-case hex. */
    val sha256: String,
    val label: String,
    /** ISO date this certificate was added to the policy. */
    val since: String? = null,
    val note: String? = null,
)

/** A certificate that must be rejected even if it is also listed as trusted. */
@Serializable
data class RevokedSigner(val sha256: String, val reason: String)

/**
 * The reviewed signing policy for one app. Evaluation is fail-closed: an empty policy trusts nothing.
 *
 * Rotation is a deliberate, reviewed act: add the new certificate to [signers] (keeping the old one
 * while both are in circulation), then later move the old one to [revoked]. [allowLineageRotation]
 * additionally accepts an APK whose Android signing-lineage proves rotation from an already-trusted
 * certificate; it defaults to off and must stay off for any publisher whose old key may be exposed.
 */
@Serializable
data class TrustPolicy(
    val signers: List<TrustedSigner> = emptyList(),
    val revoked: List<RevokedSigner> = emptyList(),
    val allowLineageRotation: Boolean = false,
    /** Free-form record of how and when the pins were reviewed. Shown in docs, never used for decisions. */
    val review: String = "",
)

sealed interface TrustVerdict {
    data class Trusted(val signer: TrustedSigner) : TrustVerdict
    data class Revoked(val reason: String) : TrustVerdict
    data class UntrustedSigner(val digests: Set<String>) : TrustVerdict

    /** The APK carries no valid signature, or the platform could not read one. */
    data object Unsigned : TrustVerdict

    /** The catalog lists no reviewed certificate for this app yet, so nothing can be verified. */
    data object PolicyNotConfigured : TrustVerdict
}

object TrustEvaluator {

    /**
     * @param signers SHA-256 digests of the certificates that signed the package contents.
     * @param lineage digests of the signing-lineage history (older certificates first), if the package has one.
     */
    fun evaluate(policy: TrustPolicy, signers: Set<String>, lineage: Set<String> = emptySet()): TrustVerdict {
        val current = signers.mapNotNull { Hex.normalizeDigest(it) }.toSet()
        if (current.isEmpty()) return TrustVerdict.Unsigned
        val history = lineage.mapNotNull { Hex.normalizeDigest(it) }.toSet()

        for (r in policy.revoked) {
            val d = Hex.normalizeDigest(r.sha256) ?: continue
            if (d in current || d in history) return TrustVerdict.Revoked(r.reason)
        }
        if (policy.signers.isEmpty()) return TrustVerdict.PolicyNotConfigured

        val trusted = policy.signers.mapNotNull { s -> Hex.normalizeDigest(s.sha256)?.let { it to s } }.toMap()
        if (current.all { it in trusted }) {
            return TrustVerdict.Trusted(trusted.getValue(current.first()))
        }
        if (policy.allowLineageRotation) {
            val endorsed = history.firstOrNull { it in trusted }
            if (endorsed != null) return TrustVerdict.Trusted(trusted.getValue(endorsed))
        }
        return TrustVerdict.UntrustedSigner(current)
    }

    /**
     * Whether Android would accept [candidate] as an update of an installed package signed by [installed].
     * This mirrors the platform rule closely enough to explain a failure before attempting it: same
     * signer, or the candidate's lineage vouches for the installed certificate.
     */
    fun isSameSigningIdentity(installed: Set<String>, candidate: Set<String>, candidateLineage: Set<String>): Boolean {
        val i = installed.mapNotNull { Hex.normalizeDigest(it) }.toSet()
        val c = candidate.mapNotNull { Hex.normalizeDigest(it) }.toSet()
        val l = candidateLineage.mapNotNull { Hex.normalizeDigest(it) }.toSet()
        if (i.isEmpty() || c.isEmpty()) return false
        return i == c || i.any { it in l }
    }
}

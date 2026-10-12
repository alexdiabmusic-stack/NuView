package app.tvessentials.core.catalog

import app.tvessentials.core.net.HostPolicy
import app.tvessentials.core.util.Hex
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.InputStream

class CatalogException(message: String) : IllegalStateException(message)

object CatalogParser {
    val json: Json = Json {
        ignoreUnknownKeys = false // a typo in a security-relevant field must not be silently ignored
        classDiscriminator = "type"
        isLenient = false
    }

    fun parse(text: String): Catalog {
        val catalog = try {
            json.decodeFromString(Catalog.serializer(), text)
        } catch (e: Exception) {
            throw CatalogException("Catalog is not valid: ${e.message}")
        }
        val problems = CatalogValidator.validate(catalog)
        if (problems.isNotEmpty()) throw CatalogException("Catalog failed validation:\n - " + problems.joinToString("\n - "))
        return catalog
    }

    fun parse(stream: InputStream): Catalog = parse(stream.readBytes().toString(Charsets.UTF_8))
}

/** Structural and security validation applied to the catalog at load time and in unit tests. */
object CatalogValidator {
    private val idRegex = Regex("^[a-z][a-z0-9-]{1,40}$")
    private val packageRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val colorRegex = Regex("^#[0-9a-fA-F]{6}$")
    private val ownerRepoRegex = Regex("^[A-Za-z0-9_.-]{1,100}$")

    fun validate(catalog: Catalog): List<String> {
        val problems = ArrayList<String>()
        if (catalog.schemaVersion != 1) problems += "Unsupported schemaVersion ${catalog.schemaVersion}"
        if (catalog.apps.isEmpty()) problems += "Catalog has no apps"
        val ids = HashSet<String>()
        val allPackages = HashMap<String, String>()
        for (app in catalog.apps) {
            val at = "[${app.id}]"
            if (!idRegex.matches(app.id)) problems += "$at invalid id"
            if (!ids.add(app.id)) problems += "$at duplicate id"
            if (app.displayName.isBlank() || app.tagline.isBlank() || app.description.isBlank()) problems += "$at missing text"
            if (!colorRegex.matches(app.accentColor)) problems += "$at invalid accentColor"
            if (app.minAndroidSdk !in 16..40) problems += "$at implausible minAndroidSdk"
            if (app.channels[Channel.STABLE] == null) problems += "$at must define a stable channel"
            for ((channel, spec) in app.channels) {
                if (!packageRegex.matches(spec.packageId)) problems += "$at invalid package id for $channel"
                val owner = allPackages.put(spec.packageId, app.id)
                if (owner != null && owner != app.id) problems += "$at package ${spec.packageId} also claimed by $owner"
            }

            // Hosts
            val policy = try {
                HostPolicy(app.allowedHosts)
            } catch (e: IllegalArgumentException) {
                problems += "$at ${e.message}"
                null
            }
            if (app.allowedHosts.isEmpty()) problems += "$at allowedHosts is empty"
            requireHttps(app.websiteUrl, "$at websiteUrl", problems)
            app.guided?.let { requireHttps(it.officialUrl, "$at guided.officialUrl", problems) }

            // Source
            when (val s = app.source) {
                is GitHubReleasesSource -> {
                    if (!ownerRepoRegex.matches(s.owner) || !ownerRepoRegex.matches(s.repo)) problems += "$at invalid owner/repo"
                    if (policy != null) {
                        for (h in listOf("api.github.com", "github.com")) {
                            if (!policy.isAllowed(h)) problems += "$at allowedHosts must include $h for a GitHub source"
                        }
                    }
                    for (channel in app.channels.keys) {
                        val rule = s.channelRules[channel]
                        if (rule == null) {
                            problems += "$at no channelRules for $channel"
                            continue
                        }
                        for (p in listOfNotNull(rule.tagPattern, rule.tagVersionPattern, rule.assetNamePattern) + rule.excludeAssetPatterns) {
                            if (runCatching { Regex(p) }.isFailure) problems += "$at invalid regex '$p'"
                        }
                    }
                    for (a in s.universalAbis) if (a !in KNOWN_ABIS) problems += "$at unknown ABI '$a'"
                }
                is DirectApkSource -> {
                    val url = requireHttps(s.url, "$at source.url", problems)
                    if (url != null && policy != null && !policy.isAllowed(url.host)) problems += "$at source host ${url.host} is not in allowedHosts"
                    s.fileNameVersionPattern?.let { if (runCatching { Regex(it) }.isFailure) problems += "$at invalid regex '$it'" }
                }
                is SignedManifestSource -> {
                    for ((label, u) in listOf("manifestUrl" to s.manifestUrl, "signatureUrl" to s.signatureUrl)) {
                        val url = requireHttps(u, "$at $label", problems)
                        if (url != null && policy != null && !policy.isAllowed(url.host)) problems += "$at $label host ${url.host} is not in allowedHosts"
                    }
                    if (s.publicKeyBase64.isBlank()) problems += "$at publicKeyBase64 required"
                }
            }

            // Trust
            for (t in app.trust.signers) {
                if (Hex.normalizeDigest(t.sha256) == null) problems += "$at trusted signer '${t.label}' has an invalid SHA-256"
                else if (t.sha256 != t.sha256.lowercase()) problems += "$at trusted signer digest must be lower-case hex"
            }
            for (r in app.trust.revoked) if (Hex.normalizeDigest(r.sha256) == null) problems += "$at revoked signer has an invalid SHA-256"
            val trusted = app.trust.signers.mapNotNull { Hex.normalizeDigest(it.sha256) }.toSet()
            val revoked = app.trust.revoked.mapNotNull { Hex.normalizeDigest(it.sha256) }.toSet()
            if (trusted.intersect(revoked).isNotEmpty()) problems += "$at a signer is both trusted and revoked"
        }
        return problems
    }

    private fun requireHttps(raw: String, label: String, problems: MutableList<String>) =
        raw.toHttpUrlOrNull().also {
            if (it == null || !it.isHttps) problems += "$label must be a valid https URL"
        }

    val KNOWN_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
}

package app.tvessentials.core.testing

import app.tvessentials.core.catalog.AppDefinition
import app.tvessentials.core.catalog.Catalog
import app.tvessentials.core.catalog.CatalogParser

object Fixtures {
    val catalog: Catalog by lazy {
        val text = checkNotNull(Fixtures::class.java.classLoader.getResourceAsStream("catalog/catalog.json")).readBytes().toString(Charsets.UTF_8)
        CatalogParser.parse(text)
    }
    fun app(id: String): AppDefinition = catalog.apps.first { it.id == id }

    /** The same app as shipped, but with no reviewed signing certificate: exercises the guided-only path that must keep working for future catalog entries. */
    fun unreviewed(id: String): AppDefinition = app(id).let { it.copy(trust = it.trust.copy(signers = emptyList())) }
    fun catalogWithUnreviewed(id: String): Catalog = catalog.copy(apps = catalog.apps.map { if (it.id == id) unreviewed(id) else it })

    private const val HEX = "0123456789abcdef"
    fun fakeSha(seed: Int): String = buildString { repeat(64) { append(HEX[(seed * 7 + it * 3) % 16]) } }

    fun asset(owner: String, repo: String, tag: String, name: String, size: Long, sha: String? = null, state: String = "uploaded", host: String = "https://github.com"): String =
        """{"name":"$name","size":$size,"state":"$state","browser_download_url":"$host/$owner/$repo/releases/download/$tag/$name"${if (sha != null) ""","digest":"sha256:$sha"""" else ""}}"""

    fun release(owner: String, repo: String, tag: String, prerelease: Boolean = false, draft: Boolean = false, assets: List<String>, published: String = "2026-09-19T11:38:15Z"): String =
        """{"tag_name":"$tag","name":"$tag","draft":$draft,"prerelease":$prerelease,"published_at":"$published","html_url":"https://github.com/$owner/$repo/releases/tag/$tag","assets":[${assets.joinToString(",")}]}"""

    /** NuvioMedia/NuvioTV feed shaped like the real one, including old "-beta" tags flagged as full releases. */
    fun nuvioFeed(extra: List<String> = emptyList()): String {
        fun set(tag: String, size: Long) = listOf("arm64-v8a" to size, "armeabi-v7a" to size - 1_000_000, "universal" to size * 3, "x86" to size + 1_000_000, "x86_64" to size + 1_500_000)
            .map { (abi, sz) -> asset("NuvioMedia", "NuvioTV", tag, "app-full-$abi-release.apk", sz, fakeSha(abi.length + tag.length)) }
        return "[" + (extra + listOf(
            release("NuvioMedia", "NuvioTV", "1.1.0-beta.5", prerelease = true, assets = set("1.1.0-beta.5", 54_278_718), published = "2026-10-07T23:10:42Z"),
            release("NuvioMedia", "NuvioTV", "1.1.0-beta.4", prerelease = true, assets = set("1.1.0-beta.4", 54_000_000)),
            release("NuvioMedia", "NuvioTV", "1.0.0", assets = set("1.0.0", 73_746_627)),
            release("NuvioMedia", "NuvioTV", "0.9.5-beta", prerelease = false, assets = set("0.9.5-beta", 70_000_000)),
        )).joinToString(",") + "]"
    }

    /** yuliskov/SmartTube feed: stable = "32.56s", beta = "32.56", plus the rolling "latest" and an asset-less notice. */
    fun smartTubeFeed(): String {
        fun set(flavor: String, ver: String) = listOf("arm64-v8a", "armeabi-v7a", "universal", "x86")
            .map { asset("yuliskov", "SmartTube", if (flavor == "stable") "${ver}s" else ver, "SmartTube_${flavor}_${ver}_$it.apk", 25_000_000, fakeSha(it.length + ver.length)) }
        return "[" + listOf(
            release("yuliskov", "SmartTube", "32.56s", assets = set("stable", "32.56")),
            release("yuliskov", "SmartTube", "32.56", prerelease = true, assets = set("beta", "32.56")),
            release("yuliskov", "SmartTube", "32.54", prerelease = true, assets = set("beta", "32.54")),
            release("yuliskov", "SmartTube", "notification2", assets = emptyList()),
            release("yuliskov", "SmartTube", "latest", assets = listOf(
                asset("yuliskov", "SmartTube", "latest", "smarttube_stable.apk", 34_922_752),
                asset("yuliskov", "SmartTube", "latest", "smarttube_beta.apk", 34_948_291),
            )),
            release("yuliskov", "SmartTube", "32.47s", assets = set("stable", "32.47")),
        ).joinToString(",") + "]"
    }
}

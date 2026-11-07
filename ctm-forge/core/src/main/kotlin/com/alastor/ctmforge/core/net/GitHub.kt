package com.alastor.ctmforge.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** GitHub's REST API for release lookups (no token: 60 requests an hour per IP address). */
object GitHub {
    const val API = "https://api.github.com"

    class Asset(
        val name: String,
        val url: String,
        val size: Long,
        /** Lowercase hex SHA-256 from the asset's `digest`, when GitHub reports one. */
        val sha256: String?,
    )

    class Release(
        val tag: String,
        val name: String,
        val pageUrl: String,
        val publishedAt: String,
        val notes: String,
        val prerelease: Boolean,
        val assets: List<Asset>,
    )

    private val headers = mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")

    /** The newest release that isn't a draft or a pre-release. */
    fun latestRelease(repo: String): Release = parseRelease(Http.json("$API/repos/$repo/releases/latest", headers))

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun parseRelease(e: JsonElement): Release {
        val o = e.jsonObject
        val tag = o.str("tag_name") ?: throw IllegalArgumentException("Not a GitHub release")
        return Release(
            tag = tag,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: tag,
            pageUrl = o.str("html_url") ?: "",
            publishedAt = o.str("published_at") ?: "",
            notes = o.str("body") ?: "",
            prerelease = (o["prerelease"] as? JsonPrimitive)?.booleanOrNull == true,
            assets = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull { a ->
                val name = a.str("name") ?: return@mapNotNull null
                val url = a.str("browser_download_url") ?: return@mapNotNull null
                Asset(
                    name = name,
                    url = url,
                    size = (a["size"] as? JsonPrimitive)?.longOrNull ?: -1L,
                    sha256 = a.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
                )
            } ?: emptyList(),
        )
    }
}

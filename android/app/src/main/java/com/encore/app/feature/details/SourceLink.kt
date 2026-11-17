package com.encore.app.feature.details

import java.net.URI

enum class SourceKind { WIKIPEDIA, KAGGLE, OTHER }

/**
 * A movie's attribution link (D10, data/SOURCE-ATTRIBUTION.md). Catalog URLs are untrusted input:
 * only absolute http(s) URLs with a host are opened, everything else is dropped.
 */
data class SourceLink(val url: String, val kind: SourceKind) {
    companion object {
        fun from(raw: String?): SourceLink? {
            val url = raw?.trim().orEmpty()
            if (url.isEmpty()) return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if ((scheme != "https" && scheme != "http") || host.isNullOrEmpty()) return null
            val kind = when {
                host == "wikipedia.org" || host.endsWith(".wikipedia.org") -> SourceKind.WIKIPEDIA
                host == "kaggle.com" || host.endsWith(".kaggle.com") -> SourceKind.KAGGLE
                else -> SourceKind.OTHER
            }
            return SourceLink(url, kind)
        }

        /** The record's `sourceUrl` first, then `datasetSourceUrl` when it is a different link. */
        fun forMovie(sourceUrl: String?, datasetSourceUrl: String?): List<SourceLink> =
            listOfNotNull(from(sourceUrl), from(datasetSourceUrl)).distinctBy { it.url }
    }
}

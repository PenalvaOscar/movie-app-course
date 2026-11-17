package com.encore.app.core.data.catalog

import android.content.res.AssetManager
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/** The catalog copied into the APK at build time by the `copyCatalog` Gradle task, if any. */
class AssetCatalogSource(
    private val assets: AssetManager,
    private val path: String = "catalog/movies.json",
) : CatalogSource {
    override val origin = CatalogOrigin.ASSET

    override fun open(): SourceStream? {
        val input = try {
            assets.open(path)
        } catch (_: FileNotFoundException) {
            return null
        }
        // For an asset, available() is the full remaining length, compressed or not.
        return SourceStream(input, input.available().toLong().takeIf { it > 0 })
    }
}

/** One GET of the normalized catalog (the local `api/` server in debug, the public API in release). */
class DownloadCatalogSource(private val url: String) : CatalogSource {
    override val origin = CatalogOrigin.DOWNLOAD

    override fun open(): SourceStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        val status = connection.responseCode
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw HttpStatusException(status)
        }
        return SourceStream(connection.inputStream, connection.contentLengthLong.takeIf { it > 0 })
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

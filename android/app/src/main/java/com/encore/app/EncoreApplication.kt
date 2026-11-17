package com.encore.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient

class EncoreApplication : Application(), SingletonImageLoader.Factory {
    val container: AppContainer by lazy { AppContainer(this) }

    /**
     * Posters come from the catalog's own URLs (TMDB and Wikimedia), never fabricated (D10).
     * The disk cache lives in cacheDir, which Auto Backup never includes (O5), and lets posters
     * already seen show offline; anything else falls back to the procedural poster.
     * Wikimedia asks clients to send a descriptive User-Agent; it carries no user data.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("poster_cache"))
                    .maxSizeBytes(POSTER_CACHE_BYTES)
                    .build()
            }
            .build()
    }

    private companion object {
        const val USER_AGENT = "Encore/${BuildConfig.VERSION_NAME} (Android movie discovery app)"
        const val POSTER_CACHE_BYTES = 150L * 1024 * 1024
    }
}

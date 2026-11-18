package com.encore.app

import android.content.Context
import android.util.Log
import com.encore.app.core.data.catalog.AssetCatalogSource
import com.encore.app.core.data.catalog.CatalogRepository
import com.encore.app.core.data.catalog.DownloadCatalogSource
import com.encore.app.core.data.catalog.db.CatalogDatabase
import com.encore.app.core.data.catalog.db.RoomCatalogStore
import com.encore.app.core.data.catalog.db.RoomMovieCatalog
import com.encore.app.core.data.rating.RatingRepository
import com.encore.app.core.data.rating.db.RoomRatingStore
import com.encore.app.core.data.rating.db.UserDatabase
import kotlinx.coroutines.Dispatchers

/** Manual dependency wiring (A5): one instance per process, owned by [EncoreApplication]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val catalogDatabase by lazy { CatalogDatabase.build(appContext) }

    val catalogRepository by lazy {
        CatalogRepository(
            store = RoomCatalogStore(catalogDatabase),
            sources = listOf(AssetCatalogSource(appContext.assets), DownloadCatalogSource(BuildConfig.CATALOG_URL)),
            ioDispatcher = Dispatchers.IO,
            log = { Log.i("EncoreCatalog", it) },
        )
    }

    val movieCatalog by lazy { RoomMovieCatalog(catalogDatabase) }

    private val userDatabase by lazy { UserDatabase.build(appContext) }

    val ratingRepository by lazy { RatingRepository(RoomRatingStore(userDatabase)) }
}

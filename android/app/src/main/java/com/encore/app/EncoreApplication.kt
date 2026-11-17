package com.encore.app

import android.app.Application

class EncoreApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

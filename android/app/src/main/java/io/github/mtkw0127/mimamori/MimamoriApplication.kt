package io.github.mtkw0127.mimamori

import android.app.Application
import timber.log.Timber

class MimamoriApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}

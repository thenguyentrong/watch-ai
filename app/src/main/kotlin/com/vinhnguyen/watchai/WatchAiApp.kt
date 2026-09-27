package com.vinhnguyen.watchai

import android.app.Application
import com.vinhnguyen.watchai.security.ReleaseTree
import timber.log.Timber

class WatchAiApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        Timber.plant(if (BuildConfig.DEBUG) Timber.DebugTree() else ReleaseTree())
    }
}

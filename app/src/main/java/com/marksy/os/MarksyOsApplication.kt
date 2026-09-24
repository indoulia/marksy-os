package com.marksy.os

import android.app.Application
import com.marksy.os.gateway.SecureCredentialStore

class MarksyOsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.initialize(this)
        SecureCredentialStore.purgeLegacyCredentials(this)
    }
}

internal object AppContext {
    private var application: Application? = null

    fun initialize(value: Application) {
        application = value
    }

    fun get(): Application = application
        ?: error("MarksyOsApplication has not been initialized")
}

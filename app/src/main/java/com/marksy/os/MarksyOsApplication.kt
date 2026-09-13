package com.marksy.os

import android.app.Application

class MarksyOsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.initialize(this)
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

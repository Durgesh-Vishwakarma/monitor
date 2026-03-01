package com.micmonitor.app

import android.app.Application
import android.content.Context

class MicApp : Application() {

    companion object {
        lateinit var instance: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = applicationContext
    }
}

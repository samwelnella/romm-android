package com.rommclient.android

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class RommApp : Application() {

    companion object {
        lateinit var instance: RommApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Apply system dark/light mode on app start
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }
}
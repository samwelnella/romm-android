package com.rommclient.android

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class RommApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Apply system dark/light mode on app start
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }
}
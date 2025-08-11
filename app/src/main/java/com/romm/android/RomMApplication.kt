package com.romm.android

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.hilt.work.HiltWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RomMApplication : Application(), Configuration.Provider {
    
    @Inject lateinit var workerFactory: HiltWorkerFactory
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setWorkerFactory(workerFactory)
            .build()
    
    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("RomMApplication", "Application created")
        
        // Log memory info
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory() / 1024 / 1024
        val freeMemory = runtime.freeMemory() / 1024 / 1024
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        
        android.util.Log.d("RomMApplication", "Memory info - Total: ${totalMemory}MB, Free: ${freeMemory}MB, Max: ${maxMemory}MB")
        
        // Manually initialize WorkManager with Hilt factory
        android.util.Log.d("RomMApplication", "Initializing WorkManager with Hilt factory")
        WorkManager.initialize(this, workManagerConfiguration)
        android.util.Log.d("RomMApplication", "WorkManager initialized successfully")
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        android.util.Log.w("RomMApplication", "Low memory warning - forcing garbage collection")
        System.gc()
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        android.util.Log.w("RomMApplication", "Trim memory level: $level")
        if (level >= TRIM_MEMORY_MODERATE) {
            System.gc()
        }
    }
}

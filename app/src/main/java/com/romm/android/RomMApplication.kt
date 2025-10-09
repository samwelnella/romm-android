package com.romm.android

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.hilt.work.HiltWorkerFactory
import com.romm.android.utils.AppLogger
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
        AppLogger.d(tag = "RomMApplication", message = "Application created")
        
        // Log memory info
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory() / 1024 / 1024
        val freeMemory = runtime.freeMemory() / 1024 / 1024
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        
        AppLogger.d(tag = "RomMApplication", message = "Memory info - Total: ${totalMemory}MB, Free: ${freeMemory}MB, Max: ${maxMemory}MB")
        
        // Manually initialize WorkManager with Hilt factory
        AppLogger.d(tag = "RomMApplication", message = "Initializing WorkManager with Hilt factory")
        WorkManager.initialize(this, workManagerConfiguration)
        AppLogger.d(tag = "RomMApplication", message = "WorkManager initialized successfully")
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        AppLogger.w(tag = "RomMApplication", message = "Low memory warning - forcing garbage collection")
        System.gc()
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLogger.w(tag = "RomMApplication", message = "Trim memory level: $level")
        if (level >= TRIM_MEMORY_MODERATE) {
            System.gc()
        }
    }
}

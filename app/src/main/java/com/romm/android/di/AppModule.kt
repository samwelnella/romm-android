package com.romm.android.di

import android.content.Context
import androidx.work.WorkManager
import com.romm.android.data.SettingsRepository
import com.romm.android.network.RomMApiService
import com.romm.android.utils.DownloadManager
import com.romm.android.sync.FileScanner
import com.romm.android.sync.SyncManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context
    ): SettingsRepository {
        return SettingsRepository(context)
    }
    
    @Provides
    @Singleton
    fun provideRomMApiService(
        settingsRepository: SettingsRepository,
        @ApplicationContext context: Context
    ): RomMApiService {
        return RomMApiService(settingsRepository, context)
    }
    
    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context
    ): WorkManager {
        return WorkManager.getInstance(context)
    }
    
    @Provides
    @Singleton
    fun provideDownloadManager(
        @ApplicationContext context: Context,
        apiService: RomMApiService,
        workManager: WorkManager
    ): DownloadManager {
        return DownloadManager(context, apiService, workManager)
    }
    
    @Provides
    @Singleton
    fun provideFileScanner(
        @ApplicationContext context: Context
    ): FileScanner {
        return FileScanner(context)
    }
    
    @Provides
    @Singleton
    fun provideSyncManager(
        @ApplicationContext context: Context,
        apiService: RomMApiService,
        downloadManager: DownloadManager,
        fileScanner: FileScanner
    ): SyncManager {
        return SyncManager(context, apiService, downloadManager, fileScanner)
    }
}

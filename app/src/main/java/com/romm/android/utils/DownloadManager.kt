package com.romm.android.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.romm.android.data.AppSettings
import com.romm.android.data.Firmware
import com.romm.android.data.Game
import com.romm.android.network.RomMApiService
import com.romm.android.workers.UnifiedDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import android.util.Log
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: RomMApiService,
    private val workManager: WorkManager
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    // Download session management
    private var currentSession: DownloadSession? = null
    private var monitoringJob: Job? = null
    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Atomic counters for thread-safe updates
    private val completedCount = AtomicInteger(0)
    private val failedCount = AtomicInteger(0)
    private val cancelledCount = AtomicInteger(0)
    
    data class DownloadSession(
        val sessionId: String = "download_session_${System.currentTimeMillis()}",
        var totalDownloads: Int,
        val maxConcurrentDownloads: Int,
        val workIds: MutableSet<UUID> = mutableSetOf(),
        var isCompleted: Boolean = false
    )
    
    data class DownloadItem(
        val id: String,
        val name: String,
        val type: DownloadType,
        // Game-specific
        val gameId: Int? = null,
        val platformSlug: String? = null,
        val isMulti: Boolean = false,
        // Firmware-specific
        val firmwareId: Int? = null,
        val fileName: String? = null,
        val platformId: Int? = null
    )
    
    enum class DownloadType {
        GAME, FIRMWARE
    }
    
    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CANCEL_ALL) {
                Log.d("DownloadManager", "Cancel all broadcast received")
                cancelAllDownloads()
            }
        }
    }
    
    init {
        createNotificationChannel()
        
        // Register cancel broadcast receiver
        val filter = IntentFilter(ACTION_CANCEL_ALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(cancelReceiver, filter)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Game and firmware downloads"
                setShowBadge(true)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Download a single game using foreground service
     */
    suspend fun downloadGame(game: Game, settings: AppSettings) {
        if (settings.downloadDirectory.isEmpty()) {
            Log.e("DownloadManager", "Download directory is empty!")
            return
        }
        
        val downloadItems = listOf(
            DownloadItem(
                id = "game_${game.id}",
                name = game.name ?: game.fs_name,
                type = DownloadType.GAME,
                gameId = game.id,
                platformSlug = game.platform_fs_slug,
                isMulti = game.multi
            )
        )
        
        startDownloadSession(downloadItems, settings)
    }
    
    /**
     * Download multiple games in bulk using foreground services
     */
    suspend fun downloadAllGames(games: List<Game>, settings: AppSettings) {
        if (settings.downloadDirectory.isEmpty()) {
            Log.e("DownloadManager", "Download directory is empty!")
            return
        }
        
        if (games.isEmpty()) {
            Log.w("DownloadManager", "No games to download")
            return
        }
        
        val downloadItems = games.map { game ->
            DownloadItem(
                id = "game_${game.id}",
                name = game.name ?: game.fs_name,
                type = DownloadType.GAME,
                gameId = game.id,
                platformSlug = game.platform_fs_slug,
                isMulti = game.multi
            )
        }
        
        startDownloadSession(downloadItems, settings)
    }
    
    /**
     * Download multiple firmware files using foreground services
     */
    suspend fun downloadFirmware(firmware: List<Firmware>, settings: AppSettings) {
        if (settings.downloadDirectory.isEmpty()) {
            Log.e("DownloadManager", "Download directory is empty!")
            return
        }
        
        if (firmware.isEmpty()) {
            Log.w("DownloadManager", "No firmware to download")
            return
        }
        
        val downloadItems = firmware.map { fw ->
            DownloadItem(
                id = "firmware_${fw.id}",
                name = "Firmware: ${fw.file_name}",
                type = DownloadType.FIRMWARE,
                firmwareId = fw.id,
                fileName = fw.file_name,
                platformId = fw.platform_id
            )
        }
        
        startDownloadSession(downloadItems, settings)
    }
    
    /**
     * Download missing games - checks for file existence first
     */
    suspend fun downloadMissingGames(games: List<Game>, settings: AppSettings) {
        if (settings.downloadDirectory.isEmpty()) {
            Log.e("DownloadManager", "Download directory is empty!")
            return
        }
        
        val baseDir = DocumentFile.fromTreeUri(context, Uri.parse(settings.downloadDirectory))
        if (baseDir == null) {
            Log.e("DownloadManager", "Cannot access download directory")
            return
        }
        
        // Filter games that don't exist locally
        val missingGames = games.filter { game ->
            !gameExistsLocally(game, baseDir)
        }
        
        Log.d("DownloadManager", "Found ${missingGames.size} missing games out of ${games.size} total")
        
        if (missingGames.isNotEmpty()) {
            downloadAllGames(missingGames, settings)
        }
    }
    
    /**
     * Check if a game already exists locally
     */
    private fun gameExistsLocally(game: Game, baseDir: DocumentFile): Boolean {
        val platformDir = findPlatformDirectory(baseDir, game.platform_fs_slug)
        if (platformDir == null) {
            return false
        }
        
        return if (game.multi) {
            // Multi-disc games are stored in a directory
            val gameDir = platformDir.findFile(game.fs_name_no_ext)
            gameDir != null && gameDir.isDirectory
        } else {
            // Single file games
            val gameFile = platformDir.findFile(game.fs_name)
            gameFile != null && gameFile.isFile
        }
    }
    
    /**
     * Find platform directory (handles numbered variants)
     */
    private fun findPlatformDirectory(baseDir: DocumentFile, platformSlug: String): DocumentFile? {
        val files = baseDir.listFiles()
        
        // First pass: exact match
        for (file in files) {
            if (file.isDirectory && file.name == platformSlug) {
                return file
            }
        }
        
        // Second pass: numbered variants like "snes (1)"
        for (file in files) {
            if (file.isDirectory) {
                val name = file.name ?: continue
                val regex = Regex("^${Regex.escape(platformSlug)}\\s*\\(\\d+\\)$")
                if (regex.matches(name)) {
                    return file
                }
            }
        }
        
        return null
    }
    
    /**
     * Start a new download session or add to existing session
     */
    private suspend fun startDownloadSession(downloadItems: List<DownloadItem>, settings: AppSettings) {
        val existingSession = currentSession
        
        if (existingSession != null && !existingSession.isCompleted) {
            // Add to existing session
            Log.d("DownloadManager", "Adding ${downloadItems.size} items to existing session: ${existingSession.sessionId}")
            addItemsToExistingSession(downloadItems, settings, existingSession)
        } else {
            // Create new session
            Log.d("DownloadManager", "Creating new download session: ${downloadItems.size} items, max concurrent: ${settings.maxConcurrentDownloads}")
            createNewSession(downloadItems, settings)
        }
    }
    
    /**
     * Add items to an existing download session
     */
    private suspend fun addItemsToExistingSession(
        downloadItems: List<DownloadItem>, 
        settings: AppSettings, 
        session: DownloadSession
    ) {
        // Update session totals
        session.totalDownloads += downloadItems.size
        
        // Create constraints for network connectivity
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        // Create work requests with proper concurrency control
        val workRequests = downloadItems.map { item ->
            val workRequest = OneTimeWorkRequestBuilder<UnifiedDownloadWorker>()
                .setInputData(createWorkData(item, settings))
                .setConstraints(constraints)
                .addTag(DOWNLOAD_TAG)
                .addTag("session_${session.sessionId}")
                .build()
            
            session.workIds.add(workRequest.id)
            workRequest
        }
        
        // Enqueue additional work with same concurrency control 
        // For existing sessions, append to the existing chains
        enqueueAdditionalWorkToExistingChains(workRequests, session.maxConcurrentDownloads, session.sessionId)
        
        // Update notification to show new totals
        updateSummaryNotification()
        
        Log.d("DownloadManager", "Added ${downloadItems.size} items to session. New total: ${session.totalDownloads}")
    }
    
    /**
     * Create a completely new download session
     */
    private suspend fun createNewSession(downloadItems: List<DownloadItem>, settings: AppSettings) {
        // Cancel any previous completed session
        if (currentSession != null) {
            cancelAllDownloads()
        }
        
        // Reset counters
        completedCount.set(0)
        failedCount.set(0)
        cancelledCount.set(0)
        
        // Create new session
        currentSession = DownloadSession(
            totalDownloads = downloadItems.size,
            maxConcurrentDownloads = settings.maxConcurrentDownloads
        )
        
        // Show initial summary notification
        updateSummaryNotification()
        
        // Create constraints for network connectivity
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        // Create work requests with proper concurrency control
        val workRequests = downloadItems.map { item ->
            val workRequest = OneTimeWorkRequestBuilder<UnifiedDownloadWorker>()
                .setInputData(createWorkData(item, settings))
                .setConstraints(constraints)
                .addTag(DOWNLOAD_TAG)
                .addTag("session_${currentSession!!.sessionId}")
                .build()
            
            currentSession!!.workIds.add(workRequest.id)
            workRequest
        }
        
        // Enqueue work with concurrency control
        enqueueWorkWithConcurrencyControl(workRequests, settings.maxConcurrentDownloads)
        
        // Start monitoring
        startSessionMonitoring()
    }
    
    /**
     * Create work data for download items
     */
    private fun createWorkData(item: DownloadItem, settings: AppSettings): Data {
        val builder = Data.Builder()
            .putString("downloadId", item.id)
            .putString("downloadName", item.name)
            .putString("downloadType", item.type.name)
            .putString("host", settings.host)
            .putString("username", settings.username)
            .putString("password", settings.password)
            .putString("downloadDirectory", settings.downloadDirectory)
            .putString("sessionId", currentSession!!.sessionId)
        
        when (item.type) {
            DownloadType.GAME -> {
                builder.putInt("gameId", item.gameId!!)
                    .putString("platformSlug", item.platformSlug!!)
                    .putBoolean("isMulti", item.isMulti)
            }
            DownloadType.FIRMWARE -> {
                builder.putInt("firmwareId", item.firmwareId!!)
                    .putString("fileName", item.fileName!!)
                    .putInt("platformId", item.platformId!!)
            }
        }
        
        return builder.build()
    }
    
    /**
     * Enqueue work requests with proper concurrency control
     */
    private fun enqueueWorkWithConcurrencyControl(workRequests: List<OneTimeWorkRequest>, maxConcurrent: Int) {
        Log.d("DownloadManager", "Enqueuing ${workRequests.size} downloads with max concurrent: $maxConcurrent")
        
        if (maxConcurrent == 1) {
            // Sequential execution - chain all work requests
            if (workRequests.isNotEmpty()) {
                var continuation = workManager.beginWith(workRequests.first())
                for (i in 1 until workRequests.size) {
                    continuation = continuation.then(workRequests[i])
                }
                continuation.enqueue()
                Log.d("DownloadManager", "Enqueued ${workRequests.size} downloads sequentially")
            }
        } else {
            // Limited concurrent execution using multiple chains
            // Create exactly maxConcurrent chains and distribute work across them
            for (i in workRequests.indices) {
                val chainIndex = i % maxConcurrent
                val chainName = "download_chain_${chainIndex}_${currentSession?.sessionId}"
                
                Log.d("DownloadManager", "Adding work ${i + 1}/${workRequests.size} to chain $chainIndex")
                
                workManager.beginUniqueWork(
                    chainName,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    workRequests[i]
                ).enqueue()
            }
            
            Log.d("DownloadManager", "Enqueued ${workRequests.size} downloads across $maxConcurrent chains")
        }
    }
    
    /**
     * Add additional work to existing download chains
     */
    private fun enqueueAdditionalWorkToExistingChains(workRequests: List<OneTimeWorkRequest>, maxConcurrent: Int, sessionId: String) {
        Log.d("DownloadManager", "Adding ${workRequests.size} downloads to existing chains with max concurrent: $maxConcurrent")
        
        if (maxConcurrent == 1) {
            // Sequential execution - append to the single chain
            val chainName = "download_chain_0_$sessionId"
            for (workRequest in workRequests) {
                workManager.beginUniqueWork(
                    chainName,
                    ExistingWorkPolicy.APPEND,
                    workRequest
                ).enqueue()
            }
            Log.d("DownloadManager", "Appended ${workRequests.size} downloads to sequential chain")
        } else {
            // Distribute across existing chains
            for (i in workRequests.indices) {
                val chainIndex = i % maxConcurrent
                val chainName = "download_chain_${chainIndex}_$sessionId"
                
                Log.d("DownloadManager", "Appending work ${i + 1}/${workRequests.size} to existing chain $chainIndex")
                
                workManager.beginUniqueWork(
                    chainName,
                    ExistingWorkPolicy.APPEND,
                    workRequests[i]
                ).enqueue()
            }
            
            Log.d("DownloadManager", "Appended ${workRequests.size} downloads to existing chains")
        }
    }
    
    /**
     * Start monitoring the download session
     */
    private fun startSessionMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = sessionScope.launch {
            val session = currentSession ?: return@launch
            
            while (!session.isCompleted) {
                try {
                    val workInfos = workManager.getWorkInfosByTag("session_${session.sessionId}").get()
                    
                    var activeCount = 0
                    var completedThisCheck = 0
                    var failedThisCheck = 0
                    var cancelledThisCheck = 0
                    
                    for (workInfo in workInfos) {
                        when (workInfo.state) {
                            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> activeCount++
                            WorkInfo.State.SUCCEEDED -> completedThisCheck++
                            WorkInfo.State.FAILED -> failedThisCheck++
                            WorkInfo.State.CANCELLED -> cancelledThisCheck++
                            else -> {}
                        }
                    }
                    
                    // Update counters
                    completedCount.set(completedThisCheck)
                    failedCount.set(failedThisCheck)
                    cancelledCount.set(cancelledThisCheck)
                    
                    // Update notification
                    updateSummaryNotification()
                    
                    // Check if session is complete
                    if (activeCount == 0 && (completedThisCheck + failedThisCheck + cancelledThisCheck) == session.totalDownloads) {
                        session.isCompleted = true
                        showFinalNotification()
                        break
                    }
                    
                    delay(1000) // Check every second
                } catch (e: Exception) {
                    Log.e("DownloadManager", "Error monitoring session", e)
                    delay(2000)
                }
            }
        }
    }
    
    /**
     * Update the summary notification
     */
    private fun updateSummaryNotification() {
        val session = currentSession ?: return
        val completed = completedCount.get()
        val failed = failedCount.get()
        val cancelled = cancelledCount.get()
        val active = session.totalDownloads - completed - failed - cancelled
        
        val title = "Downloads"
        val text = "${completed + failed + cancelled} out of ${session.totalDownloads} completed, $active active downloads"
        
        val cancelIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(session.totalDownloads, completed + failed + cancelled, false)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(active > 0)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setNumber(active)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel All", cancelIntent)
            .build()
        
        notificationManager.notify(SUMMARY_NOTIFICATION_ID, notification)
    }
    
    /**
     * Show final completion notification
     */
    private fun showFinalNotification() {
        val session = currentSession ?: return
        val completed = completedCount.get()
        val failed = failedCount.get()
        val cancelled = cancelledCount.get()
        
        val title = when {
            failed == 0 && cancelled == 0 -> "All Downloads Complete!"
            completed == 0 -> "Downloads Failed"
            else -> "Downloads Complete"
        }
        
        val parts = mutableListOf<String>()
        if (completed > 0) parts.add("$completed completed")
        if (failed > 0) parts.add("$failed failed")
        if (cancelled > 0) parts.add("$cancelled cancelled")
        
        val text = parts.joinToString(", ")
        val icon = if (failed == 0 && cancelled == 0) {
            android.R.drawable.stat_sys_download_done
        } else {
            android.R.drawable.stat_notify_error
        }
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setNumber(session.totalDownloads)
            .build()
        
        notificationManager.notify(SUMMARY_NOTIFICATION_ID, notification)
    }
    
    /**
     * Cancel all active downloads
     */
    fun cancelAllDownloads() {
        val session = currentSession
        if (session != null) {
            Log.d("DownloadManager", "Cancelling all downloads for session: ${session.sessionId}")
            
            // Cancel all work by tags
            workManager.cancelAllWorkByTag("session_${session.sessionId}")
            workManager.cancelAllWorkByTag(DOWNLOAD_TAG)
            
            // Cancel all chains for this session
            for (i in 0 until session.maxConcurrentDownloads) {
                val chainName = "download_chain_${i}_${session.sessionId}"
                workManager.cancelUniqueWork(chainName)
            }
            
            // Mark session as completed
            session.isCompleted = true
        }
        
        // Cancel monitoring
        monitoringJob?.cancel()
        monitoringJob = null
        
        // Clear notification
        notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        
        // Clear session
        currentSession = null
        
        Log.d("DownloadManager", "All downloads cancelled")
    }
    
    /**
     * Get current download status
     */
    suspend fun getDownloadStatus(): List<WorkInfo> {
        return currentSession?.let { session ->
            workManager.getWorkInfosByTag("session_${session.sessionId}").get()
        } ?: emptyList()
    }
    
    fun cleanup() {
        try {
            context.unregisterReceiver(cancelReceiver)
        } catch (e: Exception) {
            Log.w("DownloadManager", "Error unregistering receiver", e)
        }
        sessionScope.cancel()
    }
    
    companion object {
        const val CHANNEL_ID = "download_channel"
        const val SUMMARY_NOTIFICATION_ID = 999999
        const val DOWNLOAD_TAG = "unified_download"
        const val ACTION_CANCEL_ALL = "com.romm.android.ACTION_CANCEL_ALL_DOWNLOADS"
    }
}
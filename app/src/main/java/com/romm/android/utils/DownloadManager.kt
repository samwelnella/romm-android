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
        var isCompleted: Boolean = false,
        val originalSessionId: String? = null // For recovery sessions
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
        
        // Check for orphaned downloads on startup (recovery from crash)
        sessionScope.launch {
            checkAndRecoverOrphanedDownloads()
        }
    }
    
    /**
     * Check for downloads that are still running after app restart (crash recovery)
     */
    private suspend fun checkAndRecoverOrphanedDownloads() {
        try {
            // Get all work with our download tag first
            val allWork = workManager.getWorkInfosByTag(DOWNLOAD_TAG).get()
            
            // Debug: Log all work states with more detail
            Log.d("DownloadManager", "=== RECOVERY DEBUG START ===")
            Log.d("DownloadManager", "Total historical work found: ${allWork.size}")
            
            // Group work by state for better debugging
            val workByState = allWork.groupBy { it.state }
            workByState.forEach { (state, works) ->
                Log.d("DownloadManager", "State $state: ${works.size} items")
                if (works.size <= 10) { // Don't spam for large numbers
                    works.forEach { work ->
                        Log.d("DownloadManager", "  - Work ${work.id}: tags=${work.tags}")
                    }
                }
            }
            
            // Find truly active work (ENQUEUED or RUNNING)
            val activeWork = allWork.filter { 
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED 
            }
            
            Log.d("DownloadManager", "Found ${activeWork.size} truly active downloads:")
            activeWork.forEach { workInfo ->
                Log.d("DownloadManager", "Active work ${workInfo.id}: state=${workInfo.state}, tags=${workInfo.tags}")
            }
            
            // CRITICAL: Check if there are more downloads from the same session that haven't started yet
            // Look for recent session tags in ALL work (not just active) to find the most recent session
            val recentSessionTags = allWork.mapNotNull { workInfo ->
                workInfo.tags.find { tag -> tag.startsWith("session_") }
            }.distinct()
            
            Log.d("DownloadManager", "All session tags found in historical work: $recentSessionTags")
            
            if (activeWork.isNotEmpty()) {
                Log.d("DownloadManager", "Attempting session recovery for ${activeWork.size} active downloads")
                
                // Look for session tags in active work first
                val activeSessionTags = activeWork.mapNotNull { workInfo ->
                    workInfo.tags.find { tag -> tag.startsWith("session_") }
                }.distinct()
                
                Log.d("DownloadManager", "Session tags in active work: $activeSessionTags")
                
                // If we have a recent session, check for ALL work from that session (including non-active)
                val sessionToRecover = activeSessionTags.firstOrNull() ?: recentSessionTags.maxByOrNull { it }
                
                if (sessionToRecover != null) {
                    val originalSessionId = sessionToRecover.removePrefix("session_")
                    Log.d("DownloadManager", "Recovering session: $originalSessionId")
                    
                    // Get ALL work from this session (active + blocked/pending)
                    val allSessionWork = allWork.filter { workInfo ->
                        workInfo.tags.contains(sessionToRecover)
                    }
                    
                    Log.d("DownloadManager", "Total work items for session $originalSessionId: ${allSessionWork.size}")
                    allSessionWork.forEach { workInfo ->
                        Log.d("DownloadManager", "Session work ${workInfo.id}: state=${workInfo.state}")
                    }
                    
                    // Count non-completed work items
                    val nonCompletedWork = allSessionWork.filter { 
                        it.state != WorkInfo.State.SUCCEEDED && it.state != WorkInfo.State.FAILED && it.state != WorkInfo.State.CANCELLED
                    }
                    
                    Log.d("DownloadManager", "Non-completed work items: ${nonCompletedWork.size}")
                    
                    // Create recovery session tracking ALL work from the session
                    currentSession = DownloadSession(
                        sessionId = "recovery_$originalSessionId",
                        totalDownloads = allSessionWork.size, // Total from original session
                        maxConcurrentDownloads = determineMaxConcurrentFromWork(activeWork),
                        originalSessionId = originalSessionId
                    )
                    
                    // Track ALL work from the session for monitoring
                    allSessionWork.forEach { workInfo ->
                        currentSession?.workIds?.add(workInfo.id)
                    }
                    
                    Log.d("DownloadManager", "Recovery session created: totalDownloads=${currentSession?.totalDownloads}, tracking ${currentSession?.workIds?.size} work items")
                } else {
                    // No session info - create simple recovery
                    Log.d("DownloadManager", "No session info found - creating simple recovery")
                    
                    currentSession = DownloadSession(
                        sessionId = "recovery_${System.currentTimeMillis()}",
                        totalDownloads = activeWork.size,
                        maxConcurrentDownloads = determineMaxConcurrentFromWork(activeWork)
                    )
                    
                    activeWork.forEach { workInfo ->
                        currentSession?.workIds?.add(workInfo.id)
                    }
                }
                
                // Reset counters - we'll count actual completed work during monitoring
                this.completedCount.set(0)
                this.failedCount.set(0)
                this.cancelledCount.set(0)
                
                // Start monitoring the recovered session
                startSessionMonitoring()
                
                // Show recovery notification
                updateSummaryNotification()
                
                Log.d("DownloadManager", "=== RECOVERY DEBUG END - Setup complete, monitoring ${currentSession?.workIds?.size} work items ===")
            } else {
                Log.d("DownloadManager", "No active downloads found during recovery check")
                Log.d("DownloadManager", "=== RECOVERY DEBUG END - No recovery needed ===")
            }
        } catch (e: Exception) {
            Log.e("DownloadManager", "Error during orphaned download recovery", e)
        }
    }
    
    /**
     * Try to determine max concurrent downloads from work data
     */
    private fun determineMaxConcurrentFromWork(activeWork: List<WorkInfo>): Int {
        // WorkInfo doesn't expose inputData directly, so we'll use a reasonable default
        // In practice, we could store this in shared preferences or elsewhere for recovery
        
        // For now, analyze the number of active downloads to make a reasonable guess
        val activeCount = activeWork.size
        
        // If we have more than 3 active downloads, it suggests higher concurrency was set
        val estimatedMaxConcurrent = when {
            activeCount >= 5 -> 5
            activeCount >= 3 -> 3
            activeCount >= 2 -> 2
            else -> 1
        }
        
        Log.d("DownloadManager", "Estimated maxConcurrentDownloads based on ${activeCount} active items: $estimatedMaxConcurrent")
        return estimatedMaxConcurrent
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
    suspend fun downloadMissingGames(games: List<Game>, settings: AppSettings) = withContext(Dispatchers.IO) {
        if (settings.downloadDirectory.isEmpty()) {
            Log.e("DownloadManager", "Download directory is empty!")
            return@withContext
        }
        
        val baseDir = DocumentFile.fromTreeUri(context, Uri.parse(settings.downloadDirectory))
        if (baseDir == null) {
            Log.e("DownloadManager", "Cannot access download directory")
            return@withContext
        }
        
        Log.d("DownloadManager", "Checking ${games.size} games for local existence...")
        val startTime = System.currentTimeMillis()
        
        // Cache platform directories to avoid repeated lookups
        val platformDirCache = mutableMapOf<String, DocumentFile?>()
        
        // Check games in parallel with limited concurrency to avoid overwhelming the filesystem
        val missingGames = games.chunked(20).flatMap { chunk ->
            chunk.map { game ->
                async {
                    val exists = gameExistsLocallyWithCache(game, baseDir, platformDirCache)
                    if (!exists) game else null
                }
            }.awaitAll().filterNotNull()
        }
        
        val checkTime = System.currentTimeMillis() - startTime
        Log.d("DownloadManager", "Found ${missingGames.size} missing games out of ${games.size} total (checked in ${checkTime}ms)")
        
        if (missingGames.isNotEmpty()) {
            // Switch back to main context for the download call
            withContext(Dispatchers.Main) {
                downloadAllGames(missingGames, settings)
            }
        }
    }
    
    /**
     * Check if a game already exists locally (optimized for performance)
     */
    private fun gameExistsLocally(game: Game, baseDir: DocumentFile): Boolean {
        return try {
            val platformDir = findPlatformDirectory(baseDir, game.platform_fs_slug)
                ?: return false
            
            if (game.multi) {
                // Multi-disc games are stored in a directory
                val gameDir = platformDir.findFile(game.fs_name_no_ext)
                gameDir?.isDirectory == true
            } else {
                // Single file games
                val gameFile = platformDir.findFile(game.fs_name)
                gameFile?.isFile == true
            }
        } catch (e: Exception) {
            // If any file system operation fails, assume game doesn't exist
            Log.w("DownloadManager", "Error checking if game exists: ${game.name}", e)
            false
        }
    }
    
    /**
     * Check if a game already exists locally with platform directory caching
     */
    private suspend fun gameExistsLocallyWithCache(
        game: Game, 
        baseDir: DocumentFile, 
        platformDirCache: MutableMap<String, DocumentFile?>
    ): Boolean {
        return try {
            // Use cached platform directory or find and cache it
            val platformDir = platformDirCache.getOrPut(game.platform_fs_slug) {
                findPlatformDirectory(baseDir, game.platform_fs_slug)
            } ?: return false
            
            if (game.multi) {
                // Multi-disc games are stored in a directory
                val gameDir = platformDir.findFile(game.fs_name_no_ext)
                gameDir?.isDirectory == true
            } else {
                // Single file games
                val gameFile = platformDir.findFile(game.fs_name)
                gameFile?.isFile == true
            }
        } catch (e: Exception) {
            // If any file system operation fails, assume game doesn't exist
            Log.w("DownloadManager", "Error checking if game exists: ${game.name}", e)
            false
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
            .putInt("maxConcurrentDownloads", settings.maxConcurrentDownloads)
        
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
            // Sequential execution - chain all work requests one after another
            if (workRequests.isNotEmpty()) {
                var continuation = workManager.beginWith(workRequests.first())
                for (i in 1 until workRequests.size) {
                    continuation = continuation.then(workRequests[i])
                }
                continuation.enqueue()
                Log.d("DownloadManager", "Enqueued ${workRequests.size} downloads sequentially")
            }
        } else {
            // Concurrent execution - just enqueue all requests directly
            // WorkManager will run them concurrently up to system limits
            // We'll control concurrency in the worker itself
            workManager.enqueue(workRequests)
            Log.d("DownloadManager", "Enqueued ${workRequests.size} downloads for concurrent execution (max: $maxConcurrent)")
        }
    }
    
    /**
     * Add additional work to existing session
     */
    private fun enqueueAdditionalWorkToExistingChains(workRequests: List<OneTimeWorkRequest>, maxConcurrent: Int, sessionId: String) {
        Log.d("DownloadManager", "Adding ${workRequests.size} downloads to existing session with max concurrent: $maxConcurrent")
        
        // Just enqueue the additional work - concurrency is controlled by the worker
        workManager.enqueue(workRequests)
        
        Log.d("DownloadManager", "Enqueued ${workRequests.size} additional downloads")
    }
    
    /**
     * Start monitoring the download session
     */
    private fun startSessionMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = sessionScope.launch {
            val session = currentSession ?: return@launch
            
            Log.d("DownloadManager", "Starting session monitoring for ${session.workIds.size} work items")
            
            while (!session.isCompleted) {
                try {
                    // Always monitor the specific work IDs we're tracking
                    val workInfos = session.workIds.mapNotNull { workId ->
                        try {
                            workManager.getWorkInfoById(workId).get()
                        } catch (e: Exception) {
                            Log.w("DownloadManager", "Could not get work info for $workId", e)
                            null
                        }
                    }
                    
                    var activeCount = 0
                    var completedThisCheck = 0
                    var failedThisCheck = 0
                    var cancelledThisCheck = 0
                    
                    Log.d("DownloadManager", "=== MONITORING UPDATE ===")
                    Log.d("DownloadManager", "Checking ${workInfos.size} work items:")
                    
                    for (workInfo in workInfos) {
                        Log.d("DownloadManager", "Work ${workInfo.id}: state=${workInfo.state}")
                        when (workInfo.state) {
                            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> activeCount++
                            WorkInfo.State.SUCCEEDED -> completedThisCheck++
                            WorkInfo.State.FAILED -> failedThisCheck++
                            WorkInfo.State.CANCELLED -> cancelledThisCheck++
                            else -> {
                                Log.d("DownloadManager", "Work ${workInfo.id}: unexpected state ${workInfo.state}")
                            }
                        }
                    }
                    
                    Log.d("DownloadManager", "Count summary: active=$activeCount, completed=$completedThisCheck, failed=$failedThisCheck, cancelled=$cancelledThisCheck, total=${session.totalDownloads}")
                    
                    // Update counters
                    val prevCompleted = completedCount.get()
                    val prevFailed = failedCount.get()
                    val prevCancelled = cancelledCount.get()
                    
                    completedCount.set(completedThisCheck)
                    failedCount.set(failedThisCheck)
                    cancelledCount.set(cancelledThisCheck)
                    
                    // Log counter changes
                    if (completedThisCheck != prevCompleted || failedThisCheck != prevFailed || cancelledThisCheck != prevCancelled) {
                        Log.d("DownloadManager", "Counters updated: completed $prevCompleted->$completedThisCheck, failed $prevFailed->$failedThisCheck, cancelled $prevCancelled->$cancelledThisCheck")
                    }
                    
                    // Update notification
                    updateSummaryNotification()
                    
                    // Check if session is complete
                    val totalProcessed = completedThisCheck + failedThisCheck + cancelledThisCheck
                    if (activeCount == 0 && totalProcessed >= session.totalDownloads) {
                        Log.d("DownloadManager", "Session completed: $totalProcessed out of ${session.totalDownloads} processed")
                        session.isCompleted = true
                        showFinalNotification()
                        
                        // Clean up semaphores for this session
                        val cleanupSessionId = session.originalSessionId ?: session.sessionId
                        UnifiedDownloadWorker.cleanupSession(cleanupSessionId)
                        break
                    }
                    
                    Log.d("DownloadManager", "=== MONITORING UPDATE END ===")
                    
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
        val totalProcessed = completed + failed + cancelled
        val remaining = session.totalDownloads - totalProcessed
        
        // Active downloads = downloads that have acquired semaphore permits and are actually downloading
        // This will be limited by the maxConcurrentDownloads setting
        val activeDownloads = minOf(remaining, session.maxConcurrentDownloads)
        
        val title = "Downloads"
        val text = "$totalProcessed out of ${session.totalDownloads} completed, $activeDownloads active downloads"
        
        val cancelIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(session.totalDownloads, totalProcessed, false)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(activeDownloads > 0)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setNumber(activeDownloads)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel All", cancelIntent)
            .setShowWhen(false)
            // Don't group the summary - let it stand alone
            // Individual notifications will be grouped together separately
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
            // Don't group final notification - let it stand alone
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
            
            // Cancel all work by tags (use original session ID for recovery sessions)
            val cancelSessionId = session.originalSessionId ?: session.sessionId
            workManager.cancelAllWorkByTag("session_$cancelSessionId")
            workManager.cancelAllWorkByTag(DOWNLOAD_TAG)
            
            // Mark session as completed
            session.isCompleted = true
            
            // Clean up semaphores for this session
            UnifiedDownloadWorker.cleanupSession(cancelSessionId)
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
        const val NOTIFICATION_GROUP_KEY = "com.romm.android.DOWNLOAD_GROUP"
    }
}
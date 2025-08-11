package com.romm.android.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.romm.android.data.AppSettings
import com.romm.android.data.Firmware
import com.romm.android.data.Game
import com.romm.android.network.RomMApiService
import com.romm.android.workers.GameDownloadWorker
import com.romm.android.workers.FirmwareDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: RomMApiService,
    private val workManager: WorkManager
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    // Track all download progress - unified system
    private var currentDownloadSession: String? = null
    private val currentDownloadTracker = ConcurrentHashMap<String, DownloadTracker>()
    private val monitoringJobs = ConcurrentHashMap<String, Job>()
    // Track individual notification IDs for cleanup
    private val individualNotificationIds = ConcurrentHashMap<String, MutableList<Int>>()
    
    data class DownloadTracker(
        val totalGames: Int,
        val gameNames: MutableList<String> = mutableListOf(),
        val workIds: MutableList<String> = mutableListOf(),
        val sessionType: DownloadSessionType = DownloadSessionType.BULK
    )
    
    enum class DownloadSessionType {
        BULK, INDIVIDUAL
    }
    
    init {
        createNotificationChannel()
        setInstance(this)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_DEFAULT // Use DEFAULT importance for proper grouping
            ).apply {
                description = "Game and firmware downloads"
                setShowBadge(true) // Show badge on summary notification
                enableLights(false) // Disable lights to avoid distraction
                enableVibration(false) // Disable vibration to avoid noise
                setSound(null, null) // No sound for quiet operation
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun startMonitoringDownload(sessionId: String) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            Log.d("DownloadManager", "Starting monitoring for unified session: $sessionId")
            
            while (currentDownloadTracker.containsKey(sessionId)) {
                try {
                    val tracker = currentDownloadTracker[sessionId] ?: break
                    
                    // Get work info for this session
                    val workInfos = workManager.getWorkInfosByTag(sessionId).get()
                    
                    val completed = workInfos.count { it.state == WorkInfo.State.SUCCEEDED }
                    val failed = workInfos.count { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED }
                    val running = workInfos.count { it.state == WorkInfo.State.RUNNING }
                    val enqueued = workInfos.count { it.state == WorkInfo.State.ENQUEUED }
                    val total = tracker.totalGames
                    
                    Log.d("DownloadManager", "Session $sessionId progress: $completed completed, $failed failed, $running running, $enqueued enqueued out of $total")
                    
                    // Update notification based on session type
                    val isFirmwareSession = sessionId.startsWith("firmware_")
                    if (completed + failed < total) {
                        // Still in progress
                        if (isFirmwareSession) {
                            showUnifiedFirmwareDownloadProgressNotification(
                                sessionId = sessionId,
                                completed = completed,
                                failed = failed,
                                inProgress = running + enqueued,
                                total = total
                            )
                        } else {
                            showUnifiedDownloadProgressNotification(
                                sessionId = sessionId,
                                completed = completed,
                                failed = failed,
                                inProgress = running + enqueued,
                                total = total
                            )
                        }
                    } else {
                        // All done
                        Log.d("DownloadManager", "Download session $sessionId completed: $completed success, $failed failed")
                        if (isFirmwareSession) {
                            showUnifiedFirmwareDownloadCompleteNotification(
                                sessionId = sessionId,
                                completed = completed,
                                failed = failed,
                                total = total
                            )
                        } else {
                            showUnifiedDownloadCompleteNotification(
                                sessionId = sessionId,
                                completed = completed,
                                failed = failed,
                                total = total
                            )
                        }
                        
                        // Clean up individual notifications after a delay to let users see the summary
                        CoroutineScope(Dispatchers.IO).launch {
                            delay(10000) // Wait 10 seconds
                            cleanupIndividualNotifications(sessionId)
                        }
                        
                        // Clean up tracker and reset current session if this was it
                        currentDownloadTracker.remove(sessionId)
                        if (currentDownloadSession == sessionId) {
                            currentDownloadSession = null
                        }
                        break
                    }
                    
                    // Check every 2 seconds
                    delay(2000)
                    
                } catch (e: Exception) {
                    Log.e("DownloadManager", "Error monitoring download session $sessionId", e)
                    break
                }
            }
            
            Log.d("DownloadManager", "Stopped monitoring session: $sessionId")
            monitoringJobs.remove(sessionId)
        }
        
        monitoringJobs[sessionId] = job
    }
    
    private fun showUnifiedDownloadProgressNotification(
        sessionId: String,
        completed: Int,
        failed: Int,
        inProgress: Int,
        total: Int
    ) {
        val title = "Downloading Games"
        val text = if (failed > 0) {
            "Progress: $completed/$total completed, $failed failed, $inProgress in progress"
        } else {
            "Progress: $completed/$total completed, $inProgress in progress"
        }
        
        Log.d("DownloadManager", "Updating unified notification: $text")
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total, completed, false)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true) // Don't make noise for progress updates
            .setGroup(UNIFIED_DOWNLOAD_GROUP)
            .setGroupSummary(true) // This is the summary notification
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY) // Only this notification alerts
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Higher priority than children to ensure it appears first
            .setAutoCancel(false) // Don't auto-cancel during progress
            .setNumber(inProgress + completed) // Show count badge
            .build()
        
        notificationManager.notify(getUnifiedNotificationId(sessionId), notification)
    }
    
    private fun showUnifiedDownloadCompleteNotification(
        sessionId: String,
        completed: Int,
        failed: Int,
        total: Int
    ) {
        val title = when {
            failed == 0 -> "All Downloads Complete!"
            completed == 0 -> "All Downloads Failed"
            else -> "Downloads Complete"
        }
        
        val text = when {
            failed == 0 -> "Successfully downloaded $completed ${if (completed == 1) "game" else "games"}"
            completed == 0 -> "Failed to download $failed ${if (failed == 1) "game" else "games"}"
            else -> "Downloaded $completed ${if (completed == 1) "game" else "games"}, $failed failed"
        }
        
        val icon = if (failed == 0) {
            android.R.drawable.stat_sys_download_done
        } else {
            android.R.drawable.stat_notify_error
        }
        
        Log.d("DownloadManager", "Final unified notification: $title - $text")
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setAutoCancel(true)
            .setOngoing(false) // Not ongoing anymore
            .setGroup(UNIFIED_DOWNLOAD_GROUP)
            .setGroupSummary(true) // This is the summary notification
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY) // Only this notification alerts
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Higher priority than children to ensure it appears first
            .setNumber(total) // Show total count badge
            .build()
        
        notificationManager.notify(getUnifiedNotificationId(sessionId), notification)
    }
    
    private fun getUnifiedNotificationId(sessionId: String): Int {
        return sessionId.hashCode()
    }
    
    /**
     * Get or create a unified download session. If there's already an active session,
     * new downloads will be added to it. Otherwise, create a new session.
     */
    private fun getOrCreateUnifiedSession(gameName: String): String {
        return currentDownloadSession ?: run {
            val newSessionId = "unified_${System.currentTimeMillis()}"
            currentDownloadSession = newSessionId
            
            // Initialize tracker for this session
            currentDownloadTracker[newSessionId] = DownloadTracker(
                totalGames = 0, // Will be updated as games are added
                gameNames = mutableListOf(),
                workIds = mutableListOf(),
                sessionType = DownloadSessionType.INDIVIDUAL
            )
            
            Log.d("DownloadManager", "Created new unified download session: $newSessionId")
            newSessionId
        }
    }
    
    /**
     * Add a game to the current unified session
     */
    private fun addGameToUnifiedSession(sessionId: String, gameName: String, workId: String) {
        currentDownloadTracker[sessionId]?.let { tracker ->
            tracker.gameNames.add(gameName)
            tracker.workIds.add(workId)
            
            // Update total games count
            val updatedTracker = tracker.copy(totalGames = tracker.gameNames.size)
            currentDownloadTracker[sessionId] = updatedTracker
            
            Log.d("DownloadManager", "Added game '$gameName' to unified session $sessionId. Total games: ${updatedTracker.totalGames}")
        }
    }
    
    suspend fun downloadGame(game: Game, settings: AppSettings) {
        Log.d("DownloadManager", "Starting individual download for game: ${game.name ?: game.fs_name}")
        Log.d("DownloadManager", "Download directory: ${settings.downloadDirectory}")
        
        if (settings.downloadDirectory.isEmpty()) {
            Log.e("DownloadManager", "Download directory is empty!")
            showErrorNotification("Download failed: No download directory selected")
            return
        }
        
        val gameName = game.name ?: game.fs_name
        
        // Get or create unified session for this individual download
        val sessionId = getOrCreateUnifiedSession(gameName)
        
        val workRequest = OneTimeWorkRequestBuilder<GameDownloadWorker>()
            .setInputData(
                workDataOf(
                    "gameId" to game.id,
                    "gameName" to gameName,
                    "platformSlug" to game.platform_fs_slug,
                    "isMulti" to game.multi,
                    "host" to settings.host,
                    "username" to settings.username,
                    "password" to settings.password,
                    "downloadDirectory" to settings.downloadDirectory,
                    "maxConcurrentDownloads" to settings.maxConcurrentDownloads,
                    "isBulkDownload" to false,
                    "bulkSessionId" to sessionId // Use unified session ID
                )
            )
            .addTag("download_work") // Use consistent tag for concurrency control
            .addTag("unified_download")
            .addTag(sessionId) // Add session ID as tag for tracking
            .addTag("game_${game.id}")
            .build()
        
        // Add this game to the unified session
        addGameToUnifiedSession(sessionId, gameName, workRequest.id.toString())
        
        // Use unique work name to ensure all games are enqueued
        val uniqueWorkName = "download_individual_${game.id}_${System.currentTimeMillis()}"
        Log.d("DownloadManager", "Enqueuing individual download with unified session ID: $sessionId (Work ID: ${workRequest.id})")
        
        // Use a queue-based approach to respect maxConcurrentDownloads
        enqueueWithConcurrencyLimit(workRequest, settings.maxConcurrentDownloads)
        
        // Start monitoring if this is the first download in the session
        if (!monitoringJobs.containsKey(sessionId)) {
            Log.d("DownloadManager", "Starting monitoring for new unified session: $sessionId")
            startMonitoringDownload(sessionId)
            
            // Show initial progress notification
            showUnifiedDownloadProgressNotification(
                sessionId = sessionId,
                completed = 0,
                failed = 0,
                inProgress = 1,
                total = 1
            )
        }
    }
    
    suspend fun downloadAllGames(games: List<Game>, settings: AppSettings) {
        Log.d("DownloadManager", "Starting bulk download for ${games.size} games")
        
        if (settings.downloadDirectory.isEmpty()) {
            Log.e("DownloadManager", "Download directory is empty!")
            showErrorNotification("Download failed: No download directory selected")
            return
        }
        
        if (games.isEmpty()) {
            showErrorNotification("No games to download")
            return
        }
        
        // Check if there's an existing session - if so, add to it; otherwise create new
        val sessionId = currentDownloadSession ?: run {
            val newSessionId = "unified_${System.currentTimeMillis()}"
            currentDownloadSession = newSessionId
            
            // Clear any existing notifications when starting fresh
            clearUnifiedDownloadNotifications()
            newSessionId
        }
        
        Log.d("DownloadManager", "Using unified session for bulk download: $sessionId")
        
        // Get existing tracker or create new one
        val existingTracker = currentDownloadTracker[sessionId]
        val gameNames = games.map { it.name ?: it.fs_name }
        
        if (existingTracker != null) {
            // Add new games to existing session
            existingTracker.gameNames.addAll(gameNames)
            val updatedTracker = existingTracker.copy(
                totalGames = existingTracker.gameNames.size,
                sessionType = DownloadSessionType.BULK // Update to bulk since we're adding multiple
            )
            currentDownloadTracker[sessionId] = updatedTracker
            Log.d("DownloadManager", "Added ${games.size} games to existing unified session. Total games: ${updatedTracker.totalGames}")
        } else {
            // Create new tracker
            currentDownloadTracker[sessionId] = DownloadTracker(
                totalGames = games.size,
                gameNames = gameNames.toMutableList(),
                workIds = mutableListOf(),
                sessionType = DownloadSessionType.BULK
            )
            Log.d("DownloadManager", "Created new unified tracker for bulk download")
        }
        
        // Set up constraints for network connectivity
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        // Create work requests for all games with unique names
        games.forEachIndexed { index, game ->
            val gameName = game.name ?: game.fs_name
            val workRequest = OneTimeWorkRequestBuilder<GameDownloadWorker>()
                .setInputData(
                    workDataOf(
                        "gameId" to game.id,
                        "gameName" to gameName,
                        "platformSlug" to game.platform_fs_slug,
                        "isMulti" to game.multi,
                        "host" to settings.host,
                        "username" to settings.username,
                        "password" to settings.password,
                        "downloadDirectory" to settings.downloadDirectory,
                        "maxConcurrentDownloads" to settings.maxConcurrentDownloads,
                        "isBulkDownload" to true,
                        "bulkSessionId" to sessionId,
                        "totalGames" to (currentDownloadTracker[sessionId]?.totalGames ?: games.size),
                        "gameIndex" to index
                    )
                )
                .setConstraints(constraints)
                .addTag("download_work") // Use consistent tag for concurrency control
                .addTag("unified_download")
                .addTag(sessionId) // Add session ID as tag
                .addTag("game_${game.id}")
                .build()
            
            // Store work ID for tracking
            currentDownloadTracker[sessionId]?.workIds?.add(workRequest.id.toString())
            
            // Use unique work names to ensure all games are enqueued
            val uniqueWorkName = "download_game_${game.id}_${System.currentTimeMillis()}"
            Log.d("DownloadManager", "Enqueuing game ${index + 1}/${games.size}: $gameName (Work ID: ${workRequest.id})")
            
            // Use a queue-based approach to respect maxConcurrentDownloads
            enqueueWithConcurrencyLimit(workRequest, settings.maxConcurrentDownloads)
        }
        
        // Show initial unified download notification
        val totalGames = currentDownloadTracker[sessionId]?.totalGames ?: games.size
        showUnifiedDownloadProgressNotification(
            sessionId = sessionId,
            completed = 0,
            failed = 0,
            inProgress = games.size,
            total = totalGames
        )
        
        // Start monitoring if not already started
        if (!monitoringJobs.containsKey(sessionId)) {
            Log.d("DownloadManager", "Starting monitoring for unified session: $sessionId")
            startMonitoringDownload(sessionId)
        }
        
        Log.d("DownloadManager", "Successfully enqueued ${games.size} download requests with unified session ID: $sessionId")
    }
    
    suspend fun downloadFirmware(firmware: List<Firmware>, settings: AppSettings) {
        Log.d("DownloadManager", "Starting firmware download for ${firmware.size} files")
        
        if (settings.downloadDirectory.isEmpty()) {
            showErrorNotification("Download failed: No download directory selected")
            return
        }
        
        if (firmware.isEmpty()) {
            showErrorNotification("No firmware to download")
            return
        }
        
        // Create or use existing unified session for firmware downloads
        val sessionId = currentDownloadSession ?: run {
            val newSessionId = "firmware_${System.currentTimeMillis()}"
            currentDownloadSession = newSessionId
            
            // Clear any existing notifications when starting fresh
            clearUnifiedDownloadNotifications()
            newSessionId
        }
        
        Log.d("DownloadManager", "Using unified session for firmware download: $sessionId")
        
        // Create tracker for firmware downloads
        val firmwareNames = firmware.map { it.file_name }
        currentDownloadTracker[sessionId] = DownloadTracker(
            totalGames = firmware.size,
            gameNames = firmwareNames.toMutableList(),
            workIds = mutableListOf(),
            sessionType = DownloadSessionType.BULK
        )
        
        // Set up constraints
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        firmware.forEachIndexed { index, fw ->
            val workRequest = OneTimeWorkRequestBuilder<FirmwareDownloadWorker>()
                .setInputData(
                    workDataOf(
                        "firmwareId" to fw.id,
                        "fileName" to fw.file_name,
                        "platformId" to fw.platform_id,
                        "host" to settings.host,
                        "username" to settings.username,
                        "password" to settings.password,
                        "downloadDirectory" to settings.downloadDirectory,
                        "isBulkDownload" to true,
                        "bulkSessionId" to sessionId,
                        "totalFiles" to firmware.size,
                        "fileIndex" to index
                    )
                )
                .setConstraints(constraints)
                .addTag("firmware_download")
                .addTag("unified_download")
                .addTag(sessionId)
                .addTag("firmware_${fw.id}")
                .build()
            
            // Store work ID for tracking
            currentDownloadTracker[sessionId]?.workIds?.add(workRequest.id.toString())
            
            // Use unique names for firmware downloads
            val uniqueWorkName = "download_firmware_${fw.id}_${System.currentTimeMillis()}"
            workManager.enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
        
        // Show initial unified download notification for firmware
        showUnifiedFirmwareDownloadProgressNotification(
            sessionId = sessionId,
            completed = 0,
            failed = 0,
            inProgress = firmware.size,
            total = firmware.size
        )
        
        // Start monitoring if not already started
        if (!monitoringJobs.containsKey(sessionId)) {
            Log.d("DownloadManager", "Starting monitoring for firmware session: $sessionId")
            startMonitoringDownload(sessionId)
        }
        
        Log.d("DownloadManager", "Successfully enqueued ${firmware.size} firmware download requests with session ID: $sessionId")
    }
    
    fun cancelAllDownloads() {
        Log.d("DownloadManager", "Cancelling all downloads")
        workManager.cancelAllWorkByTag("unified_download")
        workManager.cancelAllWorkByTag("firmware_download")
        
        // Stop monitoring jobs and clear trackers
        monitoringJobs.values.forEach { it.cancel() }
        monitoringJobs.clear()
        
        // Clear all download trackers and notifications
        currentDownloadTracker.keys.forEach { sessionId ->
            notificationManager.cancel(getUnifiedNotificationId(sessionId))
        }
        currentDownloadTracker.clear()
        
        // Reset current session
        currentDownloadSession = null
        
        // Clean up individual notifications
        individualNotificationIds.clear()
    }
    
    suspend fun getDownloadStatus(): List<WorkInfo> {
        return workManager.getWorkInfosByTag("unified_download").get()
    }
    
    /**
     * Clear any existing unified download notifications
     */
    private fun clearUnifiedDownloadNotifications() {
        Log.d("DownloadManager", "Clearing existing unified download notifications")
        
        // Cancel all existing download summary notifications
        currentDownloadTracker.keys.forEach { sessionId ->
            notificationManager.cancel(getUnifiedNotificationId(sessionId))
            Log.d("DownloadManager", "Cancelled notification for session: $sessionId")
        }
        
        // Clean up all individual notifications
        individualNotificationIds.values.forEach { notificationIds ->
            notificationIds.forEach { id ->
                notificationManager.cancel(id)
            }
        }
        
        // Clear tracking maps
        individualNotificationIds.clear()
        currentDownloadTracker.clear()
        
        // Stop all existing monitoring jobs
        monitoringJobs.values.forEach { it.cancel() }
        monitoringJobs.clear()
        
        // Reset current session
        currentDownloadSession = null
        
        Log.d("DownloadManager", "Cleared all existing unified download notifications")
    }
    
    /**
     * Show individual download notification that only appears when summary is expanded
     */
    fun showIndividualDownloadNotification(gameName: String, isSuccess: Boolean, sessionId: String? = null) {
        if (sessionId == null) return
        
        // Ensure the summary notification exists first - this is crucial for proper grouping
        ensureSummaryNotificationExists(sessionId)
        
        val title = if (isSuccess) "Downloaded" else "Failed"
        val text = gameName
        val icon = if (isSuccess) {
            android.R.drawable.stat_sys_download_done
        } else {
            android.R.drawable.stat_notify_error
        }
        
        // Create child notification that should be hidden by default
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setGroup(UNIFIED_DOWNLOAD_GROUP) // Same group as summary
            .setGroupSummary(false) // This is NOT a summary notification
            .setSilent(true) // Silent - no sound, vibration, or lights
            .setAutoCancel(false) // Don't auto-cancel so they persist in expanded view
            .setOngoing(false) // Not ongoing
            .setPriority(NotificationCompat.PRIORITY_LOW) // Lower priority than summary to ensure proper ordering
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY) // Only summary notification alerts
            .setLocalOnly(true) // Keep local to device
            .setShowWhen(true) // Show timestamp to help distinguish between downloads
            .setOnlyAlertOnce(true) // Alert only once
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Public visibility
            .setDefaults(0) // No defaults (sound, vibration, lights)
            .build()
        
        val notificationId = gameName.hashCode()
        notificationManager.notify(notificationId, notification)
        
        // Track this notification ID for later cleanup
        individualNotificationIds.getOrPut(sessionId) { mutableListOf() }.add(notificationId)
        
        Log.d("DownloadManager", "Created child notification for: $gameName (success: $isSuccess, ID: $notificationId)")
    }
    
    /**
     * Show individual firmware download notification that only appears when summary is expanded
     */
    fun showIndividualFirmwareDownloadNotification(fileName: String, isSuccess: Boolean, sessionId: String? = null) {
        if (sessionId == null) return
        
        // Ensure the summary notification exists first - this is crucial for proper grouping
        ensureSummaryNotificationExists(sessionId)
        
        val title = if (isSuccess) "Downloaded" else "Failed"
        val text = fileName
        val icon = if (isSuccess) {
            android.R.drawable.stat_sys_download_done
        } else {
            android.R.drawable.stat_notify_error
        }
        
        // Create child notification that should be hidden by default
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setGroup(UNIFIED_DOWNLOAD_GROUP) // Same group as summary
            .setGroupSummary(false) // This is NOT a summary notification
            .setSilent(true) // Silent - no sound, vibration, or lights
            .setAutoCancel(false) // Don't auto-cancel so they persist in expanded view
            .setOngoing(false) // Not ongoing
            .setPriority(NotificationCompat.PRIORITY_LOW) // Lower priority than summary to ensure proper ordering
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY) // Only summary notification alerts
            .setLocalOnly(true) // Keep local to device
            .setShowWhen(true) // Show timestamp to help distinguish between downloads
            .setOnlyAlertOnce(true) // Alert only once
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Public visibility
            .setDefaults(0) // No defaults (sound, vibration, lights)
            .build()
        
        val notificationId = fileName.hashCode()
        notificationManager.notify(notificationId, notification)
        
        // Track this notification ID for later cleanup
        individualNotificationIds.getOrPut(sessionId) { mutableListOf() }.add(notificationId)
        
        Log.d("DownloadManager", "Created child firmware notification for: $fileName (success: $isSuccess, ID: $notificationId)")
    }
    
    /**
     * Ensure the summary notification exists before creating child notifications
     */
    private fun ensureSummaryNotificationExists(sessionId: String) {
        // Check if we have tracker info to create/update summary
        val tracker = currentDownloadTracker[sessionId] ?: return
        
        // Get current work status to update summary if needed
        try {
            val workInfos = workManager.getWorkInfosByTag(sessionId).get()
            val completed = workInfos.count { it.state == WorkInfo.State.SUCCEEDED }
            val failed = workInfos.count { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED }
            val running = workInfos.count { it.state == WorkInfo.State.RUNNING }
            val enqueued = workInfos.count { it.state == WorkInfo.State.ENQUEUED }
            
            if (completed + failed < tracker.totalGames) {
                showUnifiedDownloadProgressNotification(
                    sessionId = sessionId,
                    completed = completed,
                    failed = failed,
                    inProgress = running + enqueued,
                    total = tracker.totalGames
                )
            }
        } catch (e: Exception) {
            Log.w("DownloadManager", "Could not update summary notification", e)
        }
    }
    
    /**
     * Show placeholder notification for downloads that are starting
     */
    private fun showIndividualDownloadPlaceholder(gameName: String, sessionId: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Queued")
            .setContentText(gameName)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setSilent(true) // Don't make noise for placeholder notifications
            .setGroup(UNIFIED_DOWNLOAD_GROUP) // Group with the unified summary
            .setGroupSummary(false) // This is NOT a summary notification
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY) // Only summary makes sound
            .setOngoing(false) // Don't make ongoing since summary shows progress
            .setPriority(NotificationCompat.PRIORITY_LOW) // Lower priority so they don't show individually
            .setLocalOnly(true) // Keep notifications local to this device
            .setAutoCancel(false) // Don't auto-cancel so they stay when expanded
            .setProgress(0, 0, true) // Indeterminate progress
            .build()
        
        // Use a unique notification ID for each game
        val notificationId = gameName.hashCode()
        notificationManager.notify(notificationId, notification)
        
        // Track this notification ID for later cleanup
        individualNotificationIds.getOrPut(sessionId) { mutableListOf() }.add(notificationId)
        
        Log.d("DownloadManager", "Created placeholder notification for: $gameName (ID: $notificationId)")
    }
    
    /**
     * Clean up individual notifications for a completed unified download session
     */
    private fun cleanupIndividualNotifications(sessionId: String) {
        Log.d("DownloadManager", "Starting cleanup for session: $sessionId")
        Log.d("DownloadManager", "Current individualNotificationIds keys: ${individualNotificationIds.keys}")
        
        val notificationIds = individualNotificationIds.remove(sessionId)
        Log.d("DownloadManager", "Found ${notificationIds?.size ?: 0} notification IDs to clean up for session: $sessionId")
        
        notificationIds?.forEach { id ->
            notificationManager.cancel(id)
            Log.d("DownloadManager", "Cleaned up individual notification ID: $id")
        }
        Log.d("DownloadManager", "Cleaned up ${notificationIds?.size ?: 0} individual notifications for session: $sessionId")
    }
    
    private fun showUnifiedFirmwareDownloadProgressNotification(
        sessionId: String,
        completed: Int,
        failed: Int,
        inProgress: Int,
        total: Int
    ) {
        val title = "Downloading Firmware"
        val text = if (failed > 0) {
            "Progress: $completed/$total completed, $failed failed, $inProgress in progress"
        } else {
            "Progress: $completed/$total completed, $inProgress in progress"
        }
        
        Log.d("DownloadManager", "Updating unified firmware notification: $text")
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total, completed, false)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true) // Don't make noise for progress updates
            .setGroup(UNIFIED_DOWNLOAD_GROUP)
            .setGroupSummary(true) // This is the summary notification
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY) // Only this notification alerts
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Higher priority than children to ensure it appears first
            .setAutoCancel(false) // Don't auto-cancel during progress
            .setNumber(inProgress + completed) // Show count badge
            .build()
        
        notificationManager.notify(getUnifiedNotificationId(sessionId), notification)
    }
    
    private fun showUnifiedFirmwareDownloadCompleteNotification(
        sessionId: String,
        completed: Int,
        failed: Int,
        total: Int
    ) {
        val title = when {
            failed == 0 -> "All Firmware Downloads Complete!"
            completed == 0 -> "All Firmware Downloads Failed"
            else -> "Firmware Downloads Complete"
        }
        
        val text = when {
            failed == 0 -> "Successfully downloaded $completed firmware ${if (completed == 1) "file" else "files"}"
            completed == 0 -> "Failed to download $failed firmware ${if (failed == 1) "file" else "files"}"
            else -> "Downloaded $completed firmware ${if (completed == 1) "file" else "files"}, $failed failed"
        }
        
        val icon = if (failed == 0) {
            android.R.drawable.stat_sys_download_done
        } else {
            android.R.drawable.stat_notify_error
        }
        
        Log.d("DownloadManager", "Final unified firmware notification: $title - $text")
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setAutoCancel(true)
            .setOngoing(false) // Not ongoing anymore
            .setGroup(UNIFIED_DOWNLOAD_GROUP)
            .setGroupSummary(true) // This is the summary notification
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY) // Only this notification alerts
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Higher priority than children to ensure it appears first
            .setNumber(total) // Show total count badge
            .build()
        
        notificationManager.notify(getUnifiedNotificationId(sessionId), notification)
    }
    
    
    private fun showDownloadStartedNotification(message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download Started")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setGroup(INDIVIDUAL_DOWNLOAD_GROUP) // Use different group to avoid conflicts
            .build()
        
        notificationManager.notify(STARTED_NOTIFICATION_ID, notification)
    }
    
    private fun showErrorNotification(message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download Error")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(ERROR_NOTIFICATION_ID, notification)
    }
    
    /**
     * Enqueue work requests with proper concurrency control
     */
    private fun enqueueWithConcurrencyLimit(workRequest: OneTimeWorkRequest, maxConcurrentDownloads: Int) {
        Log.d("DownloadManager", "Enqueuing download with max concurrent limit: $maxConcurrentDownloads")
        
        val uniqueName = "download_${workRequest.id}"
        
        if (maxConcurrentDownloads == 1) {
            // Sequential downloads - use a single sequential chain
            Log.d("DownloadManager", "Enqueuing to sequential download chain")
            workManager.beginUniqueWork(
                "sequential_downloads",
                ExistingWorkPolicy.APPEND,
                workRequest
            ).enqueue()
        } else {
            // Limited concurrent downloads - use a simple chain approach
            Log.d("DownloadManager", "Enqueuing with concurrency limit: $maxConcurrentDownloads")
            
            // Use a round-robin approach with fixed chains
            // This ensures we never exceed the concurrency limit
            val chainIndex = System.currentTimeMillis() % maxConcurrentDownloads
            val chainName = "concurrent_chain_$chainIndex"
            
            Log.d("DownloadManager", "Using chain: $chainName (max concurrent: $maxConcurrentDownloads)")
            
            workManager.beginUniqueWork(
                chainName,
                ExistingWorkPolicy.APPEND,
                workRequest
            ).enqueue()
        }
    }
    
    companion object {
        const val CHANNEL_ID = "download_channel"
        const val STARTED_NOTIFICATION_ID = 1001
        const val ERROR_NOTIFICATION_ID = 1002
        const val UNIFIED_DOWNLOAD_GROUP = "unified_downloads"
        const val INDIVIDUAL_DOWNLOAD_GROUP = "individual_downloads"
        
        @Volatile
        private var INSTANCE: DownloadManager? = null
        
        // Temporary workaround to get DownloadManager instance from workers
        fun getInstance(context: Context): DownloadManager? {
            return INSTANCE
        }
        
        fun setInstance(instance: DownloadManager) {
            INSTANCE = instance
        }
    }
}

object ErrorHandler {
    fun getErrorMessage(throwable: Throwable): String {
        return when (throwable) {
            is java.net.ConnectException -> "Cannot connect to RomM server. Check your connection settings."
            is java.net.UnknownHostException -> "Cannot resolve hostname. Check your server address."
            is retrofit2.HttpException -> {
                when (throwable.code()) {
                    401 -> "Authentication failed. Check your username and password."
                    404 -> "Resource not found."
                    500 -> "Server error occurred."
                    else -> "HTTP error: ${throwable.code()}"
                }
            }
            else -> throwable.message ?: "An unknown error occurred"
        }
    }
}

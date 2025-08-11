package com.romm.android.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: RomMApiService,
    private val workManager: WorkManager
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    // Track all download progress - unified system for all download types
    private var globalDownloadSession: GlobalDownloadSession? = null
    private var monitoringJob: Job? = null
    // Track individual download progress
    private val individualDownloadProgress = ConcurrentHashMap<String, Int>()
    private val individualDownloadStatus = ConcurrentHashMap<String, IndividualDownloadStatus>()
    
    data class GlobalDownloadSession(
        val sessionId: String = "global_downloads_${System.currentTimeMillis()}",
        var totalDownloads: Int = 0,
        var completedDownloads: Int = 0,
        var failedDownloads: Int = 0,
        var cancelledDownloads: Int = 0,
        val activeDownloads: MutableSet<String> = mutableSetOf(),
        val allDownloadIds: MutableSet<String> = mutableSetOf()
    )
    
    data class IndividualDownloadStatus(
        val downloadId: String,
        val displayName: String,
        val workId: String,
        var progress: Int = 0,
        var isActive: Boolean = true,
        var isCompleted: Boolean = false,
        var isFailed: Boolean = false,
        var isCancelled: Boolean = false
    )
    
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
    
    private fun startGlobalDownloadMonitoring() {
        // Stop any existing monitoring
        monitoringJob?.cancel()
        
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d("DownloadManager", "Starting global download monitoring")
            
            while (globalDownloadSession != null) {
                try {
                    val session = globalDownloadSession ?: break
                    
                    // Get work info for all downloads in the session
                    val allWorkInfos = session.allDownloadIds.mapNotNull { downloadId ->
                        val status = individualDownloadStatus[downloadId]
                        if (status != null) {
                            try {
                                workManager.getWorkInfoById(UUID.fromString(status.workId)).get()
                            } catch (e: Exception) {
                                Log.w("DownloadManager", "Could not get work info for $downloadId", e)
                                null
                            }
                        } else null
                    }
                    
                    // Update individual download statuses
                    session.allDownloadIds.forEach { downloadId ->
                        val status = individualDownloadStatus[downloadId]
                        val workInfo = allWorkInfos.find { it.id.toString() == status?.workId }
                        
                        if (status != null && workInfo != null) {
                            val wasActive = status.isActive
                            
                            when (workInfo.state) {
                                WorkInfo.State.SUCCEEDED -> {
                                    if (wasActive) {
                                        status.isCompleted = true
                                        status.isActive = false
                                        session.completedDownloads++
                                        session.activeDownloads.remove(downloadId)
                                        // Clear individual notification when completed
                                        clearIndividualDownloadNotification(downloadId)
                                    }
                                }
                                WorkInfo.State.FAILED -> {
                                    if (wasActive) {
                                        status.isFailed = true
                                        status.isActive = false
                                        session.failedDownloads++
                                        session.activeDownloads.remove(downloadId)
                                        // Clear individual notification when failed
                                        clearIndividualDownloadNotification(downloadId)
                                    }
                                }
                                WorkInfo.State.CANCELLED -> {
                                    if (wasActive) {
                                        status.isCancelled = true
                                        status.isActive = false
                                        session.cancelledDownloads++
                                        session.activeDownloads.remove(downloadId)
                                        // Clear individual notification when cancelled
                                        clearIndividualDownloadNotification(downloadId)
                                    }
                                }
                                WorkInfo.State.RUNNING -> {
                                    // Update progress if available
                                    val progress = workInfo.progress.getInt("progress", status.progress)
                                    if (progress != status.progress && progress % 5 == 0) { // Update every 5%
                                        status.progress = progress
                                        updateIndividualDownloadNotification(downloadId, status)
                                    }
                                }
                                else -> { /* ENQUEUED or other states - no action needed */ }
                            }
                        }
                    }
                    
                    // Update summary notification
                    val activeCount = session.activeDownloads.size
                    val totalCompleted = session.completedDownloads + session.failedDownloads + session.cancelledDownloads
                    
                    if (activeCount > 0 || totalCompleted < session.totalDownloads) {
                        // Still in progress
                        showSummaryProgressNotification(session, activeCount)
                    } else {
                        // All downloads finished
                        Log.d("DownloadManager", "All downloads finished: ${session.completedDownloads} completed, ${session.failedDownloads} failed, ${session.cancelledDownloads} cancelled")
                        showSummaryCompleteNotification(session)
                        
                        // Keep the session but mark it as completed for potential new downloads
                        break
                    }
                    
                    // Check every 1 second for responsive progress updates
                    delay(1000)
                    
                } catch (e: Exception) {
                    Log.e("DownloadManager", "Error in global download monitoring", e)
                    delay(2000) // Wait longer on error
                }
            }
            
            Log.d("DownloadManager", "Stopped global download monitoring")
        }
    }
    
    /**
     * Show summary notification with progress bar and cancel button
     */
    private fun showSummaryProgressNotification(session: GlobalDownloadSession, activeCount: Int) {
        val completed = session.completedDownloads + session.failedDownloads + session.cancelledDownloads
        val title = "Downloads"
        val text = "$completed of ${session.totalDownloads} completed, $activeCount active downloads"
        
        Log.d("DownloadManager", "Updating summary notification: $text")
        
        // Create cancel all intent
        val cancelIntent = createCancelAllPendingIntent()
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(session.totalDownloads, completed, false)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(false) // This notification can make sound
            .setGroup(UNIFIED_DOWNLOAD_GROUP)
            .setGroupSummary(true) // This is the summary notification
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setNumber(activeCount) // Show active count as badge
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel All", cancelIntent)
            .build()
        
        notificationManager.notify(SUMMARY_NOTIFICATION_ID, notification)
    }
    
    /**
     * Show summary notification when all downloads are complete
     */
    private fun showSummaryCompleteNotification(session: GlobalDownloadSession) {
        val title = "Downloads Complete"
        val parts = mutableListOf<String>()
        
        if (session.completedDownloads > 0) {
            parts.add("${session.completedDownloads} completed")
        }
        if (session.failedDownloads > 0) {
            parts.add("${session.failedDownloads} failed")
        }
        if (session.cancelledDownloads > 0) {
            parts.add("${session.cancelledDownloads} cancelled")
        }
        
        val text = parts.joinToString(", ")
        val icon = if (session.failedDownloads == 0 && session.cancelledDownloads == 0) {
            android.R.drawable.stat_sys_download_done
        } else {
            android.R.drawable.stat_notify_error
        }
        
        Log.d("DownloadManager", "Final summary notification: $title - $text")
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setAutoCancel(true)
            .setOngoing(false)
            .setGroup(UNIFIED_DOWNLOAD_GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setNumber(session.totalDownloads)
            .build()
        
        notificationManager.notify(SUMMARY_NOTIFICATION_ID, notification)
    }
    
    /**
     * Individual notifications are now handled by foreground service notifications from workers
     */
    private fun showIndividualDownloadNotification(downloadId: String, status: IndividualDownloadStatus) {
        Log.d("DownloadManager", "Individual notification now handled by foreground service for: ${status.displayName} (progress: ${status.progress}%)")
        // Individual notifications are now the foreground service notifications from the workers
        // This eliminates duplicate notifications
    }
    
    /**
     * Update individual download notification progress
     */
    private fun updateIndividualDownloadNotification(downloadId: String, status: IndividualDownloadStatus) {
        if (!status.isActive) return
        
        showIndividualDownloadNotification(downloadId, status)
        Log.d("DownloadManager", "Updated individual notification for: ${status.displayName} (progress: ${status.progress}%)")
    }
    
    /**
     * Clear individual download notification when download finishes
     */
    private fun clearIndividualDownloadNotification(downloadId: String) {
        val notificationId = downloadId.hashCode()
        notificationManager.cancel(notificationId)
        Log.d("DownloadManager", "Cleared individual notification for download: $downloadId (ID: $notificationId)")
    }
    
    /**
     * Create a PendingIntent that cancels all downloads when clicked
     */
    private fun createCancelAllPendingIntent(): PendingIntent {
        val intent = Intent().apply {
            action = "CANCEL_ALL_DOWNLOADS"
        }
        
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
     * Get or create global download session. This handles ALL downloads (single, bulk, firmware).
     */
    private fun getOrCreateGlobalSession(): GlobalDownloadSession {
        val existingSession = globalDownloadSession
        
        if (existingSession != null) {
            // Check if the current session is completed and we're starting new downloads
            val allCompleted = existingSession.activeDownloads.isEmpty() && 
                              (existingSession.completedDownloads + existingSession.failedDownloads + existingSession.cancelledDownloads) >= existingSession.totalDownloads
            
            if (allCompleted && existingSession.totalDownloads > 0) {
                Log.d("DownloadManager", "Previous session completed, transitioning to new downloads")
                // Reset the session for new downloads while keeping the completed status visible
                existingSession.totalDownloads = 0
                existingSession.completedDownloads = 0
                existingSession.failedDownloads = 0
                existingSession.cancelledDownloads = 0
                existingSession.activeDownloads.clear()
                existingSession.allDownloadIds.clear()
                
                // Clear individual download tracking
                individualDownloadStatus.clear()
                individualDownloadProgress.clear()
            }
            
            return existingSession
        } else {
            // Create new global session
            val newSession = GlobalDownloadSession()
            globalDownloadSession = newSession
            Log.d("DownloadManager", "Created new global download session: ${newSession.sessionId}")
            return newSession
        }
    }
    
    /**
     * Add a download to the global session
     */
    private fun addDownloadToSession(gameName: String, workId: String): String {
        val session = getOrCreateGlobalSession()
        val downloadId = "download_${System.currentTimeMillis()}_${gameName.hashCode()}"
        
        // Add to session
        session.totalDownloads++
        session.activeDownloads.add(downloadId)
        session.allDownloadIds.add(downloadId)
        
        // Track individual download
        individualDownloadStatus[downloadId] = IndividualDownloadStatus(
            downloadId = downloadId,
            displayName = gameName,
            workId = workId,
            progress = 0,
            isActive = true
        )
        
        Log.d("DownloadManager", "Added download '$gameName' to session. Total downloads: ${session.totalDownloads}")
        
        // Start monitoring if this is the first download
        if (session.totalDownloads == 1) {
            startGlobalDownloadMonitoring()
        }
        
        // Show initial individual notification
        showIndividualDownloadNotification(downloadId, individualDownloadStatus[downloadId]!!)
        
        return downloadId
    }
    
    /**
     * Legacy method signature - redirect to new unified session
     */
    private fun getOrCreateUnifiedSession(downloadType: String, isBulk: Boolean = false): String {
        // This is now handled by the global session
        val session = getOrCreateGlobalSession()
        return session.sessionId
    }
    
    
    
    suspend fun downloadGame(game: Game, settings: AppSettings) {
        Log.d("DownloadManager", "Starting individual download for game: ${game.name ?: game.fs_name}")
        
        if (settings.downloadDirectory.isEmpty()) {
            Log.e("DownloadManager", "Download directory is empty!")
            showErrorNotification("Download failed: No download directory selected")
            return
        }
        
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
                    "isBulkDownload" to false
                )
            )
            .addTag("download_work")
            .addTag("global_download")
            .addTag("game_${game.id}")
            .build()
        
        // Add to global session and get download ID
        val downloadId = addDownloadToSession(gameName, workRequest.id.toString())
        
        Log.d("DownloadManager", "Enqueuing individual download: $gameName (Work ID: ${workRequest.id}, Download ID: $downloadId)")
        
        // Enqueue with concurrency control
        enqueueWithConcurrencyLimit(workRequest, settings.maxConcurrentDownloads)
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
        
        // Set up constraints for network connectivity
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        // Create work requests for all games
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
                        "totalGames" to games.size,
                        "gameIndex" to index
                    )
                )
                .setConstraints(constraints)
                .addTag("download_work")
                .addTag("global_download")
                .addTag("game_${game.id}")
                .build()
            
            // Add to global session
            val downloadId = addDownloadToSession(gameName, workRequest.id.toString())
            
            Log.d("DownloadManager", "Enqueuing game ${index + 1}/${games.size}: $gameName (Work ID: ${workRequest.id}, Download ID: $downloadId)")
            
            // Use a queue-based approach to respect maxConcurrentDownloads
            enqueueWithConcurrencyLimit(workRequest, settings.maxConcurrentDownloads)
        }
        
        Log.d("DownloadManager", "Successfully enqueued ${games.size} download requests")
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
                        "totalFiles" to firmware.size,
                        "fileIndex" to index
                    )
                )
                .setConstraints(constraints)
                .addTag("firmware_download")
                .addTag("global_download")
                .addTag("firmware_${fw.id}")
                .build()
            
            // Add to global session
            val downloadId = addDownloadToSession("Firmware: ${fw.file_name}", workRequest.id.toString())
            
            Log.d("DownloadManager", "Enqueuing firmware ${index + 1}/${firmware.size}: ${fw.file_name} (Work ID: ${workRequest.id}, Download ID: $downloadId)")
            
            // Use unique names for firmware downloads
            val uniqueWorkName = "download_firmware_${fw.id}_${System.currentTimeMillis()}"
            workManager.enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
        
        Log.d("DownloadManager", "Successfully enqueued ${firmware.size} firmware download requests")
    }
    
    fun cancelAllDownloads() {
        Log.d("DownloadManager", "Cancelling all downloads")
        workManager.cancelAllWorkByTag("global_download")
        workManager.cancelAllWorkByTag("firmware_download")
        
        // Stop monitoring job
        monitoringJob?.cancel()
        monitoringJob = null
        
        // Clear session and notifications
        globalDownloadSession?.let { session ->
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
            
            // Individual foreground service notifications will be automatically 
            // cleared when the workers are cancelled
        }
        
        // Reset session and tracking
        globalDownloadSession = null
        individualDownloadStatus.clear()
        individualDownloadProgress.clear()
    }
    
    suspend fun getDownloadStatus(): List<WorkInfo> {
        return workManager.getWorkInfosByTag("global_download").get()
    }
    
    /**
     * Clear any existing unified download notifications
     */
    private fun clearUnifiedDownloadNotifications() {
        Log.d("DownloadManager", "Clearing existing unified download notifications")
        
        // Cancel summary notification
        notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        
        // Individual foreground service notifications will be automatically 
        // cleared when downloads complete or are cancelled
        
        // Stop monitoring job
        monitoringJob?.cancel()
        monitoringJob = null
        
        // Clear tracking
        globalDownloadSession = null
        individualDownloadStatus.clear()
        individualDownloadProgress.clear()
        
        Log.d("DownloadManager", "Cleared all existing unified download notifications")
    }
    
    /**
     * Legacy methods - these are called by the worker classes but now handled by the global session
     * The workers should eventually be updated to use progress callbacks instead
     */
    fun showIndividualDownloadNotification(gameName: String, isSuccess: Boolean, sessionId: String? = null) {
        Log.d("DownloadManager", "Legacy individual notification call for: $gameName (success: $isSuccess) - handled by global session")
        // This is now handled by the global monitoring system
    }
    
    fun showIndividualFirmwareDownloadNotification(fileName: String, isSuccess: Boolean, sessionId: String? = null) {
        Log.d("DownloadManager", "Legacy firmware notification call for: $fileName (success: $isSuccess) - handled by global session")
        // This is now handled by the global monitoring system
    }
    
    /**
     * Legacy method - now handled by global monitoring
     */
    private fun ensureSummaryNotificationExists(sessionId: String) {
        Log.d("DownloadManager", "Legacy ensureSummaryNotificationExists call - handled by global monitoring")
        // This is now handled by the global monitoring system
    }
    
    /**
     * Legacy method - now handled by global monitoring
     */
    private fun ensureGroupSummaryNotificationExists(sessionId: String) {
        Log.d("DownloadManager", "Legacy ensureGroupSummaryNotificationExists call - handled by global monitoring")
        // This is now handled by the global monitoring system
    }
    
    /**
     * Legacy placeholder method - now handled by global monitoring
     */
    private fun showIndividualDownloadPlaceholder(gameName: String, sessionId: String) {
        Log.d("DownloadManager", "Legacy placeholder notification call for: $gameName - handled by global monitoring")
        // This is now handled by the global monitoring system
    }
    
    /**
     * Clean up individual notifications for a completed unified download session
     */
    private fun cleanupIndividualNotifications(sessionId: String) {
        Log.d("DownloadManager", "Starting cleanup for session: $sessionId")
        
        // Clean up all individual notifications for the global session
        globalDownloadSession?.allDownloadIds?.forEach { downloadId ->
            val notificationId = downloadId.hashCode()
            notificationManager.cancel(notificationId)
            Log.d("DownloadManager", "Cleaned up individual notification ID: $notificationId")
        }
        
        Log.d("DownloadManager", "Cleaned up individual notifications for session: $sessionId")
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
        const val UNIFIED_DOWNLOAD_GROUP = "romm_download_group"
        const val SUMMARY_NOTIFICATION_ID = 999999
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

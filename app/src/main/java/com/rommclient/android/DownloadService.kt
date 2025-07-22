package com.rommclient.android

import com.rommclient.android.PlatformMappings
import com.rommclient.android.RommDatabaseHelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URL

class DownloadService : Service() {
    val lastUpdateTimes = mutableMapOf<Int, Long>()
    // Moved from top of file; used for notification throttling
    private var lastGroupUpdateTime = 0L
    companion object {
        const val CHANNEL_ID = "download_channel"
        const val GROUP_KEY_DOWNLOADS = "romm_downloads"
        const val SUMMARY_NOTIFICATION_ID = 1000
        private const val NOTIFICATION_ID_PREPARE = 999
        @JvmStatic var activeDownloads = 0
        @JvmStatic var totalDownloads = 0
        private var isForegroundStarted = false
    }
    // Track total downloads started
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    // --- Platform folder DocumentFile cache and lock ---
    private val platformFolderCache = mutableMapOf<String, DocumentFile?>()
    private val folderLock = Any()
    private lateinit var semaphore: Semaphore

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Show initial placeholder notification IMMEDIATELY
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Preparing downloads...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(NOTIFICATION_ID_PREPARE, notification)

        val fileUrl = intent?.getStringExtra("file_url") ?: return START_NOT_STICKY
        val fileName = intent.getStringExtra("file_name") ?: "downloaded_file"
        val platformSlug = intent.getStringExtra("platform_slug") ?: "unknown"

        val downloadId = fileName.hashCode()

        // Removed initial startForeground call with "Starting download..." notification
        if (!isForegroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "Download Progress", NotificationManager.IMPORTANCE_LOW)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }

            val initialNotification = NotificationCompat.Builder(this@DownloadService, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Preparing download…")
                .setContentText("Initializing download service")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setGroup(GROUP_KEY_DOWNLOADS)
                .build()
            startForeground(SUMMARY_NOTIFICATION_ID, initialNotification)
            isForegroundStarted = true
        }
        totalDownloads++
        activeDownloads++
        val sharedPrefs = getSharedPreferences("romm_prefs", Context.MODE_PRIVATE)
        val maxConcurrentDownloads = sharedPrefs.getInt("max_concurrent_downloads", 3)
        if (!::semaphore.isInitialized) {
            semaphore = Semaphore(maxConcurrentDownloads)
        }
        scope.launch {
            try {
                semaphore.withPermit {
                val baseDir = sharedPrefs.getString(
                    "download_directory",
                    getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
                )
                Log.d("DownloadService", "baseDir = $baseDir")
                val platformFolder = PlatformMappings.esDeFolderMap[platformSlug] ?: platformSlug

                // --- Begin new foreground download with progress notification ---
                val isContentUri = baseDir?.startsWith("content://") == true

                val notificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val CHANNEL_ID = "download_channel"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "Download Progress",
                        NotificationManager.IMPORTANCE_LOW
                    )
                    notificationManager.createNotificationChannel(channel)
                }
                // No per-download startForeground, handled by group summary

                try {
                    val urlConnection = URL(fileUrl).openConnection()
                    val contentLength = urlConnection.contentLengthLong
                    val input = urlConnection.getInputStream()
                    val buffer = ByteArray(8 * 1024)
                    var bytesCopied = 0
                    var read: Int

                    val startTime = System.currentTimeMillis()
                    var lastUpdateTime = startTime
                    var lastBytesCopied = 0

                    if (isContentUri) {
                        val treeUri = Uri.parse(baseDir)
                        val root = DocumentFile.fromTreeUri(this@DownloadService, treeUri)
                        val platformFolderDoc = synchronized(folderLock) {
                            platformFolderCache[platformFolder] ?: run {
                                val existing = root?.findFile(platformFolder)
                                val created = existing ?: root?.createDirectory(platformFolder)
                                platformFolderCache[platformFolder] = created
                                created
                            }
                        }

                        val contentType = urlConnection.contentType?.lowercase() ?: ""
                        val isZipFile = contentType.contains("zip") || fileName.lowercase().endsWith(".zip")
                        val romFile = if (isZipFile) {
                            platformFolderDoc?.createFile("application/zip", fileName)
                        } else {
                            platformFolderDoc?.findFile(fileName)?.let {
                                it.delete()
                                null
                            } ?: platformFolderDoc?.createFile("application/octet-stream", fileName)
                        }

                        romFile?.let {
                            contentResolver.openOutputStream(it.uri)?.use { output ->
                                input.use { inp ->
                                    while (inp.read(buffer).also { read = it } >= 0) {
                                        output.write(buffer, 0, read)
                                        bytesCopied += read
                                        if (contentLength > 0) {
                                            val safeProgress =
                                                ((bytesCopied * 100L) / contentLength).coerceIn(
                                                    0,
                                                    100
                                                ).toInt()
                                            val currentTime = System.currentTimeMillis()
                                            if (currentTime - lastUpdateTime >= 1000) { // update every second
                                                val elapsedTime = (currentTime - startTime) / 1000.0
                                                val speedKBps = (bytesCopied / 1024.0) / elapsedTime
                                                val speedStr = if (speedKBps >= 1000) {
                                                    String.format("%.2f MB/s", speedKBps / 1024)
                                                } else {
                                                    String.format("%.1f KB/s", speedKBps)
                                                }

                                                val remainingBytes = contentLength - bytesCopied
                                                val estimatedTime =
                                                    if (speedKBps > 0) (remainingBytes / 1024.0) / speedKBps else -1.0

                                                val etaStr = if (estimatedTime >= 0) {
                                                    val minutes = (estimatedTime / 60).toInt()
                                                    val seconds = (estimatedTime % 60).toInt()
                                                    String.format(
                                                        "ETA: %02d:%02d",
                                                        minutes,
                                                        seconds
                                                    )
                                                } else ""

                                                val statusText = "$speedStr $etaStr".trim()

                                                Log.d(
                                                    "DownloadService",
                                                    "Updating progress: $safeProgress%, $statusText"
                                                )
                                                showPerDownloadNotification(
                                                    this@DownloadService,
                                                    fileName,
                                                    safeProgress,
                                                    downloadId,
                                                    statusText
                                                )

                                                lastUpdateTime = currentTime
                                                lastBytesCopied = bytesCopied
                                            }
                                        }
                                    }
                                }
                            }
                            Log.d("DownloadService", "Downloaded to SAF: ${romFile.uri}")
                            RommDatabaseHelper(this@DownloadService).insertDownload(
                                platformSlug,
                                fileName
                            )
                        }
                        // --- Begin SAF zip extraction logic ---
                        if (isZipFile && romFile != null) {
                            try {
                                val extractionFolder = platformFolderDoc?.createDirectory(fileName.removeSuffix(".zip"))
                                if (extractionFolder == null) {
                                    Log.e("DownloadService", "Failed to create SAF extraction folder for $fileName")
                                    return@launch
                                }
                                // Open the zip file as a stream
                                val zipStream = java.util.zip.ZipInputStream(contentResolver.openInputStream(romFile.uri))
                                if (zipStream == null) {
                                    Log.e("DownloadService", "Failed to open zip input stream for $fileName")
                                    return@launch
                                }
                                var index = 0
                                var entry = zipStream.nextEntry
                                while (entry != null) {
                                    val extractedFile = extractionFolder.createFile("application/octet-stream", entry.name)
                                    if (extractedFile != null) {
                                        contentResolver.openOutputStream(extractedFile.uri)?.use { out ->
                                            zipStream.copyTo(out)
                                        }
                                    }
                                    zipStream.closeEntry()
                                    index++
                                    showPerDownloadNotification(
                                        this@DownloadService,
                                        "Unpacking $fileName",
                                        index,
                                        downloadId,
                                        "Unzipping file #$index"
                                    )
                                    entry = zipStream.nextEntry
                                }
                                zipStream.close()
                                romFile.delete()
                            } catch (e: Exception) {
                                Log.e("DownloadService", "Failed to unzip to SAF: ${e.message}", e)
                            }
                        }
                        // --- End SAF zip extraction logic ---
                    } else {
                        // --- Begin enhanced zip-handling logic ---
                        val contentType = urlConnection.contentType?.lowercase() ?: ""
                        val isZipFile = contentType.contains("zip") || fileName.lowercase().endsWith(".zip")
                        val targetDir = File(baseDir, platformFolder)
                        val finalDir = if (isZipFile) File(targetDir, fileName.removeSuffix(".zip")) else targetDir
                        val targetFile = if (isZipFile) File(targetDir, fileName) else File(finalDir, fileName)
                        finalDir.mkdirs()

                        targetFile.outputStream().use { output ->
                            input.use { inp ->
                                while (inp.read(buffer).also { read = it } >= 0) {
                                    output.write(buffer, 0, read)
                                    bytesCopied += read
                                    if (contentLength > 0) {
                                        val safeProgress =
                                            ((bytesCopied * 100L) / contentLength).coerceIn(0, 100)
                                                .toInt()
                                        val currentTime = System.currentTimeMillis()
                                        if (currentTime - lastUpdateTime >= 1000) { // update every second
                                            val elapsedTime = (currentTime - startTime) / 1000.0
                                            val speedKBps = (bytesCopied / 1024.0) / elapsedTime
                                            val speedStr = if (speedKBps >= 1000) {
                                                String.format("%.2f MB/s", speedKBps / 1024)
                                            } else {
                                                String.format("%.1f KB/s", speedKBps)
                                            }

                                            val remainingBytes = contentLength - bytesCopied
                                            val estimatedTime =
                                                if (speedKBps > 0) (remainingBytes / 1024.0) / speedKBps else -1.0

                                            val etaStr = if (estimatedTime >= 0) {
                                                val minutes = (estimatedTime / 60).toInt()
                                                val seconds = (estimatedTime % 60).toInt()
                                                String.format("ETA: %02d:%02d", minutes, seconds)
                                            } else ""

                                            val statusText = "$speedStr $etaStr".trim()

                                            Log.d(
                                                "DownloadService",
                                                "Updating progress: $safeProgress%, $statusText"
                                            )
                                            showPerDownloadNotification(
                                                this@DownloadService,
                                                fileName,
                                                safeProgress,
                                                downloadId,
                                                statusText
                                            )

                                            lastUpdateTime = currentTime
                                            lastBytesCopied = bytesCopied
                                        }
                                    }
                                }
                            }
                        }

                        if (isZipFile) {
                            try {
                                // Extract to folder named after zip file (without .zip)
                                val zipFolderName = fileName.removeSuffix(".zip")
                                val extractionDir = File(targetDir, zipFolderName)
                                extractionDir.mkdirs()
                                java.util.zip.ZipFile(targetFile).use { zip ->
                                    val entries = zip.entries()
                                    var index = 0
                                    while (entries.hasMoreElements()) {
                                        val entry = entries.nextElement()
                                        val outFile = File(extractionDir, entry.name)
                                        if (entry.isDirectory) {
                                            outFile.mkdirs()
                                        } else {
                                            outFile.parentFile?.mkdirs()
                                            zip.getInputStream(entry).use { input ->
                                                outFile.outputStream().use { output ->
                                                    input.copyTo(output)
                                                }
                                            }
                                        }
                                        index++
                                        showPerDownloadNotification(
                                            this@DownloadService,
                                            "Unpacking $fileName",
                                            index,
                                            downloadId,
                                            "Unzipping file #$index"
                                        )
                                    }
                                }
                                targetFile.delete()
                            } catch (e: Exception) {
                                Log.e("DownloadService", "Failed to unzip ${targetFile.name}: ${e.message}", e)
                            }
                        }
                        Log.d("DownloadService", "Downloaded to ${targetFile.absolutePath}")
                        RommDatabaseHelper(this@DownloadService).insertDownload(
                            platformSlug,
                            fileName
                        )
                    }

                    Log.d("DownloadService", "Download complete")
                    cancelPerDownloadNotification(this@DownloadService, downloadId)
                    // Optionally stop foreground if this is the last download
                } catch (e: Exception) {
                    cancelPerDownloadNotification(this@DownloadService, downloadId)
                    // Optionally stop foreground if this is the last download
                    Log.e("DownloadService", "Download error: ${e.message}", e)
                } finally {
                    stopSelf()
                    activeDownloads--
                    showGroupSummaryNotification(this@DownloadService, totalDownloads - activeDownloads + 1, totalDownloads)
                    if (activeDownloads == 0) {
                        cancelSummaryNotification(this@DownloadService)
                        totalDownloads = 0
                    }
                }
            }
                // --- End new download logic ---
            } catch (e: Exception) {
                Log.e("DownloadService", "Download failed", e)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    // private fun createNotification(content: String): Notification {
    //     val channelId = "download_channel"
    //     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    //         val channel = NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_LOW)
    //         getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    //     }
    //
    //     return NotificationCompat.Builder(this, channelId)
    //         .setContentTitle("ROMM Download")
    //         .setContentText(content)
    //         .setSmallIcon(android.R.drawable.stat_sys_download)
    //         .build()
    // }

    override fun onBind(intent: Intent?): IBinder? = null
}

private fun showPerDownloadNotification(
    context: Context,
    gameName: String,
    progress: Int,
    notificationId: Int,
    statusText: String
) {
    // Throttle notification updates to at most once per second per notificationId
    val service = context as? DownloadService
    if (service != null) {
        val now = System.currentTimeMillis()
        val lastUpdate = service.lastUpdateTimes[notificationId] ?: 0
        if (now - lastUpdate < 1000) return // Skip if called too quickly
        service.lastUpdateTimes[notificationId] = now
    }
    // Ensure group summary is posted before individual notification, but throttle updates
    maybeUpdateGroupSummaryNotification(context)

    val builder = NotificationCompat.Builder(context, DownloadService.CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Downloading $gameName")
        .setContentText(statusText)
        .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
        .setProgress(100, progress, false)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setGroup(DownloadService.GROUP_KEY_DOWNLOADS)

    val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(notificationId, builder.build())
}

private var lastGroupUpdateTime = 0L
private fun maybeUpdateGroupSummaryNotification(context: Context) {
    val currentTime = System.currentTimeMillis()
    if (currentTime - lastGroupUpdateTime >= 5000) {
        showGroupSummaryNotification(
            context,
            DownloadService.totalDownloads - DownloadService.activeDownloads + 1,
            DownloadService.totalDownloads
        )
        lastGroupUpdateTime = currentTime
    }
}

private fun showGroupSummaryNotification(context: Context, active: Int, total: Int) {
    val builder = NotificationCompat.Builder(context, DownloadService.CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Downloads in progress")
        .setStyle(
            NotificationCompat.InboxStyle()
                .addLine("Tap to view downloads")
                .setSummaryText("Downloading $active of $total")
        )
        .setOnlyAlertOnce(true)
        .setGroup(DownloadService.GROUP_KEY_DOWNLOADS)
        .setGroupSummary(true)

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(DownloadService.SUMMARY_NOTIFICATION_ID, builder.build())
}

private fun cancelPerDownloadNotification(context: Context, notificationId: Int) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.cancel(notificationId)
}

private fun cancelSummaryNotification(context: Context) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.cancel(DownloadService.SUMMARY_NOTIFICATION_ID)
}
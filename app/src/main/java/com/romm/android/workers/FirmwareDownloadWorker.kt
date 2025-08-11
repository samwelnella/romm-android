package com.romm.android.workers

import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import androidx.hilt.work.HiltWorker
import com.romm.android.network.RomMApiService
import com.romm.android.utils.DownloadManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.BufferedOutputStream
import java.io.IOException

@HiltWorker
class FirmwareDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val apiService: RomMApiService
) : CoroutineWorker(context, workerParams) {
    
    init {
        android.util.Log.d("FirmwareDownloadWorker", "Worker created successfully with API service: ${apiService::class.java.simpleName}")
    }
    
    override suspend fun doWork(): Result {
        return try {
            val firmwareId = inputData.getInt("firmwareId", -1)
            val fileName = inputData.getString("fileName") ?: ""
            val platformId = inputData.getInt("platformId", -1)
            val downloadDirectoryUri = inputData.getString("downloadDirectory") ?: ""
            val isBulkDownload = inputData.getBoolean("isBulkDownload", false)
            val bulkSessionId = inputData.getString("bulkSessionId")
            val totalFiles = inputData.getInt("totalFiles", 1)
            val fileIndex = inputData.getInt("fileIndex", 0)
            
            android.util.Log.d("FirmwareDownloadWorker", "=== FIRMWARE WORKER STARTED ===")
            android.util.Log.d("FirmwareDownloadWorker", "Worker ID: ${this.id}")
            android.util.Log.d("FirmwareDownloadWorker", "Firmware ID: $firmwareId, Name: $fileName")
            android.util.Log.d("FirmwareDownloadWorker", "Bulk download: $isBulkDownload (${fileIndex + 1}/$totalFiles)")
            android.util.Log.d("FirmwareDownloadWorker", "Download URI: $downloadDirectoryUri")
            
            if (downloadDirectoryUri.isEmpty()) {
                android.util.Log.e("FirmwareDownloadWorker", "Download directory URI is empty")
                return Result.failure()
            }
            
            // Set foreground service for reliable downloads
            setForeground(createForegroundInfo("Firmware: $fileName", 0))
            
            // Get the base directory DocumentFile
            val baseDir = DocumentFile.fromTreeUri(applicationContext, Uri.parse(downloadDirectoryUri))
            if (baseDir == null) {
                android.util.Log.e("FirmwareDownloadWorker", "Could not access base directory from URI: $downloadDirectoryUri")
                return Result.failure()
            }
            
            // Get or create firmware directory with retry logic
            val firmwareDir = getOrCreateFirmwareDirectory(baseDir)
            if (firmwareDir == null) {
                android.util.Log.e("FirmwareDownloadWorker", "Could not create firmware directory")
                return Result.failure()
            }
            
            // Download the firmware
            val response = apiService.downloadFirmware(firmwareId, fileName)
            
            if (response.isSuccessful) {
                response.body()?.let { body ->
                    val outputFile = createOrReplaceFile(firmwareDir, fileName)
                        ?: return@let
                    
                    downloadFileOptimized(body, outputFile, "Firmware: $fileName") { progress ->
                        setProgress(workDataOf("progress" to progress))
                    }
                }
                
                android.util.Log.d("FirmwareDownloadWorker", "Download completed successfully for: $fileName")
                
                // Success notifications now handled by DownloadManager global system
                
                android.util.Log.d("FirmwareDownloadWorker", "=== FIRMWARE WORKER SUCCESS === File: $fileName")
                Result.success()
            } else {
                android.util.Log.e("FirmwareDownloadWorker", "Download failed with response code: ${response.code()}")
                // Error notifications now handled by DownloadManager global system
                Result.failure()
            }
        } catch (e: OutOfMemoryError) {
            val catchFileName = inputData.getString("fileName") ?: ""
            android.util.Log.e("FirmwareDownloadWorker", "=== FIRMWARE WORKER FAILURE (OOM) === File: $catchFileName", e)
            // Error notifications now handled by DownloadManager global system
            System.gc() // Force garbage collection
            Result.failure()
        } catch (e: Exception) {
            val catchFileName = inputData.getString("fileName") ?: ""
            android.util.Log.e("FirmwareDownloadWorker", "=== FIRMWARE WORKER FAILURE (Exception) === File: $catchFileName", e)
            // Error notifications now handled by DownloadManager global system
            Result.failure()
        }
    }
    
    /**
     * Simplified firmware directory creation that handles race conditions gracefully
     */
    private fun getOrCreateFirmwareDirectory(baseDir: DocumentFile): DocumentFile? {
        // Try to find any existing firmware directory first
        val existingDir = findAnyFirmwareDirectory(baseDir)
        if (existingDir != null) {
            android.util.Log.d("FirmwareDownloadWorker", "Using existing firmware directory: ${existingDir.name}")
            return existingDir
        }
        
        // No existing directory found, try to create one
        android.util.Log.d("FirmwareDownloadWorker", "Creating firmware directory")
        val newDir = baseDir.createDirectory("firmware")
        
        if (newDir != null) {
            android.util.Log.d("FirmwareDownloadWorker", "Successfully created firmware directory: ${newDir.name}")
            return newDir
        }
        
        // Creation failed, maybe another worker just created it - try finding it again
        android.util.Log.d("FirmwareDownloadWorker", "Directory creation failed, checking if another worker created it")
        val retryDir = findAnyFirmwareDirectory(baseDir)
        if (retryDir != null) {
            android.util.Log.d("FirmwareDownloadWorker", "Found directory created by another worker: ${retryDir.name}")
            return retryDir
        }
        
        android.util.Log.e("FirmwareDownloadWorker", "Failed to create or find firmware directory")
        return null
    }
    
    /**
     * Find any existing firmware directory (including numbered variants)
     */
    private fun findAnyFirmwareDirectory(baseDir: DocumentFile): DocumentFile? {
        val files = try {
            baseDir.listFiles()
        } catch (e: Exception) {
            android.util.Log.e("FirmwareDownloadWorker", "Error listing directory files", e)
            emptyArray()
        }
        
        // First pass: look for exact match
        for (file in files) {
            if (file.isDirectory && file.name == "firmware") {
                android.util.Log.d("FirmwareDownloadWorker", "Found exact match firmware directory: ${file.name}")
                return file
            }
        }
        
        // Second pass: look for numbered variants
        for (file in files) {
            if (file.isDirectory) {
                val name = file.name ?: continue
                // Match pattern like "firmware (1)", "firmware (2)", etc.
                val regex = Regex("^firmware\\s*\\(\\d+\\)$")
                if (regex.matches(name)) {
                    android.util.Log.d("FirmwareDownloadWorker", "Found numbered firmware directory: $name")
                    return file
                }
            }
        }
        
        return null
    }
    
    /**
     * Create a file, overwriting if it already exists
     */
    private fun createOrReplaceFile(directory: DocumentFile, fileName: String): DocumentFile? {
        // Check for existing file with the same name
        val existingFile = directory.findFile(fileName)
        if (existingFile != null && existingFile.isFile) {
            android.util.Log.d("FirmwareDownloadWorker", "Deleting existing file: ${existingFile.name}")
            existingFile.delete()
        }
        
        // Also check for files with (1), (2), etc. suffixes and delete them
        val fileNameWithoutExt = fileName.substringBeforeLast(".")
        val fileExtension = fileName.substringAfterLast(".", "")
        
        // Look for files like "filename (1).ext", "filename (2).ext", etc.
        directory.listFiles().forEach { file ->
            if (file.isFile) {
                val name = file.name ?: return@forEach
                val nameWithoutExt = name.substringBeforeLast(".")
                
                // Check if it matches the pattern "originalname (n)"
                val regex = Regex("^${Regex.escape(fileNameWithoutExt)}\\s*\\(\\d+\\)$")
                if (regex.matches(nameWithoutExt)) {
                    android.util.Log.d("FirmwareDownloadWorker", "Deleting duplicate file: ${file.name}")
                    file.delete()
                }
            }
        }
        
        // Create new file
        return directory.createFile("application/octet-stream", fileName)
    }
    
    /**
     * Create foreground info for Android foreground service downloads
     * This ensures downloads continue even when app is backgrounded
     */
    private suspend fun createForegroundInfo(title: String, progress: Int): ForegroundInfo {
        val displayTitle = if (progress > 0) "$title ($progress%)" else "Starting: $title"
        
        val notification = NotificationCompat.Builder(applicationContext, DownloadManager.CHANNEL_ID)
            .setContentTitle(displayTitle)
            .setContentText("Downloading...")
            .setProgress(100, progress, progress == 0)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Normal priority as individual notification
            .setShowWhen(false)
            .setLocalOnly(true)
            .build()
        
        // Use a consistent foreground notification ID for this worker instance
        return ForegroundInfo(this.hashCode(), notification)
    }
    
    /**
     * Optimized file download with memory management
     */
    private suspend fun downloadFileOptimized(
        body: ResponseBody,
        outputFile: DocumentFile,
        title: String,
        onProgress: suspend (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val contentLength = body.contentLength()
        var downloadedBytes = 0L
        var lastProgressUpdate = 0
        
        val outputStream = applicationContext.contentResolver.openOutputStream(outputFile.uri)
            ?: throw IOException("Could not open output stream")
        
        try {
            body.byteStream().use { input ->
                BufferedOutputStream(outputStream, 32768).use { output ->
                    val buffer = ByteArray(4096) // Smaller buffer to reduce memory usage
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        if (contentLength > 0) {
                            val progress = (downloadedBytes * 100 / contentLength).toInt()
                            
                            // Only update progress every 5% to reduce overhead
                            if (progress >= lastProgressUpdate + 5) {
                                lastProgressUpdate = progress
                                onProgress(progress)
                                // Update foreground service notification with progress
                                setForeground(createForegroundInfo(title, progress))
                                
                                // Force flush buffer periodically
                                output.flush()
                            }
                        }
                    }
                    
                    // Final flush
                    output.flush()
                }
            }
        } finally {
            outputStream.close()
        }
    }
    
    // Old notification methods removed - now handled by DownloadManager global system
    
}

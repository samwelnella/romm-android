package com.romm.android.workers

import android.content.Context
import android.net.Uri
import android.util.Log
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
import java.io.*
import java.util.zip.ZipInputStream

@HiltWorker
class GameDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val apiService: RomMApiService,
    private val downloadManager: DownloadManager
) : CoroutineWorker(context, workerParams) {
    
    init {
        Log.d("GameDownloadWorker", "Worker created successfully with API service: ${apiService::class.java.simpleName}")
    }
    
    override suspend fun doWork(): Result {
        val gameId = inputData.getInt("gameId", -1)
        val gameName = inputData.getString("gameName") ?: "Unknown Game"
        val platformSlug = inputData.getString("platformSlug") ?: ""
        val isMulti = inputData.getBoolean("isMulti", false)
        val downloadDirectoryUri = inputData.getString("downloadDirectory") ?: ""
        val isBulkDownload = inputData.getBoolean("isBulkDownload", false)
        val bulkSessionId = inputData.getString("bulkSessionId")
        val totalGames = inputData.getInt("totalGames", 1)
        val gameIndex = inputData.getInt("gameIndex", 0)
        
        Log.d("GameDownloadWorker", "=== WORKER STARTED ===")
        Log.d("GameDownloadWorker", "Worker ID: ${this.id}")
        Log.d("GameDownloadWorker", "Game ID: $gameId, Name: $gameName")
        Log.d("GameDownloadWorker", "Platform: $platformSlug, Multi: $isMulti")
        Log.d("GameDownloadWorker", "Bulk download: $isBulkDownload (${gameIndex + 1}/$totalGames)")
        Log.d("GameDownloadWorker", "Download URI: $downloadDirectoryUri")
        Log.d("GameDownloadWorker", "Thread: ${Thread.currentThread().name}")
        
        return try {
            if (downloadDirectoryUri.isEmpty()) {
                Log.e("GameDownloadWorker", "Download directory URI is empty")
                showErrorNotification("Download failed: No download directory")
                return Result.failure()
            }
            
            setForeground(createForegroundInfo(gameName, 0))
            
            // Get detailed game info
            Log.d("GameDownloadWorker", "Fetching game details from API")
            val game = apiService.getGame(gameId)
            Log.d("GameDownloadWorker", "Got game details: ${game.fs_name}")
            
            // Get the base directory DocumentFile
            val baseDir = DocumentFile.fromTreeUri(applicationContext, Uri.parse(downloadDirectoryUri))
            if (baseDir == null) {
                Log.e("GameDownloadWorker", "Could not access base directory from URI: $downloadDirectoryUri")
                showErrorNotification("Download failed: Cannot access download directory")
                return Result.failure()
            }
            
            Log.d("GameDownloadWorker", "Base directory accessible: ${baseDir.name}")
            
            // Get or create platform directory with retry logic
            val platformDir = getOrCreatePlatformDirectory(baseDir, platformSlug)
            
            if (platformDir == null) {
                Log.e("GameDownloadWorker", "Could not create platform directory: $platformSlug")
                showErrorNotification("Download failed: Cannot create platform directory")
                return Result.failure()
            }
            
            Log.d("GameDownloadWorker", "Platform directory ready: ${platformDir.name}")
            
            // Download the game
            val fileName = if (isMulti) "${game.fs_name_no_ext}.zip" else game.fs_name
            Log.d("GameDownloadWorker", "Downloading file: $fileName")
            
            val response = apiService.downloadGame(gameId, fileName)
            
            if (response.isSuccessful) {
                Log.d("GameDownloadWorker", "Download response successful, content length: ${response.body()?.contentLength()}")
                
                response.body()?.let { body ->
                    val targetDir = if (isMulti) {
                        // For multi-disc games, handle directory creation/replacement
                        val existingDir = platformDir.findFile(game.fs_name_no_ext)
                        if (existingDir != null && existingDir.isDirectory) {
                            Log.d("GameDownloadWorker", "Deleting existing multi-disc directory: ${existingDir.name}")
                            deleteDirectoryRecursively(existingDir)
                        }
                        // Create new directory
                        platformDir.createDirectory(game.fs_name_no_ext)
                    } else {
                        platformDir
                    }
                    
                    if (targetDir == null) {
                        Log.e("GameDownloadWorker", "Could not create target directory")
                        showErrorNotification("Download failed: Cannot create game directory")
                        return Result.failure()
                    }
                    
                    // Create the file in the target directory, overwriting if it exists
                    val outputFile = createOrReplaceFile(targetDir, fileName)
                    if (outputFile == null) {
                        Log.e("GameDownloadWorker", "Could not create output file: $fileName")
                        showErrorNotification("Download failed: Cannot create file")
                        return Result.failure()
                    }
                    
                    Log.d("GameDownloadWorker", "Created output file: ${outputFile.name}")
                    
                    // Use optimized download method
                    downloadFileOptimized(body, outputFile, gameName) { progress ->
                        setProgress(workDataOf("progress" to progress))
                    }
                    
                    // If it's a zip file and multi-disc, extract it
                    if (isMulti && fileName.endsWith(".zip")) {
                        Log.d("GameDownloadWorker", "Extracting ZIP file")
                        extractZipFileOptimized(outputFile, targetDir)
                        outputFile.delete() // Remove the zip file after extraction
                        Log.d("GameDownloadWorker", "ZIP extraction complete")
                    }
                    
                    Log.d("GameDownloadWorker", "Download completed successfully for: $gameName")
                    
                    // Show appropriate notification based on download type
                    if (isBulkDownload) {
                        // Show individual notification grouped under the summary
                        downloadManager.showIndividualDownloadNotification(gameName, true, bulkSessionId)
                    } else {
                        // Show standalone success notification
                        showSuccessNotification("Downloaded: $gameName")
                    }
                    
                    // Force garbage collection to free memory
                    System.gc()
                }
                
                Log.d("GameDownloadWorker", "=== WORKER SUCCESS === Game: $gameName")
                Result.success()
            } else {
                Log.e("GameDownloadWorker", "Download failed with response code: ${response.code()}")
                if (isBulkDownload) {
                    downloadManager.showIndividualDownloadNotification(gameName, false, bulkSessionId)
                } else {
                    showErrorNotification("Download failed: Server error ${response.code()}")
                }
                Result.failure()
            }
        } catch (e: OutOfMemoryError) {
            Log.e("GameDownloadWorker", "=== WORKER FAILURE (OOM) === Game: $gameName", e)
            if (isBulkDownload) {
                downloadManager.showIndividualDownloadNotification(gameName, false, bulkSessionId)
            } else {
                showErrorNotification("Download failed: Out of memory")
            }
            System.gc() // Force garbage collection
            Result.failure()
        } catch (e: Exception) {
            Log.e("GameDownloadWorker", "=== WORKER FAILURE (Exception) === Game: $gameName", e)
            if (isBulkDownload) {
                downloadManager.showIndividualDownloadNotification(gameName, false, bulkSessionId)
            } else {
                showErrorNotification("Download failed: ${e.message}")
            }
            Result.failure()
        }
    }
    
    /**
     * Simplified directory creation that handles race conditions gracefully
     */
    private fun getOrCreatePlatformDirectory(baseDir: DocumentFile, platformSlug: String): DocumentFile? {
        // Try to find any existing platform directory first
        val existingDir = findAnyPlatformDirectory(baseDir, platformSlug)
        if (existingDir != null) {
            Log.d("GameDownloadWorker", "Using existing platform directory: ${existingDir.name}")
            return existingDir
        }
        
        // No existing directory found, try to create one
        Log.d("GameDownloadWorker", "Creating platform directory: $platformSlug")
        val newDir = baseDir.createDirectory(platformSlug)
        
        if (newDir != null) {
            Log.d("GameDownloadWorker", "Successfully created platform directory: ${newDir.name}")
            return newDir
        }
        
        // Creation failed, maybe another worker just created it - try finding it again
        Log.d("GameDownloadWorker", "Directory creation failed, checking if another worker created it")
        val retryDir = findAnyPlatformDirectory(baseDir, platformSlug)
        if (retryDir != null) {
            Log.d("GameDownloadWorker", "Found directory created by another worker: ${retryDir.name}")
            return retryDir
        }
        
        Log.e("GameDownloadWorker", "Failed to create or find platform directory: $platformSlug")
        return null
    }
    
    /**
     * Find any existing platform directory (including numbered variants)
     */
    private fun findAnyPlatformDirectory(baseDir: DocumentFile, platformSlug: String): DocumentFile? {
        val files = try {
            baseDir.listFiles()
        } catch (e: Exception) {
            Log.e("GameDownloadWorker", "Error listing directory files", e)
            emptyArray()
        }
        
        // First pass: look for exact match
        for (file in files) {
            if (file.isDirectory && file.name == platformSlug) {
                Log.d("GameDownloadWorker", "Found exact match platform directory: ${file.name}")
                return file
            }
        }
        
        // Second pass: look for numbered variants
        for (file in files) {
            if (file.isDirectory) {
                val name = file.name ?: continue
                // Match pattern like "snes (1)", "snes (2)", etc.
                val regex = Regex("^${Regex.escape(platformSlug)}\\s*\\(\\d+\\)$")
                if (regex.matches(name)) {
                    Log.d("GameDownloadWorker", "Found numbered platform directory: $name")
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
            Log.d("GameDownloadWorker", "Deleting existing file: ${existingFile.name}")
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
                    Log.d("GameDownloadWorker", "Deleting duplicate file: ${file.name}")
                    file.delete()
                }
            }
        }
        
        // Create new file
        return directory.createFile("application/octet-stream", fileName)
    }
    
    /**
     * Recursively delete a directory and its contents
     */
    private fun deleteDirectoryRecursively(directory: DocumentFile): Boolean {
        if (!directory.isDirectory) return false
        
        val files = directory.listFiles()
        for (file in files) {
            if (file.isDirectory) {
                deleteDirectoryRecursively(file)
            } else {
                file.delete()
            }
        }
        
        return directory.delete()
    }
    
    private suspend fun createForegroundInfo(gameName: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, DownloadManager.CHANNEL_ID)
            .setContentTitle("Downloading $gameName")
            .setProgress(100, progress, progress == 0)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        
        return ForegroundInfo(gameName.hashCode(), notification)
    }
    
    private fun showErrorNotification(message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val notification = NotificationCompat.Builder(applicationContext, DownloadManager.CHANNEL_ID)
            .setContentTitle("Download Error")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setGroup(DownloadManager.INDIVIDUAL_DOWNLOAD_GROUP) // Use individual group
            .build()
        
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
    
    private fun showSuccessNotification(message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val notification = NotificationCompat.Builder(applicationContext, DownloadManager.CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setGroup(DownloadManager.INDIVIDUAL_DOWNLOAD_GROUP) // Use individual group
            .build()
        
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
    
    /**
     * Optimized file download with memory management
     */
    private suspend fun downloadFileOptimized(
        body: ResponseBody,
        outputFile: DocumentFile,
        gameName: String,
        onProgress: suspend (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val contentLength = body.contentLength()
        var downloadedBytes = 0L
        var lastProgressUpdate = 0
        
        Log.d("GameDownloadWorker", "Starting optimized file download, content length: $contentLength")
        
        val outputStream = applicationContext.contentResolver.openOutputStream(outputFile.uri)
        if (outputStream == null) {
            Log.e("GameDownloadWorker", "Could not open output stream for file: ${outputFile.name}")
            throw IOException("Could not open output stream")
        }
        
        try {
            body.byteStream().use { input ->
                BufferedOutputStream(outputStream, 32768).use { output -> // 32KB buffer
                    val buffer = ByteArray(4096) // Smaller buffer to reduce memory usage
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        if (contentLength > 0) {
                            val progress = (downloadedBytes * 100 / contentLength).toInt()
                            
                            // Only update progress and notification every 5% to reduce overhead
                            if (progress >= lastProgressUpdate + 5) {
                                lastProgressUpdate = progress
                                onProgress(progress)
                                setForeground(createForegroundInfo(gameName, progress))
                                
                                // Force flush buffer periodically
                                output.flush()
                                
                                Log.d("GameDownloadWorker", "Download progress: $progress% ($downloadedBytes/$contentLength bytes)")
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
        
        Log.d("GameDownloadWorker", "Optimized file download completed, bytes written: $downloadedBytes")
    }
    
    /**
     * Optimized ZIP extraction with memory management
     */
    private suspend fun extractZipFileOptimized(zipFile: DocumentFile, destinationDir: DocumentFile) = withContext(Dispatchers.IO) {
        Log.d("GameDownloadWorker", "Starting optimized ZIP extraction")
        
        val inputStream = applicationContext.contentResolver.openInputStream(zipFile.uri)
            ?: return@withContext
        
        try {
            BufferedInputStream(inputStream, 32768).use { bufferedInput ->
                ZipInputStream(bufferedInput).use { zis ->
                    var entry = zis.nextEntry
                    
                    while (entry != null) {
                        if (entry.isDirectory) {
                            // Create directory
                            val dirName = entry.name.trimEnd('/')
                            destinationDir.findFile(dirName) ?: destinationDir.createDirectory(dirName)
                            Log.d("GameDownloadWorker", "Created directory: $dirName")
                        } else {
                            // Create file, overwriting if it exists
                            val fileName = entry.name
                            val outputFile = createOrReplaceFile(destinationDir, fileName)
                            
                            outputFile?.let { file ->
                                val outputStream = applicationContext.contentResolver.openOutputStream(file.uri)
                                outputStream?.use { output ->
                                    BufferedOutputStream(output, 32768).use { bufferedOutput ->
                                        val buffer = ByteArray(4096)
                                        var bytesRead: Int
                                        
                                        while (zis.read(buffer).also { bytesRead = it } != -1) {
                                            bufferedOutput.write(buffer, 0, bytesRead)
                                        }
                                        
                                        bufferedOutput.flush()
                                    }
                                }
                                Log.d("GameDownloadWorker", "Extracted file: $fileName")
                            }
                        }
                        
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } finally {
            inputStream.close()
        }
        
        Log.d("GameDownloadWorker", "Optimized ZIP extraction completed")
    }
}

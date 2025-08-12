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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@HiltWorker
class UnifiedDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val apiService: RomMApiService
) : CoroutineWorker(context, workerParams) {
    
    private enum class DownloadType {
        GAME, FIRMWARE
    }
    
    companion object {
        // Session-specific semaphore map for concurrency control
        private val semaphoreMap = mutableMapOf<String, Semaphore>()
        
        // Get or create a semaphore for the given session and concurrent limit
        private fun getSemaphoreForSession(sessionId: String, maxConcurrent: Int): Semaphore {
            val key = "${sessionId}_$maxConcurrent"
            return semaphoreMap.getOrPut(key) {
                Log.d("UnifiedDownloadWorker", "Creating semaphore for session $sessionId with limit $maxConcurrent")
                Semaphore(maxConcurrent)
            }
        }
        
        // Clean up semaphores for completed sessions
        fun cleanupSession(sessionId: String) {
            semaphoreMap.keys.removeAll { it.startsWith("${sessionId}_") }
        }
    }
    
    override suspend fun doWork(): Result {
        val downloadId = inputData.getString("downloadId") ?: return Result.failure()
        val downloadName = inputData.getString("downloadName") ?: return Result.failure()
        val downloadType = inputData.getString("downloadType") ?: return Result.failure()
        val downloadDirectoryUri = inputData.getString("downloadDirectory") ?: return Result.failure()
        val sessionId = inputData.getString("sessionId") ?: return Result.failure()
        val maxConcurrent = inputData.getInt("maxConcurrentDownloads", 1)
        
        Log.d("UnifiedDownloadWorker", "=== FOREGROUND DOWNLOAD STARTED ===")
        Log.d("UnifiedDownloadWorker", "Worker ID: ${this.id}")
        Log.d("UnifiedDownloadWorker", "Download ID: $downloadId")
        Log.d("UnifiedDownloadWorker", "Name: $downloadName")
        Log.d("UnifiedDownloadWorker", "Type: $downloadType")
        Log.d("UnifiedDownloadWorker", "Session: $sessionId")
        Log.d("UnifiedDownloadWorker", "Max Concurrent: $maxConcurrent")
        
        return try {
            // Use semaphore to control concurrency
            val semaphore = getSemaphoreForSession(sessionId, maxConcurrent)
            Log.d("UnifiedDownloadWorker", "Waiting for download slot ($maxConcurrent max concurrent)")
            
            semaphore.withPermit {
                Log.d("UnifiedDownloadWorker", "Acquired download slot - starting download: $downloadName")
                
                // Set foreground service ONLY after acquiring download slot
                setForeground(createActiveForegroundInfo(downloadName, 0))
                
                when (DownloadType.valueOf(downloadType)) {
                    DownloadType.GAME -> downloadGame()
                    DownloadType.FIRMWARE -> downloadFirmware()
                }
            }
        } catch (e: OutOfMemoryError) {
            Log.e("UnifiedDownloadWorker", "=== DOWNLOAD FAILED (OOM) === $downloadName", e)
            System.gc()
            Result.failure()
        } catch (e: Exception) {
            Log.e("UnifiedDownloadWorker", "=== DOWNLOAD FAILED (Exception) === $downloadName", e)
            Result.failure()
        }
    }
    
    /**
     * Download a game with foreground service notification
     */
    private suspend fun downloadGame(): Result {
        val gameId = inputData.getInt("gameId", -1)
        val platformSlug = inputData.getString("platformSlug") ?: return Result.failure()
        val isMulti = inputData.getBoolean("isMulti", false)
        val downloadName = inputData.getString("downloadName") ?: return Result.failure()
        val downloadDirectoryUri = inputData.getString("downloadDirectory") ?: return Result.failure()
        
        Log.d("UnifiedDownloadWorker", "Downloading game: $downloadName (ID: $gameId, Multi: $isMulti)")
        
        // Get detailed game info
        val game = apiService.getGame(gameId)
        Log.d("UnifiedDownloadWorker", "Got game details: ${game.fs_name}")
        
        // Get base directory
        val baseDir = DocumentFile.fromTreeUri(applicationContext, Uri.parse(downloadDirectoryUri))
        if (baseDir == null) {
            Log.e("UnifiedDownloadWorker", "Could not access base directory: $downloadDirectoryUri")
            return Result.failure()
        }
        
        // Get or create platform directory
        val platformDir = getOrCreatePlatformDirectory(baseDir, platformSlug)
        if (platformDir == null) {
            Log.e("UnifiedDownloadWorker", "Could not create platform directory: $platformSlug")
            return Result.failure()
        }
        
        // Prepare download
        val fileName = if (isMulti) "${game.fs_name_no_ext}.zip" else game.fs_name
        Log.d("UnifiedDownloadWorker", "Downloading file: $fileName")
        
        val response = apiService.downloadGame(gameId, fileName)
        
        if (!response.isSuccessful) {
            Log.e("UnifiedDownloadWorker", "Download failed with response code: ${response.code()}")
            return Result.failure()
        }
        
        response.body()?.let { body ->
            val targetDir = if (isMulti) {
                // For multi-disc games, create/replace directory
                val existingDir = platformDir.findFile(game.fs_name_no_ext)
                if (existingDir != null && existingDir.isDirectory) {
                    Log.d("UnifiedDownloadWorker", "Deleting existing multi-disc directory: ${existingDir.name}")
                    deleteDirectoryRecursively(existingDir)
                }
                platformDir.createDirectory(game.fs_name_no_ext)
            } else {
                platformDir
            }
            
            if (targetDir == null) {
                Log.e("UnifiedDownloadWorker", "Could not create target directory")
                return Result.failure()
            }
            
            // Create/overwrite the file
            val outputFile = createOrReplaceFile(targetDir, fileName)
            if (outputFile == null) {
                Log.e("UnifiedDownloadWorker", "Could not create output file: $fileName")
                return Result.failure()
            }
            
            Log.d("UnifiedDownloadWorker", "Created output file: ${outputFile.name}")
            
            // Download with foreground progress updates
            downloadFileWithForegroundUpdates(body, outputFile, downloadName) { progress ->
                setProgress(workDataOf("progress" to progress))
            }
            
            // Extract ZIP if needed
            if (isMulti && fileName.endsWith(".zip")) {
                Log.d("UnifiedDownloadWorker", "Extracting ZIP file")
                setForeground(createActiveForegroundInfo("$downloadName (Extracting...)", 100))
                extractZipFile(outputFile, targetDir)
                outputFile.delete() // Remove ZIP after extraction
                Log.d("UnifiedDownloadWorker", "ZIP extraction complete")
            }
            
            Log.d("UnifiedDownloadWorker", "=== DOWNLOAD SUCCESS === $downloadName")
            return Result.success()
        }
        
        return Result.failure()
    }
    
    /**
     * Download firmware with foreground service notification
     */
    private suspend fun downloadFirmware(): Result {
        val firmwareId = inputData.getInt("firmwareId", -1)
        val fileName = inputData.getString("fileName") ?: return Result.failure()
        val downloadName = inputData.getString("downloadName") ?: return Result.failure()
        val downloadDirectoryUri = inputData.getString("downloadDirectory") ?: return Result.failure()
        
        Log.d("UnifiedDownloadWorker", "Downloading firmware: $fileName (ID: $firmwareId)")
        
        // Get base directory
        val baseDir = DocumentFile.fromTreeUri(applicationContext, Uri.parse(downloadDirectoryUri))
        if (baseDir == null) {
            Log.e("UnifiedDownloadWorker", "Could not access base directory: $downloadDirectoryUri")
            return Result.failure()
        }
        
        // Get or create firmware directory
        val firmwareDir = getOrCreateFirmwareDirectory(baseDir)
        if (firmwareDir == null) {
            Log.e("UnifiedDownloadWorker", "Could not create firmware directory")
            return Result.failure()
        }
        
        // Download firmware
        val response = apiService.downloadFirmware(firmwareId, fileName)
        
        if (!response.isSuccessful) {
            Log.e("UnifiedDownloadWorker", "Firmware download failed with response code: ${response.code()}")
            return Result.failure()
        }
        
        response.body()?.let { body ->
            // Create/overwrite the file
            val outputFile = createOrReplaceFile(firmwareDir, fileName)
            if (outputFile == null) {
                Log.e("UnifiedDownloadWorker", "Could not create firmware output file: $fileName")
                return Result.failure()
            }
            
            // Download with foreground progress updates
            downloadFileWithForegroundUpdates(body, outputFile, downloadName) { progress ->
                setProgress(workDataOf("progress" to progress))
            }
            
            Log.d("UnifiedDownloadWorker", "=== FIRMWARE DOWNLOAD SUCCESS === $fileName")
            return Result.success()
        }
        
        return Result.failure()
    }
    
    
    /**
     * Create active foreground info for downloading
     */
    private fun createActiveForegroundInfo(name: String, progress: Int): ForegroundInfo {
        val title = if (progress > 0 && progress < 100) {
            "$name ($progress%)"
        } else if (progress >= 100) {
            name
        } else {
            "Starting: $name"
        }
        
        val notification = NotificationCompat.Builder(applicationContext, DownloadManager.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Downloading...")
            .setProgress(100, progress, progress == 0)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setSilent(true) // Don't make noise for individual downloads
            .setGroup("INDIVIDUAL_DOWNLOADS") // Group individual downloads together
            .build()
        
        // Use worker ID hash for consistent notification ID
        return ForegroundInfo(this.hashCode(), notification)
    }
    
    /**
     * Download file with foreground service progress updates
     */
    private suspend fun downloadFileWithForegroundUpdates(
        body: ResponseBody,
        outputFile: DocumentFile,
        downloadName: String,
        onProgress: suspend (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val contentLength = body.contentLength()
        var downloadedBytes = 0L
        var lastProgressUpdate = 0
        
        Log.d("UnifiedDownloadWorker", "Starting download, content length: $contentLength")
        
        val outputStream = applicationContext.contentResolver.openOutputStream(outputFile.uri)
        if (outputStream == null) {
            Log.e("UnifiedDownloadWorker", "Could not open output stream for file: ${outputFile.name}")
            throw IOException("Could not open output stream")
        }
        
        try {
            body.byteStream().use { input ->
                BufferedOutputStream(outputStream, 65536).use { output -> // 64KB buffer
                    val buffer = ByteArray(8192) // 8KB read buffer
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        if (contentLength > 0) {
                            val progress = (downloadedBytes * 100 / contentLength).toInt()
                            
                            // Update progress every 5% to reduce overhead
                            if (progress >= lastProgressUpdate + 5) {
                                lastProgressUpdate = progress
                                onProgress(progress)
                                
                                // Update foreground notification
                                setForeground(createActiveForegroundInfo(downloadName, progress))
                                
                                // Flush buffer periodically
                                output.flush()
                                
                                Log.d("UnifiedDownloadWorker", "Download progress: $progress% ($downloadedBytes/$contentLength bytes)")
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
        
        Log.d("UnifiedDownloadWorker", "Download completed, bytes written: $downloadedBytes")
    }
    
    /**
     * Get or create platform directory with improved race condition handling
     */
    private fun getOrCreatePlatformDirectory(baseDir: DocumentFile, platformSlug: String): DocumentFile? {
        // Multiple attempts to handle race conditions between concurrent workers
        repeat(5) { attempt ->
            // Always check for existing directory first
            val existingDir = findAnyPlatformDirectory(baseDir, platformSlug)
            if (existingDir != null) {
                Log.d("UnifiedDownloadWorker", "Using existing platform directory: ${existingDir.name}")
                return existingDir
            }
            
            // Try to create new directory
            Log.d("UnifiedDownloadWorker", "Attempt ${attempt + 1}: Creating platform directory: $platformSlug")
            try {
                val newDir = baseDir.createDirectory(platformSlug)
                if (newDir != null && newDir.name == platformSlug) {
                    Log.d("UnifiedDownloadWorker", "Successfully created platform directory: ${newDir.name}")
                    return newDir
                } else if (newDir != null) {
                    // Android created a numbered variant - delete it and try again
                    Log.w("UnifiedDownloadWorker", "Android created numbered variant: ${newDir.name}, deleting and retrying")
                    newDir.delete()
                }
            } catch (e: Exception) {
                Log.w("UnifiedDownloadWorker", "Directory creation attempt ${attempt + 1} failed", e)
            }
            
            // Small delay before retry to avoid tight loop
            Thread.sleep(50)
        }
        
        // Final attempt: check one more time if another worker created it
        val finalCheck = findAnyPlatformDirectory(baseDir, platformSlug)
        if (finalCheck != null) {
            Log.d("UnifiedDownloadWorker", "Found directory created by another worker: ${finalCheck.name}")
            // Clean up any numbered variants that might have been created accidentally
            cleanupNumberedVariants(baseDir, platformSlug)
            return finalCheck
        }
        
        Log.e("UnifiedDownloadWorker", "Failed to create or find platform directory after 5 attempts: $platformSlug")
        return null
    }
    
    /**
     * Clean up numbered variant directories that were created accidentally due to race conditions
     */
    private fun cleanupNumberedVariants(baseDir: DocumentFile, platformSlug: String) {
        try {
            val files = baseDir.listFiles()
            files.forEach { file ->
                if (file.isDirectory) {
                    val name = file.name ?: return@forEach
                    val regex = Regex("^${Regex.escape(platformSlug)}\\s*\\(\\d+\\)$")
                    if (regex.matches(name)) {
                        Log.d("UnifiedDownloadWorker", "Cleaning up numbered variant directory: $name")
                        // Move contents to the main directory if it has any files
                        val mainDir = findAnyPlatformDirectory(baseDir, platformSlug)
                        if (mainDir != null) {
                            moveDirectoryContents(file, mainDir)
                        }
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("UnifiedDownloadWorker", "Error cleaning up numbered variants", e)
        }
    }
    
    /**
     * Move contents from source directory to destination directory
     */
    private fun moveDirectoryContents(sourceDir: DocumentFile, destDir: DocumentFile) {
        try {
            sourceDir.listFiles().forEach { file ->
                if (file.isFile) {
                    // Check if file already exists in destination
                    val existingFile = destDir.findFile(file.name ?: "")
                    if (existingFile == null) {
                        // File doesn't exist in destination, we could move it but for safety we'll just delete the duplicate
                        Log.d("UnifiedDownloadWorker", "Removing duplicate file from numbered variant: ${file.name}")
                        file.delete()
                    } else {
                        // File already exists, delete the duplicate
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("UnifiedDownloadWorker", "Error moving directory contents", e)
        }
    }
    
    /**
     * Find platform directory - ONLY exact match, ignore numbered variants
     */
    private fun findAnyPlatformDirectory(baseDir: DocumentFile, platformSlug: String): DocumentFile? {
        val files = try {
            baseDir.listFiles()
        } catch (e: Exception) {
            Log.e("UnifiedDownloadWorker", "Error listing directory files", e)
            emptyArray()
        }
        
        // Only exact match - ignore numbered variants to prevent race condition issues
        for (file in files) {
            if (file.isDirectory && file.name == platformSlug) {
                return file
            }
        }
        
        return null
    }
    
    /**
     * Get or create firmware directory
     */
    private fun getOrCreateFirmwareDirectory(baseDir: DocumentFile): DocumentFile? {
        // Try to find existing firmware directory
        val existingDir = findAnyFirmwareDirectory(baseDir)
        if (existingDir != null) {
            Log.d("UnifiedDownloadWorker", "Using existing firmware directory: ${existingDir.name}")
            return existingDir
        }
        
        // Create new firmware directory
        Log.d("UnifiedDownloadWorker", "Creating firmware directory")
        val newDir = baseDir.createDirectory("firmware")
        
        if (newDir != null) {
            Log.d("UnifiedDownloadWorker", "Successfully created firmware directory: ${newDir.name}")
            return newDir
        }
        
        // Creation failed, try to find it again
        val retryDir = findAnyFirmwareDirectory(baseDir)
        if (retryDir != null) {
            Log.d("UnifiedDownloadWorker", "Found firmware directory created by another worker: ${retryDir.name}")
            return retryDir
        }
        
        Log.e("UnifiedDownloadWorker", "Failed to create or find firmware directory")
        return null
    }
    
    /**
     * Find firmware directory including numbered variants
     */
    private fun findAnyFirmwareDirectory(baseDir: DocumentFile): DocumentFile? {
        val files = try {
            baseDir.listFiles()
        } catch (e: Exception) {
            Log.e("UnifiedDownloadWorker", "Error listing directory files", e)
            emptyArray()
        }
        
        // First pass: exact match
        for (file in files) {
            if (file.isDirectory && file.name == "firmware") {
                return file
            }
        }
        
        // Second pass: numbered variants
        for (file in files) {
            if (file.isDirectory) {
                val name = file.name ?: continue
                val regex = Regex("^firmware\\s*\\(\\d+\\)$")
                if (regex.matches(name)) {
                    return file
                }
            }
        }
        
        return null
    }
    
    /**
     * Create or replace file - ensures no duplicates
     */
    private fun createOrReplaceFile(directory: DocumentFile, fileName: String): DocumentFile? {
        // Delete existing file with same name
        val existingFile = directory.findFile(fileName)
        if (existingFile != null && existingFile.isFile) {
            Log.d("UnifiedDownloadWorker", "Deleting existing file: ${existingFile.name}")
            existingFile.delete()
        }
        
        // Delete numbered variants like "filename (1).ext"
        val fileNameWithoutExt = fileName.substringBeforeLast(".")
        val fileExtension = fileName.substringAfterLast(".", "")
        
        directory.listFiles().forEach { file ->
            if (file.isFile) {
                val name = file.name ?: return@forEach
                val nameWithoutExt = name.substringBeforeLast(".")
                
                val regex = Regex("^${Regex.escape(fileNameWithoutExt)}\\s*\\(\\d+\\)$")
                if (regex.matches(nameWithoutExt)) {
                    Log.d("UnifiedDownloadWorker", "Deleting duplicate file: ${file.name}")
                    file.delete()
                }
            }
        }
        
        // Create new file
        return directory.createFile("application/octet-stream", fileName)
    }
    
    /**
     * Extract ZIP file preserving directory structure
     */
    private suspend fun extractZipFile(zipFile: DocumentFile, destinationDir: DocumentFile) = withContext(Dispatchers.IO) {
        Log.d("UnifiedDownloadWorker", "Starting ZIP extraction")
        
        val inputStream = applicationContext.contentResolver.openInputStream(zipFile.uri)
            ?: return@withContext
        
        try {
            BufferedInputStream(inputStream, 65536).use { bufferedInput ->
                ZipInputStream(bufferedInput).use { zis ->
                    var entry = zis.nextEntry
                    
                    while (entry != null) {
                        val entryName = entry.name
                        Log.d("UnifiedDownloadWorker", "Processing ZIP entry: $entryName")
                        
                        if (entry.isDirectory) {
                            // Create directory structure
                            createDirectoryStructure(destinationDir, entryName.trimEnd('/'))
                        } else {
                            // Create file with proper directory structure
                            val outputFile = createFileWithDirectories(destinationDir, entryName)
                            
                            outputFile?.let { file ->
                                val outputStream = applicationContext.contentResolver.openOutputStream(file.uri)
                                outputStream?.use { output ->
                                    BufferedOutputStream(output, 65536).use { bufferedOutput ->
                                        val buffer = ByteArray(8192)
                                        var bytesRead: Int
                                        
                                        while (zis.read(buffer).also { bytesRead = it } != -1) {
                                            bufferedOutput.write(buffer, 0, bytesRead)
                                        }
                                        
                                        bufferedOutput.flush()
                                    }
                                }
                                Log.d("UnifiedDownloadWorker", "Extracted file: $entryName")
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
        
        Log.d("UnifiedDownloadWorker", "ZIP extraction completed")
    }
    
    /**
     * Create directory structure recursively
     */
    private fun createDirectoryStructure(baseDir: DocumentFile, dirPath: String): DocumentFile? {
        if (dirPath.isEmpty()) return baseDir
        
        val pathParts = dirPath.split('/')
        var currentDir = baseDir
        
        for (part in pathParts) {
            if (part.isNotEmpty()) {
                val existingDir = currentDir.findFile(part)
                currentDir = if (existingDir != null && existingDir.isDirectory) {
                    existingDir
                } else {
                    currentDir.createDirectory(part) ?: return null
                }
            }
        }
        
        return currentDir
    }
    
    /**
     * Create file with proper directory structure
     */
    private fun createFileWithDirectories(baseDir: DocumentFile, filePath: String): DocumentFile? {
        val pathParts = filePath.split('/')
        if (pathParts.isEmpty()) return null
        
        val fileName = pathParts.last()
        val dirPath = if (pathParts.size > 1) {
            pathParts.dropLast(1).joinToString("/")
        } else {
            ""
        }
        
        val targetDir = if (dirPath.isEmpty()) {
            baseDir
        } else {
            createDirectoryStructure(baseDir, dirPath) ?: return null
        }
        
        return createOrReplaceFile(targetDir, fileName)
    }
    
    /**
     * Recursively delete directory and contents
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
}
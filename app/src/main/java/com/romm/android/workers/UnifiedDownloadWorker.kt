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
import com.romm.android.utils.PlatformMapper
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
        GAME, FIRMWARE, SAVE_FILE, SAVE_STATE
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
                    DownloadType.SAVE_FILE -> downloadSaveFile()
                    DownloadType.SAVE_STATE -> downloadSaveState()
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
        
        // Get or create platform directory using ES-DE mapping if available
        val folderName = PlatformMapper.getEsdeFolderName(platformSlug)
        Log.d("UnifiedDownloadWorker", "Platform mapping: $platformSlug -> $folderName")
        val platformDir = getOrCreatePlatformDirectory(baseDir, folderName)
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
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(this.hashCode(), notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(this.hashCode(), notification)
        }
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
    
    /**
     * Download a save file with foreground service notification
     */
    private suspend fun downloadSaveFile(): Result {
        val saveId = inputData.getInt("saveId", -1)
        val fileName = inputData.getString("fileName") ?: return Result.failure()
        val emulator = inputData.getString("emulator") ?: "Unknown"
        val romId = inputData.getInt("romId", -1)
        val downloadName = inputData.getString("downloadName") ?: return Result.failure()
        val saveFilesDirectoryUri = inputData.getString("saveFilesDirectory") ?: return Result.failure()
        
        Log.d("UnifiedDownloadWorker", "Downloading save file: $downloadName (ID: $saveId)")
        
        // Get base directory
        val baseDir = DocumentFile.fromTreeUri(applicationContext, Uri.parse(saveFilesDirectoryUri))
        if (baseDir == null) {
            Log.e("UnifiedDownloadWorker", "Could not access save files directory: $saveFilesDirectoryUri")
            return Result.failure()
        }
        
        // Get rom info to determine platform
        val rom = apiService.getGame(romId)
        val platformSlug = rom.platform_fs_slug
        
        // Create directory structure: [SAVE_DIR]/[PLATFORM]/[EMULATOR]/
        val platformFolderName = PlatformMapper.getEsdeFolderName(platformSlug)
        val emulatorFolderName = emulator
        
        // Get or create platform directory
        val platformDir = getOrCreateSaveDirectory(baseDir, platformFolderName)
        if (platformDir == null) {
            Log.e("UnifiedDownloadWorker", "Could not create platform directory: $platformFolderName")
            return Result.failure()
        }
        
        // Get or create emulator directory
        val emulatorDir = getOrCreateSaveDirectory(platformDir, emulatorFolderName)
        if (emulatorDir == null) {
            Log.e("UnifiedDownloadWorker", "Could not create emulator directory: $emulatorFolderName")
            return Result.failure()
        }
        
        // Get save file metadata first, then download using properly encoded URLs
        val saveFile = apiService.getSave(saveId)
        val response = apiService.downloadSaveFile(saveFile)
        
        if (!response.isSuccessful) {
            Log.e("UnifiedDownloadWorker", "Save file download failed with response code: ${response.code()}")
            return Result.failure()
        }
        
        response.body()?.let { body ->
            // Strip timestamp from filename before saving locally
            val localFileName = stripTimestampFromFilename(fileName)
            Log.d("UnifiedDownloadWorker", "Original filename: $fileName")
            Log.d("UnifiedDownloadWorker", "Local filename: $localFileName")
            
            // Create/overwrite the file
            val outputFile = createOrReplaceFile(emulatorDir, localFileName)
            if (outputFile == null) {
                Log.e("UnifiedDownloadWorker", "Could not create save file output: $fileName")
                return Result.failure()
            }
            
            // Download with foreground progress updates
            downloadFileWithForegroundUpdates(body, outputFile, downloadName) { progress ->
                setProgress(workDataOf("progress" to progress))
            }
            
            Log.d("UnifiedDownloadWorker", "=== SAVE FILE DOWNLOAD SUCCESS === $fileName")
            return Result.success()
        }
        
        return Result.failure()
    }
    
    /**
     * Download a save state with foreground service notification
     */
    private suspend fun downloadSaveState(): Result {
        val stateId = inputData.getInt("stateId", -1)
        val fileName = inputData.getString("fileName") ?: return Result.failure()
        val emulator = inputData.getString("emulator") ?: "Unknown"
        val romId = inputData.getInt("romId", -1)
        val downloadName = inputData.getString("downloadName") ?: return Result.failure()
        val saveStatesDirectoryUri = inputData.getString("saveStatesDirectory") ?: return Result.failure()
        
        Log.d("UnifiedDownloadWorker", "Downloading save state: $downloadName (ID: $stateId)")
        
        // Get base directory
        val baseDir = DocumentFile.fromTreeUri(applicationContext, Uri.parse(saveStatesDirectoryUri))
        if (baseDir == null) {
            Log.e("UnifiedDownloadWorker", "Could not access save states directory: $saveStatesDirectoryUri")
            return Result.failure()
        }
        
        // Get rom info to determine platform
        val rom = apiService.getGame(romId)
        val platformSlug = rom.platform_fs_slug
        
        // Create directory structure: [STATE_DIR]/[PLATFORM]/[EMULATOR]/
        val platformFolderName = PlatformMapper.getEsdeFolderName(platformSlug)
        val emulatorFolderName = emulator
        
        // Get or create platform directory
        val platformDir = getOrCreateSaveDirectory(baseDir, platformFolderName)
        if (platformDir == null) {
            Log.e("UnifiedDownloadWorker", "Could not create platform directory: $platformFolderName")
            return Result.failure()
        }
        
        // Get or create emulator directory
        val emulatorDir = getOrCreateSaveDirectory(platformDir, emulatorFolderName)
        if (emulatorDir == null) {
            Log.e("UnifiedDownloadWorker", "Could not create emulator directory: $emulatorFolderName")
            return Result.failure()
        }
        
        // Get save state metadata first, then download using properly encoded URLs
        val saveState = apiService.getState(stateId)
        val response = apiService.downloadSaveState(saveState)
        
        if (!response.isSuccessful) {
            Log.e("UnifiedDownloadWorker", "Save state download failed with response code: ${response.code()}")
            return Result.failure()
        }
        
        response.body()?.let { body ->
            // Strip timestamp from filename before saving locally
            val localFileName = stripTimestampFromFilename(fileName)
            Log.d("UnifiedDownloadWorker", "Original filename: $fileName")
            Log.d("UnifiedDownloadWorker", "Local filename: $localFileName")
            
            // Create/overwrite the file
            val outputFile = createOrReplaceFile(emulatorDir, localFileName)
            if (outputFile == null) {
                Log.e("UnifiedDownloadWorker", "Could not create save state output: $fileName")
                return Result.failure()
            }
            
            // Download with foreground progress updates
            downloadFileWithForegroundUpdates(body, outputFile, downloadName) { progress ->
                setProgress(workDataOf("progress" to progress))
            }
            
            Log.d("UnifiedDownloadWorker", "=== SAVE STATE DOWNLOAD SUCCESS === $fileName")
            
            // After successfully downloading the save state, check for and download associated screenshot
            try {
                Log.d("UnifiedDownloadWorker", "Checking for associated screenshot for save state: $stateId")
                val screenshots = apiService.getScreenshotsForSaveState(stateId)
                
                if (screenshots.isNotEmpty()) {
                    // Use the most recent screenshot (should typically be just one)
                    val screenshot = screenshots.first()
                    Log.d("UnifiedDownloadWorker", "Found screenshot: ${screenshot.file_name}")
                    
                    val screenshotResponse = apiService.downloadScreenshot(screenshot)
                    if (screenshotResponse.isSuccessful) {
                        screenshotResponse.body()?.let { screenshotBody ->
                            // Create PNG filename matching the local save state file
                            val screenshotFileName = "${localFileName.substringBeforeLast(".")}.png"
                            
                            val screenshotFile = createOrReplaceFile(emulatorDir, screenshotFileName)
                            if (screenshotFile != null) {
                                Log.d("UnifiedDownloadWorker", "Downloading screenshot as: $screenshotFileName")
                                downloadFileWithForegroundUpdates(screenshotBody, screenshotFile, "Screenshot") { progress ->
                                    // Don't update main progress for screenshot, just log
                                }
                                Log.d("UnifiedDownloadWorker", "=== SCREENSHOT DOWNLOAD SUCCESS === $screenshotFileName")
                            } else {
                                Log.w("UnifiedDownloadWorker", "Could not create screenshot file: $screenshotFileName")
                            }
                        }
                    } else {
                        Log.w("UnifiedDownloadWorker", "Screenshot download failed with response code: ${screenshotResponse.code()}")
                    }
                } else {
                    Log.d("UnifiedDownloadWorker", "No screenshot found for save state: $stateId")
                }
            } catch (e: Exception) {
                Log.w("UnifiedDownloadWorker", "Error downloading screenshot for save state $stateId", e)
                // Don't fail the entire operation if screenshot download fails
            }
            
            return Result.success()
        }
        
        return Result.failure()
    }
    
    /**
     * Get or create directory for saves/states
     */
    private fun getOrCreateSaveDirectory(baseDir: DocumentFile, dirName: String): DocumentFile? {
        // Try to find existing directory
        val existingDir = baseDir.findFile(dirName)
        if (existingDir != null && existingDir.isDirectory) {
            Log.d("UnifiedDownloadWorker", "Using existing directory: $dirName")
            return existingDir
        }
        
        // Create new directory
        Log.d("UnifiedDownloadWorker", "Creating directory: $dirName")
        val newDir = baseDir.createDirectory(dirName)
        
        if (newDir != null) {
            Log.d("UnifiedDownloadWorker", "Successfully created directory: ${newDir.name}")
            return newDir
        }
        
        // Creation failed, try to find it again (race condition handling)
        val retryDir = baseDir.findFile(dirName)
        if (retryDir != null && retryDir.isDirectory) {
            Log.d("UnifiedDownloadWorker", "Found directory created by another worker: ${retryDir.name}")
            return retryDir
        }
        
        Log.e("UnifiedDownloadWorker", "Failed to create or find directory: $dirName")
        return null
    }
    
    /**
     * Strip timestamp and android-sync- prefix from filename to get original game filename
     * Converts: "android-sync-Metroid - Zero Mission (Europe) (En,Fr,De,Es,It) [2025-09-05 23-17-09-884].srm"
     * To: "Metroid - Zero Mission (Europe) (En,Fr,De,Es,It).srm"
     */
    private fun stripTimestampFromFilename(fileName: String): String {
        // First remove timestamp pattern
        val timestampPattern = Regex("""\s\[\d{4}-\d{2}-\d{2}\s\d{2}-\d{2}-\d{2}-\d{3}\]""")
        val withoutTimestamp = fileName.replace(timestampPattern, "")
        
        // Then remove android-sync- prefix if present
        return if (withoutTimestamp.startsWith("android-sync-")) {
            withoutTimestamp.removePrefix("android-sync-")
        } else {
            withoutTimestamp
        }
    }
    
}
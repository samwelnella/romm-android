package com.romm.android.sync

import android.content.Context
import android.util.Log
import com.romm.android.data.*
import com.romm.android.network.RomMApiService
import com.romm.android.utils.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor(
    private val context: Context,
    private val apiService: RomMApiService,
    private val downloadManager: DownloadManager,
    private val fileScanner: FileScanner
) {
    
    suspend fun createSyncPlan(
        syncRequest: SyncRequest,
        settings: AppSettings
    ): SyncPlan = withContext(Dispatchers.IO) {
        try {
            Log.d("SyncManager", "Creating sync plan...")
            
            // Scan local files
            val localItems = fileScanner.scanLocalSaveFiles(settings)
                .filter { item ->
                    // Apply filters
                    (syncRequest.platformFilter?.let { filter ->
                        item.platform.equals(filter, ignoreCase = true)
                    } ?: true) &&
                    (syncRequest.emulatorFilter?.let { filter ->
                        item.emulator?.equals(filter, ignoreCase = true) ?: false
                    } ?: true) &&
                    (syncRequest.saveFilesEnabled || item.type != SyncItemType.SAVE_FILE) &&
                    (syncRequest.saveStatesEnabled || item.type != SyncItemType.SAVE_STATE)
                }
            
            Log.d("SyncManager", "Found ${localItems.size} local items")
            
            // Get remote items from server
            val remoteItems = getRemoteItems(syncRequest, settings)
            Log.d("SyncManager", "Found ${remoteItems.size} remote items")
            
            // Create comparisons
            val comparisons = createSyncComparisons(localItems, remoteItems, syncRequest.direction)
            
            // Calculate statistics
            val uploadComparisons = comparisons.filter { it.recommendedAction == SyncAction.UPLOAD }
            val downloadComparisons = comparisons.filter { it.recommendedAction == SyncAction.DOWNLOAD }
            val skipComparisons = comparisons.filter { 
                it.recommendedAction == SyncAction.SKIP_CONFLICT || it.recommendedAction == SyncAction.SKIP_IDENTICAL
            }
            
            SyncPlan(
                direction = syncRequest.direction,
                comparisons = comparisons,
                totalUploadCount = uploadComparisons.size,
                totalDownloadCount = downloadComparisons.size,
                totalSkipCount = skipComparisons.size,
                estimatedUploadSize = uploadComparisons.mapNotNull { it.localItem?.sizeBytes }.sum(),
                estimatedDownloadSize = downloadComparisons.mapNotNull { it.remoteItem?.sizeBytes }.sum()
            )
            
        } catch (e: Exception) {
            Log.e("SyncManager", "Error creating sync plan", e)
            SyncPlan(
                direction = syncRequest.direction,
                comparisons = emptyList(),
                totalUploadCount = 0,
                totalDownloadCount = 0,
                totalSkipCount = 0,
                estimatedUploadSize = 0,
                estimatedDownloadSize = 0
            )
        }
    }
    
    suspend fun executeSync(
        syncRequest: SyncRequest,
        settings: AppSettings,
        onProgress: (SyncProgress) -> Unit,
        onComplete: (SyncResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        
        try {
            // Create sync plan
            onProgress(SyncProgress(
                currentStep = "Planning sync...",
                itemsProcessed = 0,
                totalItems = 0,
                bytesTransferred = 0,
                totalBytes = 0,
                isComplete = false,
                hasErrors = false
            ))
            
            val plan = createSyncPlan(syncRequest, settings)
            
            if (!plan.hasWork) {
                onComplete(SyncResult(
                    success = true,
                    uploadedCount = 0,
                    downloadedCount = 0,
                    skippedCount = plan.totalSkipCount,
                    errorCount = 0,
                    errors = emptyList(),
                    duration = System.currentTimeMillis() - startTime
                ))
                return@withContext
            }
            
            var processedItems = 0
            var bytesTransferred = 0L
            var uploadedCount = 0
            var downloadedCount = 0
            
            val totalItems = plan.totalUploadCount + plan.totalDownloadCount
            val totalBytes = plan.estimatedUploadSize + plan.estimatedDownloadSize
            
            // Process uploads first
            for (comparison in plan.comparisons.filter { it.recommendedAction == SyncAction.UPLOAD }) {
                try {
                    onProgress(SyncProgress(
                        currentStep = "Uploading ${comparison.localItem?.fileName}...",
                        itemsProcessed = processedItems,
                        totalItems = totalItems,
                        bytesTransferred = bytesTransferred,
                        totalBytes = totalBytes,
                        isComplete = false,
                        hasErrors = errors.isNotEmpty(),
                        errors = errors.toList()
                    ))
                    
                    val success = performUpload(comparison, settings)
                    if (success) {
                        uploadedCount++
                        bytesTransferred += comparison.localItem?.sizeBytes ?: 0
                    } else {
                        errors.add("Failed to upload ${comparison.localItem?.fileName}")
                    }
                    
                } catch (e: Exception) {
                    Log.e("SyncManager", "Error uploading ${comparison.localItem?.fileName}", e)
                    errors.add("Upload error: ${comparison.localItem?.fileName} - ${e.message}")
                }
                
                processedItems++
            }
            
            // Process downloads
            for (comparison in plan.comparisons.filter { it.recommendedAction == SyncAction.DOWNLOAD }) {
                try {
                    onProgress(SyncProgress(
                        currentStep = "Downloading ${comparison.remoteItem?.fileName}...",
                        itemsProcessed = processedItems,
                        totalItems = totalItems,
                        bytesTransferred = bytesTransferred,
                        totalBytes = totalBytes,
                        isComplete = false,
                        hasErrors = errors.isNotEmpty(),
                        errors = errors.toList()
                    ))
                    
                    val success = performDownload(comparison, settings)
                    if (success) {
                        downloadedCount++
                        bytesTransferred += comparison.remoteItem?.sizeBytes ?: 0
                    } else {
                        errors.add("Failed to download ${comparison.remoteItem?.fileName}")
                    }
                    
                } catch (e: Exception) {
                    Log.e("SyncManager", "Error downloading ${comparison.remoteItem?.fileName}", e)
                    errors.add("Download error: ${comparison.remoteItem?.fileName} - ${e.message}")
                }
                
                processedItems++
            }
            
            onComplete(SyncResult(
                success = errors.isEmpty(),
                uploadedCount = uploadedCount,
                downloadedCount = downloadedCount,
                skippedCount = plan.totalSkipCount,
                errorCount = errors.size,
                errors = errors,
                duration = System.currentTimeMillis() - startTime
            ))
            
        } catch (e: Exception) {
            Log.e("SyncManager", "Sync execution failed", e)
            onComplete(SyncResult(
                success = false,
                uploadedCount = 0,
                downloadedCount = 0,
                skippedCount = 0,
                errorCount = 1,
                errors = listOf("Sync failed: ${e.message}"),
                duration = System.currentTimeMillis() - startTime
            ))
        }
    }
    
    private suspend fun getRemoteItems(syncRequest: SyncRequest, settings: AppSettings): List<RemoteSyncItem> {
        val remoteItems = mutableListOf<RemoteSyncItem>()
        
        try {
            Log.d("SyncManager", "Starting remote items fetch...")
            
            // Get all save files in one call
            if (syncRequest.saveFilesEnabled) {
                try {
                    Log.d("SyncManager", "Fetching all save files from server...")
                    val saves = apiService.getSaves()
                    Log.d("SyncManager", "Retrieved ${saves.size} save files from server")
                    
                    for ((index, save) in saves.withIndex()) {
                        Log.d("SyncManager", "Processing save file ${index + 1}/${saves.size}: ${save.file_name} (ROM ID: ${save.rom_id})")
                        
                        // Apply emulator filter if specified
                        if (syncRequest.emulatorFilter != null && 
                            save.emulator?.equals(syncRequest.emulatorFilter, ignoreCase = true) != true) {
                            Log.d("SyncManager", "  -> Skipped due to emulator filter")
                            continue
                        }
                        
                        // Get game details to find platform and game name
                        try {
                            val game = apiService.getGame(save.rom_id)
                            
                            // Apply platform filter if specified
                            if (syncRequest.platformFilter != null && 
                                !game.platform_slug.equals(syncRequest.platformFilter, ignoreCase = true)) {
                                Log.d("SyncManager", "  -> Skipped due to platform filter (${game.platform_slug} != ${syncRequest.platformFilter})")
                                continue
                            }
                            
                            remoteItems.add(RemoteSyncItem(
                                saveFile = save,
                                type = SyncItemType.SAVE_FILE,
                                platform = game.platform_slug,
                                emulator = save.emulator,
                                gameName = game.name ?: game.fs_name_no_ext,
                                fileName = save.file_name,
                                lastModified = parseDateTime(save.updated_at ?: save.created_at),
                                sizeBytes = save.file_size_bytes,
                                romId = save.rom_id
                            ))
                            
                            Log.d("SyncManager", "  -> Added save file: ${save.file_name} for game: ${game.name ?: game.fs_name_no_ext} (${game.platform_slug})")
                        } catch (e: Exception) {
                            Log.w("SyncManager", "Could not fetch game details for ROM ID ${save.rom_id}, skipping save file ${save.file_name}", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SyncManager", "Failed to load save files", e)
                }
            } else {
                Log.d("SyncManager", "Save files sync is disabled")
            }
            
            // Get all save states in one call
            if (syncRequest.saveStatesEnabled) {
                try {
                    Log.d("SyncManager", "Fetching all save states from server...")
                    val states = apiService.getStates()
                    Log.d("SyncManager", "Retrieved ${states.size} save states from server")
                    
                    for ((index, state) in states.withIndex()) {
                        Log.d("SyncManager", "Processing save state ${index + 1}/${states.size}: ${state.file_name} (ROM ID: ${state.rom_id})")
                        
                        // Apply emulator filter if specified
                        if (syncRequest.emulatorFilter != null && 
                            state.emulator?.equals(syncRequest.emulatorFilter, ignoreCase = true) != true) {
                            Log.d("SyncManager", "  -> Skipped due to emulator filter")
                            continue
                        }
                        
                        // Get game details to find platform and game name
                        try {
                            val game = apiService.getGame(state.rom_id)
                            
                            // Apply platform filter if specified
                            if (syncRequest.platformFilter != null && 
                                !game.platform_slug.equals(syncRequest.platformFilter, ignoreCase = true)) {
                                Log.d("SyncManager", "  -> Skipped due to platform filter (${game.platform_slug} != ${syncRequest.platformFilter})")
                                continue
                            }
                            
                            remoteItems.add(RemoteSyncItem(
                                saveState = state,
                                type = SyncItemType.SAVE_STATE,
                                platform = game.platform_slug,
                                emulator = state.emulator,
                                gameName = game.name ?: game.fs_name_no_ext,
                                fileName = state.file_name,
                                lastModified = parseDateTime(state.updated_at ?: state.created_at),
                                sizeBytes = state.file_size_bytes,
                                romId = state.rom_id
                            ))
                            
                            Log.d("SyncManager", "  -> Added save state: ${state.file_name} for game: ${game.name ?: game.fs_name_no_ext} (${game.platform_slug})")
                        } catch (e: Exception) {
                            Log.w("SyncManager", "Could not fetch game details for ROM ID ${state.rom_id}, skipping save state ${state.file_name}", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SyncManager", "Failed to load save states", e)
                }
            } else {
                Log.d("SyncManager", "Save states sync is disabled")
            }
            
        } catch (e: Exception) {
            Log.e("SyncManager", "Error getting remote items", e)
        }
        
        Log.d("SyncManager", "Final remote items count: ${remoteItems.size}")
        return remoteItems
    }
    
    
    private fun parseDateTime(dateString: String?): LocalDateTime {
        if (dateString.isNullOrBlank()) {
            return LocalDateTime.now()
        }
        
        return try {
            // Try various date formats that RomM might use
            when {
                dateString.contains('T') -> LocalDateTime.parse(dateString.substringBefore('.'))
                else -> LocalDateTime.now() // Fallback
            }
        } catch (e: DateTimeParseException) {
            Log.w("SyncManager", "Could not parse date: $dateString", e)
            LocalDateTime.now()
        }
    }
    
    private fun createSyncComparisons(
        localItems: List<LocalSyncItem>,
        remoteItems: List<RemoteSyncItem>,
        direction: SyncDirection
    ): List<SyncComparison> {
        val comparisons = mutableListOf<SyncComparison>()
        
        // Create a map for quick lookup
        val remoteByIdentifier = remoteItems.associateBy { "${it.platform}/${it.fileName}" }
        val localByIdentifier = localItems.associateBy { "${it.platform}/${it.fileName}" }
        
        // Find matches and conflicts
        val allIdentifiers = (localItems.map { "${it.platform}/${it.fileName}" } + 
                             remoteItems.map { "${it.platform}/${it.fileName}" }).distinct()
        
        for (identifier in allIdentifiers) {
            val localItem = localByIdentifier[identifier]
            val remoteItem = remoteByIdentifier[identifier]
            
            val comparison = when {
                localItem != null && remoteItem != null -> {
                    // Both exist - compare timestamps
                    compareItems(localItem, remoteItem, direction)
                }
                localItem != null && remoteItem == null -> {
                    // Only local exists
                    when (direction) {
                        SyncDirection.UPLOAD_ONLY, SyncDirection.BIDIRECTIONAL -> 
                            SyncComparison(localItem, null, SyncAction.UPLOAD, "File only exists locally")
                        SyncDirection.DOWNLOAD_ONLY ->
                            SyncComparison(localItem, null, SyncAction.SKIP_CONFLICT, "Download-only mode, local file exists")
                    }
                }
                localItem == null && remoteItem != null -> {
                    // Only remote exists  
                    when (direction) {
                        SyncDirection.DOWNLOAD_ONLY, SyncDirection.BIDIRECTIONAL ->
                            SyncComparison(null, remoteItem, SyncAction.DOWNLOAD, "File only exists remotely")
                        SyncDirection.UPLOAD_ONLY ->
                            SyncComparison(null, remoteItem, SyncAction.SKIP_CONFLICT, "Upload-only mode, remote file exists")
                    }
                }
                else -> continue // Should not happen
            }
            
            comparisons.add(comparison)
        }
        
        return comparisons
    }
    
    private fun compareItems(
        localItem: LocalSyncItem,
        remoteItem: RemoteSyncItem,
        direction: SyncDirection
    ): SyncComparison {
        val localTime = localItem.lastModified
        val remoteTime = remoteItem.lastModified
        val localSize = localItem.sizeBytes
        val remoteSize = remoteItem.sizeBytes
        
        return when {
            // Files are identical (same size and timestamp within 1 second tolerance)
            localSize == remoteSize && Math.abs(java.time.Duration.between(localTime, remoteTime).seconds) <= 1 -> {
                SyncComparison(localItem, remoteItem, SyncAction.SKIP_IDENTICAL, "Files are identical")
            }
            
            // Local file is newer
            localTime.isAfter(remoteTime) -> {
                when (direction) {
                    SyncDirection.UPLOAD_ONLY, SyncDirection.BIDIRECTIONAL ->
                        SyncComparison(localItem, remoteItem, SyncAction.UPLOAD, "Local file is newer")
                    SyncDirection.DOWNLOAD_ONLY ->
                        SyncComparison(localItem, remoteItem, SyncAction.SKIP_CONFLICT, "Local file newer but download-only mode")
                }
            }
            
            // Remote file is newer
            remoteTime.isAfter(localTime) -> {
                when (direction) {
                    SyncDirection.DOWNLOAD_ONLY, SyncDirection.BIDIRECTIONAL ->
                        SyncComparison(localItem, remoteItem, SyncAction.DOWNLOAD, "Remote file is newer")
                    SyncDirection.UPLOAD_ONLY ->
                        SyncComparison(localItem, remoteItem, SyncAction.SKIP_CONFLICT, "Remote file newer but upload-only mode")
                }
            }
            
            // Same timestamp but different size - conflict
            else -> {
                SyncComparison(
                    localItem, 
                    remoteItem, 
                    SyncAction.SKIP_CONFLICT, 
                    "Files have same timestamp but different sizes (${localSize} vs ${remoteSize} bytes)"
                )
            }
        }
    }
    
    private suspend fun performUpload(comparison: SyncComparison, settings: AppSettings): Boolean {
        val localItem = comparison.localItem ?: return false
        val remoteItem = comparison.remoteItem
        
        return try {
            // Get DocumentFile for the local file
            val baseUri = when (localItem.type) {
                SyncItemType.SAVE_FILE -> settings.saveFilesDirectory
                SyncItemType.SAVE_STATE -> settings.saveStatesDirectory
            }
            
            Log.d("SyncManager", "Getting DocumentFile for upload:")
            Log.d("SyncManager", "  baseUri: '$baseUri'")
            Log.d("SyncManager", "  relativePathFromBaseDir: '${localItem.relativePathFromBaseDir}'")
            
            val documentFile = fileScanner.getDocumentFileForPath(baseUri, localItem.relativePathFromBaseDir)
            if (documentFile == null) {
                Log.e("SyncManager", "Could not get DocumentFile for ${localItem.fileName}")
                return false
            }
            
            Log.d("SyncManager", "  DocumentFile URI: ${documentFile.uri}")
            Log.d("SyncManager", "  DocumentFile exists: ${documentFile.exists()}")
            Log.d("SyncManager", "  DocumentFile name: ${documentFile.name}")
            Log.d("SyncManager", "  DocumentFile size: ${documentFile.length()}")
                
            // Find the ROM ID by matching game name and platform
            val romId = findRomIdForFile(localItem)
            if (romId == null) {
                Log.w("SyncManager", "Could not find ROM ID for ${localItem.fileName}")
                return false
            }
            
            when (localItem.type) {
                SyncItemType.SAVE_FILE -> {
                    if (remoteItem != null) {
                        // Update existing
                        apiService.updateSaveFile(remoteItem.id, documentFile)
                    } else {
                        // Create new
                        apiService.uploadSaveFile(romId, localItem.emulator, documentFile)
                    }
                }
                SyncItemType.SAVE_STATE -> {
                    if (remoteItem != null) {
                        // Update existing
                        apiService.updateSaveState(remoteItem.id, documentFile)
                    } else {
                        // Create new
                        apiService.uploadSaveState(romId, localItem.emulator, documentFile)
                    }
                }
            }
            
            Log.d("SyncManager", "Successfully uploaded ${localItem.fileName}")
            true
            
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e("SyncManager", "HTTP ${e.code()} uploading ${localItem.fileName}: $errorBody", e)
            false
        } catch (e: Exception) {
            Log.e("SyncManager", "Failed to upload ${localItem.fileName}", e)
            false
        }
    }
    
    private suspend fun findRomIdForFile(localItem: LocalSyncItem): Int? {
        return try {
            Log.d("SyncManager", "Finding ROM ID for file: ${localItem.fileName}")
            Log.d("SyncManager", "  Platform: ${localItem.platform}")  
            Log.d("SyncManager", "  Game name from file: '${localItem.gameName}'")
            
            // Extract the base filename without extension for matching
            val baseFileName = localItem.fileName.substringBeforeLast(".")
            Log.d("SyncManager", "  Base filename: '$baseFileName'")
            
            // Search for games by name and platform
            val platforms = apiService.getPlatforms()
            val platform = platforms.find { 
                it.slug.equals(localItem.platform, ignoreCase = true) ||
                it.display_name.equals(localItem.platform, ignoreCase = true)
            }
            
            if (platform == null) {
                Log.w("SyncManager", "  Could not find platform: ${localItem.platform}")
                return null
            }
            
            Log.d("SyncManager", "  Found platform: ${platform.display_name} (${platform.slug})")
            
            // Use search to find games more efficiently
            val searchTerm = baseFileName.take(10) // Use first 10 chars to search  
            val games = apiService.getGames(platformId = platform.id, searchTerm = searchTerm)
            Log.d("SyncManager", "  Searched for '$searchTerm' and found ${games.size} games")
            
            // Try to match using multiple strategies
            val gameName = localItem.gameName?.lowercase()
            val baseFileNameLower = baseFileName.lowercase()
            
            Log.d("SyncManager", "  Searching for matches with:")
            Log.d("SyncManager", "    gameName: '$gameName'")
            Log.d("SyncManager", "    baseFileName: '$baseFileNameLower'")
            
            // Try different matching strategies
            val matchedGame = games.find { game ->
                val nameMatchWithGameName = game.name?.lowercase() == gameName
                val fsNameNoExtMatchWithGameName = game.fs_name_no_ext.lowercase() == gameName
                val nameMatchWithFileName = game.name?.lowercase() == baseFileNameLower
                val fsNameNoExtMatchWithFileName = game.fs_name_no_ext.lowercase() == baseFileNameLower
                val fsNameStartsMatchWithFileName = game.fs_name.lowercase().startsWith(baseFileNameLower)
                
                if (game.name?.lowercase()?.contains("stellvia") == true) {
                    Log.d("SyncManager", "    STELLVIA GAME FOUND: '${game.name}' / '${game.fs_name_no_ext}' / '${game.fs_name}'")
                    Log.d("SyncManager", "      nameMatchWithGameName: $nameMatchWithGameName")
                    Log.d("SyncManager", "      fsNameNoExtMatchWithGameName: $fsNameNoExtMatchWithGameName") 
                    Log.d("SyncManager", "      nameMatchWithFileName: $nameMatchWithFileName")
                    Log.d("SyncManager", "      fsNameNoExtMatchWithFileName: $fsNameNoExtMatchWithFileName")
                    Log.d("SyncManager", "      fsNameStartsMatchWithFileName: $fsNameStartsMatchWithFileName")
                }
                
                nameMatchWithGameName || fsNameNoExtMatchWithGameName || 
                nameMatchWithFileName || fsNameNoExtMatchWithFileName || fsNameStartsMatchWithFileName
            }
            
            if (matchedGame != null) {
                Log.d("SyncManager", "  Found matching game: ${matchedGame.name} (ID: ${matchedGame.id})")
                return matchedGame.id
            } else {
                Log.w("SyncManager", "  No matching game found!")
                return null
            }
            
        } catch (e: Exception) {
            Log.e("SyncManager", "Error finding ROM ID for ${localItem.fileName}", e)
            null
        }
    }
    
    private suspend fun performDownload(comparison: SyncComparison, settings: AppSettings): Boolean {
        val remoteItem = comparison.remoteItem ?: return false
        
        return try {
            when (remoteItem.type) {
                SyncItemType.SAVE_FILE -> {
                    val saveFile = remoteItem.saveFile ?: return false
                    downloadManager.downloadSaveFiles(listOf(saveFile), settings)
                }
                SyncItemType.SAVE_STATE -> {
                    val saveState = remoteItem.saveState ?: return false
                    downloadManager.downloadSaveStates(listOf(saveState), settings)
                }
            }
            
            Log.d("SyncManager", "Successfully downloaded ${remoteItem.fileName}")
            true
            
        } catch (e: Exception) {
            Log.e("SyncManager", "Failed to download ${remoteItem.fileName}", e)
            false
        }
    }
}
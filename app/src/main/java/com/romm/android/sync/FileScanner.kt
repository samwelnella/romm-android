package com.romm.android.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.romm.android.data.AppSettings
import com.romm.android.utils.PlatformMapper
import com.romm.android.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileScanner @Inject constructor(
    private val context: Context
) {
    
    suspend fun scanLocalSaveFiles(settings: AppSettings): List<LocalSyncItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<LocalSyncItem>()

        AppLogger.v(tag = "FileScanner", message = "Starting scan with settings:")
        AppLogger.v(tag = "FileScanner", message = "Save Files Directory: '${settings.saveFilesDirectory}'")
        AppLogger.v(tag = "FileScanner", message = "Save States Directory: '${settings.saveStatesDirectory}'")

        if (settings.saveFilesDirectory.isNotEmpty()) {
            AppLogger.i(tag = "FileScanner", message = "Scanning save files directory...")
            val saveFilesItems = scanDirectory(
                settings.saveFilesDirectory,
                SyncItemType.SAVE_FILE,
                "Save Files"
            )
            AppLogger.i(tag = "FileScanner", message = "Found ${saveFilesItems.size} save files")
            items.addAll(saveFilesItems)
        } else {
            AppLogger.w(tag = "FileScanner", message = "Save files directory is not configured")
        }

        if (settings.saveStatesDirectory.isNotEmpty()) {
            AppLogger.i(tag = "FileScanner", message = "Scanning save states directory...")
            val saveStatesItems = scanDirectory(
                settings.saveStatesDirectory,
                SyncItemType.SAVE_STATE,
                "Save States"
            )
            AppLogger.i(tag = "FileScanner", message = "Found ${saveStatesItems.size} save states")
            items.addAll(saveStatesItems)
        } else {
            AppLogger.w(tag = "FileScanner", message = "Save states directory is not configured")
        }

        AppLogger.i(tag = "FileScanner", message = "Total found: ${items.size} local sync items")
        items
    }
    
    private suspend fun scanDirectory(
        directoryUri: String,
        type: SyncItemType,
        baseTypeName: String
    ): List<LocalSyncItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<LocalSyncItem>()
        
        try {
            AppLogger.v(tag = "FileScanner", message = "Attempting to access directory: $directoryUri")
            val baseDir = DocumentFile.fromTreeUri(context, Uri.parse(directoryUri))
            if (baseDir == null) {
                AppLogger.e(tag = "FileScanner", message = "$baseTypeName directory could not be parsed: $directoryUri")
                return@withContext items
            }

            if (!baseDir.exists()) {
                AppLogger.e(tag = "FileScanner", message = "$baseTypeName directory does not exist: $directoryUri")
                return@withContext items
            }

            if (!baseDir.isDirectory) {
                AppLogger.e(tag = "FileScanner", message = "$baseTypeName path is not a directory: $directoryUri")
                return@withContext items
            }

            AppLogger.v(tag = "FileScanner", message = "$baseTypeName directory accessible, scanning...")
            scanDirectoryRecursive(
                directory = baseDir,
                type = type,
                items = items,
                basePath = "",
                maxDepth = 3 // Scan [platform]/[emulator]/[files] - allow one more level for actual files
            )
            
        } catch (e: Exception) {
            AppLogger.e(tag = "FileScanner", message = "Error scanning $baseTypeName directory", throwable = e)
        }
        
        items
    }
    
    private fun scanDirectoryRecursive(
        directory: DocumentFile,
        type: SyncItemType,
        items: MutableList<LocalSyncItem>,
        basePath: String,
        maxDepth: Int
    ) {
        if (maxDepth <= 0) {
            AppLogger.w(tag = "FileScanner", message = "Max depth reached, stopping recursion")
            return
        }
        
        try {
            val files = directory.listFiles()
            AppLogger.v(tag = "FileScanner", message = "Directory '${directory.name}' contains ${files?.size ?: 0} items")

            files?.forEach { file ->
                if (file.isDirectory) {
                    // Recurse into subdirectories
                    val subPath = if (basePath.isEmpty()) file.name ?: "" else "$basePath/${file.name}"
                    scanDirectoryRecursive(file, type, items, subPath, maxDepth - 1)
                } else if (file.isFile) {
                    // Check if this is a save file/state we care about
                    val fileName = file.name ?: return@forEach
                    val fileSize = file.length()

                    AppLogger.v(tag = "FileScanner", message = "Found file: $fileName (${fileSize} bytes)")

                    if (isSyncableFile(fileName, type)) {
                        val metadata = extractFileMetadata(basePath, fileName, type)

                        AppLogger.v(tag = "FileScanner", message = "Syncable file: $fileName -> Platform: ${metadata.platform}, Emulator: ${metadata.emulator}")
                        
                        // Add all files with valid extensions - no emulator whitelist needed
                        items.add(
                            LocalSyncItem(
                                file = File(file.uri.path ?: ""), // This might not be a real path
                                type = type,
                                platform = metadata.platform,
                                emulator = metadata.emulator,
                                gameName = metadata.gameName,
                                fileName = fileName,
                                lastModified = LocalDateTime.ofInstant(
                                    Instant.ofEpochMilli(file.lastModified()),
                                    ZoneId.systemDefault()
                                ),
                                sizeBytes = fileSize,
                                relativePathFromBaseDir = if (basePath.isEmpty()) fileName else "$basePath/$fileName"
                            )
                        )
                    } else {
                        AppLogger.v(tag = "FileScanner", message = "File '$fileName' is not syncable for type $type")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(tag = "FileScanner", message = "Error scanning directory: ${directory.name}", throwable = e)
        }
    }
    
    private fun isSyncableFile(fileName: String, type: SyncItemType): Boolean {
        val lowerName = fileName.lowercase()
        
        return when (type) {
            SyncItemType.SAVE_FILE -> {
                // Exclude PNG files (these are screenshots handled separately)
                if (lowerName.endsWith(".png")) {
                    return false
                }
                
                // Common save file extensions
                lowerName.endsWith(".sav") ||
                lowerName.endsWith(".srm") ||
                lowerName.endsWith(".save") ||
                lowerName.endsWith(".sav") ||
                lowerName.endsWith(".mcr") ||
                lowerName.endsWith(".mc") ||
                lowerName.endsWith(".gme") ||
                lowerName.endsWith(".fla") ||
                lowerName.endsWith(".dat") ||
                lowerName.endsWith(".eep") ||
                lowerName.endsWith(".bkp") ||
                // CD-ROM specific save files (e.g., Game.cdrom.srm)
                lowerName.contains(".cdrom.") ||
                // RetroArch naming patterns
                (lowerName.contains(".") && !lowerName.endsWith(".state") && !lowerName.endsWith(".st") && !lowerName.endsWith(".png"))
            }
            SyncItemType.SAVE_STATE -> {
                // Exclude PNG files (these are screenshots handled separately)
                if (lowerName.endsWith(".png")) {
                    return false
                }
                
                // Common save state extensions
                lowerName.endsWith(".state") ||
                lowerName.endsWith(".st") ||
                lowerName.endsWith(".st0") ||
                lowerName.endsWith(".st1") ||
                lowerName.endsWith(".st2") ||
                lowerName.endsWith(".st3") ||
                lowerName.endsWith(".st4") ||
                lowerName.endsWith(".st5") ||
                lowerName.endsWith(".st6") ||
                lowerName.endsWith(".st7") ||
                lowerName.endsWith(".st8") ||
                lowerName.endsWith(".st9") ||
                lowerName.endsWith(".ss0") ||
                lowerName.endsWith(".ss1") ||
                lowerName.endsWith(".sts") ||
                lowerName.endsWith(".savestate") ||
                // Emulator-specific patterns
                lowerName.contains("state") ||
                lowerName.contains("slot")
            }
        }
    }
    
    data class FileMetadata(
        val platform: String,
        val emulator: String?,
        val gameName: String?
    )
    
    private fun extractFileMetadata(basePath: String, fileName: String, type: SyncItemType): FileMetadata {
        // Extract platform and emulator info from directory structure
        val pathParts = basePath.split("/").filter { it.isNotEmpty() }
        
        // Common patterns:
        // RetroArch: saves/platform_name/game_name.srm
        // Standalone: emulator_name/platform_name/game_name.sav
        // Simple: platform_name/game_name.ext
        
        val platform = when {
            pathParts.isNotEmpty() -> {
                // Try to identify platform from path using PlatformMapper
                val firstPart = pathParts.first()
                // Convert ES-DE folder name to RomM platform slug
                val mappedPlatform = PlatformMapper.getRommSlugFromEsdeFolder(firstPart)
                AppLogger.v(tag = "FileScanner", message = "Platform mapping: '$firstPart' -> '$mappedPlatform'")
                mappedPlatform
            }
            else -> {
                // Try to infer from filename
                inferPlatformFromFilename(fileName)
            }
        }
        
        val emulator = when {
            pathParts.size >= 2 -> {
                // Second part is emulator: [PLATFORM_NAME]/[EMULATOR_NAME]/savefile
                pathParts[1]
            }
            pathParts.size == 1 -> {
                // First part might be emulator if no platform folder
                pathParts[0]
            }
            else -> null
        }
        
        // Extract game name from filename (remove all save file extensions)
        val gameNameFromFile = extractGameNameFromFileName(fileName)
        val gameName = when {
            pathParts.size >= 2 -> pathParts.last() // Directory name as game name
            gameNameFromFile.isNotBlank() -> gameNameFromFile
            else -> null
        }
        
        return FileMetadata(
            platform = platform,
            emulator = emulator,
            gameName = gameName
        )
    }
    
    
    
    private fun inferPlatformFromFilename(fileName: String): String {
        // Try to infer platform from filename patterns
        // This is a fallback when directory structure doesn't give us info
        return "unknown"
    }
    
    fun getDocumentFileForPath(baseUri: String, relativePath: String): DocumentFile? {
        try {
            AppLogger.v(tag = "FileScanner", message = "getDocumentFileForPath: baseUri='$baseUri', relativePath='$relativePath'")

            val baseDir = DocumentFile.fromTreeUri(context, Uri.parse(baseUri))
            if (baseDir == null) {
                AppLogger.e(tag = "FileScanner", message = "Failed to parse base URI: $baseUri")
                return null
            }

            if (relativePath.isEmpty()) {
                AppLogger.v(tag = "FileScanner", message = "Empty relative path, returning base directory")
                return baseDir
            }

            // Split path and navigate step by step
            val pathParts = relativePath.split("/").filter { it.isNotEmpty() }
            AppLogger.v(tag = "FileScanner", message = "Path parts: $pathParts")

            var currentDir: DocumentFile? = baseDir

            // Navigate to all parts (including the final file)
            for ((index, part) in pathParts.withIndex()) {
                if (currentDir == null) {
                    AppLogger.e(tag = "FileScanner", message = "Current directory became null at part $index")
                    return null
                }

                AppLogger.v(tag = "FileScanner", message = "Looking for part ${index + 1}/${pathParts.size}: '$part' in ${currentDir.name ?: "unknown"}")

                val foundItem = currentDir.findFile(part)
                if (foundItem == null) {
                    AppLogger.e(tag = "FileScanner", message = "Could not find '$part' in directory '${currentDir.name ?: "unknown"}'")

                    // Debug: List all files in current directory
                    val files = currentDir.listFiles()
                    AppLogger.v(tag = "FileScanner", message = "Directory '${currentDir.name ?: "unknown"}' contains:")
                    files?.forEach { file ->
                        AppLogger.v(tag = "FileScanner", message = "  - '${file.name ?: "unknown"}' (${if (file.isDirectory) "DIR" else "FILE"})")
                    }

                    return null
                }

                AppLogger.v(tag = "FileScanner", message = "Found '$part': ${foundItem.name ?: "unknown"} (${if (foundItem.isDirectory) "DIR" else "FILE"})")
                currentDir = foundItem
            }

            AppLogger.v(tag = "FileScanner", message = "Successfully found file: ${currentDir?.name ?: "unknown"}")
            return currentDir
            
        } catch (e: Exception) {
            AppLogger.e(tag = "FileScanner", message = "Error getting DocumentFile for path: $relativePath", throwable = e)
            return null
        }
    }

    private fun extractGameNameFromFileName(fileName: String): String {
        // Known save file extensions that should be removed from game name
        val saveFileExtensions = listOf(
            ".srm", ".sav", ".save", ".mcr", ".mc", ".gme", ".fla", ".dat", ".eep", ".bkp",
            ".state", ".st", ".st0", ".st1", ".st2", ".st3", ".st4", ".st5", ".st6", ".st7", ".st8", ".st9",
            ".ss0", ".ss1", ".sts", ".savestate",
            ".cdrom" // Add cdrom extension for games like "Alien vs Predator.cdrom.srm"
        )

        var gameName = fileName

        // Remove extensions from the end, in order of priority
        // This handles cases like "Game.cdrom.srm" -> "Game"
        for (extension in saveFileExtensions.sortedByDescending { it.length }) {
            if (gameName.lowercase().endsWith(extension.lowercase())) {
                gameName = gameName.dropLast(extension.length)
                break // Only remove one extension per pass
            }
        }

        // If we still have extensions, try again (for cases like .cdrom.srm)
        if (gameName != fileName) {
            for (extension in saveFileExtensions.sortedByDescending { it.length }) {
                if (gameName.lowercase().endsWith(extension.lowercase())) {
                    gameName = gameName.dropLast(extension.length)
                    break
                }
            }
        }

        return gameName.ifBlank { fileName.substringBeforeLast(".") }
    }
}
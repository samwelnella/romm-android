package com.romm.android.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.romm.android.data.AppSettings
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
        
        Log.d("FileScanner", "Starting scan with settings:")
        Log.d("FileScanner", "Save Files Directory: '${settings.saveFilesDirectory}'")
        Log.d("FileScanner", "Save States Directory: '${settings.saveStatesDirectory}'")
        
        if (settings.saveFilesDirectory.isNotEmpty()) {
            Log.d("FileScanner", "Scanning save files directory...")
            val saveFilesItems = scanDirectory(
                settings.saveFilesDirectory,
                SyncItemType.SAVE_FILE,
                "Save Files"
            )
            Log.d("FileScanner", "Found ${saveFilesItems.size} save files")
            items.addAll(saveFilesItems)
        } else {
            Log.w("FileScanner", "Save files directory is not configured")
        }
        
        if (settings.saveStatesDirectory.isNotEmpty()) {
            Log.d("FileScanner", "Scanning save states directory...")
            val saveStatesItems = scanDirectory(
                settings.saveStatesDirectory,
                SyncItemType.SAVE_STATE,
                "Save States"
            )
            Log.d("FileScanner", "Found ${saveStatesItems.size} save states")
            items.addAll(saveStatesItems)
        } else {
            Log.w("FileScanner", "Save states directory is not configured")
        }
        
        Log.d("FileScanner", "Total found: ${items.size} local sync items")
        items
    }
    
    private suspend fun scanDirectory(
        directoryUri: String,
        type: SyncItemType,
        baseTypeName: String
    ): List<LocalSyncItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<LocalSyncItem>()
        
        try {
            Log.d("FileScanner", "Attempting to access directory: $directoryUri")
            val baseDir = DocumentFile.fromTreeUri(context, Uri.parse(directoryUri))
            if (baseDir == null) {
                Log.e("FileScanner", "$baseTypeName directory could not be parsed: $directoryUri")
                return@withContext items
            }
            
            if (!baseDir.exists()) {
                Log.e("FileScanner", "$baseTypeName directory does not exist: $directoryUri")
                return@withContext items
            }
            
            if (!baseDir.isDirectory) {
                Log.e("FileScanner", "$baseTypeName path is not a directory: $directoryUri")
                return@withContext items
            }
            
            Log.d("FileScanner", "$baseTypeName directory accessible, scanning...")
            scanDirectoryRecursive(
                directory = baseDir,
                type = type,
                items = items,
                basePath = "",
                maxDepth = 2 // Only scan [platform]/[emulator]/ - ignore deeper subdirectories
            )
            
        } catch (e: Exception) {
            Log.e("FileScanner", "Error scanning $baseTypeName directory", e)
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
            Log.w("FileScanner", "Max depth reached, stopping recursion")
            return
        }
        
        try {
            val files = directory.listFiles()
            Log.d("FileScanner", "Directory '${directory.name}' contains ${files?.size ?: 0} items")
            
            files?.forEach { file ->
                if (file.isDirectory) {
                    // Recurse into subdirectories
                    val subPath = if (basePath.isEmpty()) file.name ?: "" else "$basePath/${file.name}"
                    scanDirectoryRecursive(file, type, items, subPath, maxDepth - 1)
                } else if (file.isFile) {
                    // Check if this is a save file/state we care about
                    val fileName = file.name ?: return@forEach
                    val fileSize = file.length()
                    
                    Log.d("FileScanner", "Found file: $fileName (${fileSize} bytes)")
                    
                    if (isSyncableFile(fileName, type)) {
                        val metadata = extractFileMetadata(basePath, fileName, type)
                        
                        Log.d("FileScanner", "Syncable file: $fileName -> Platform: ${metadata.platform}, Emulator: ${metadata.emulator}")
                        
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
                        Log.d("FileScanner", "File '$fileName' is not syncable for type $type")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FileScanner", "Error scanning directory: ${directory.name}", e)
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
                // Try to identify platform from path
                val firstPart = pathParts.first().lowercase()
                mapDirectoryToPlatform(firstPart)
            }
            else -> {
                // Try to infer from filename
                inferPlatformFromFilename(fileName)
            }
        }
        
        val emulator = when {
            pathParts.size >= 2 -> {
                // Second part is emulator: [PLATFORM_NAME]/[EMULATOR_NAME]/savefile
                identifyEmulator(pathParts[1])
            }
            pathParts.size == 1 -> {
                // First part might be emulator if no platform folder
                identifyEmulator(pathParts[0])
            }
            else -> null
        }
        
        // Extract game name from filename (remove extension)
        val gameNameFromFile = fileName.substringBeforeLast(".")
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
    
    private fun mapDirectoryToPlatform(directoryName: String): String {
        val lower = directoryName.lowercase()
        return when {
            // Nintendo systems
            lower.contains("nes") && !lower.contains("snes") -> "nes"
            lower.contains("snes") || lower.contains("super nintendo") -> "snes"
            lower.contains("n64") || lower.contains("nintendo 64") -> "n64"
            lower.contains("gamecube") || lower.contains("gc") -> "gc" 
            lower.contains("wii") && !lower.contains("wiiu") -> "wii"
            lower.contains("wiiu") || lower.contains("wii u") -> "wiiu"
            lower.contains("gb") && !lower.contains("gba") -> "gb"
            lower.contains("gbc") || lower.contains("game boy color") -> "gbc"
            lower.contains("gba") || lower.contains("game boy advance") -> "gba"
            lower.contains("nds") || lower.contains("nintendo ds") -> "nds"
            lower.contains("3ds") || lower.contains("nintendo 3ds") -> "3ds"
            
            // Sega systems  
            lower.contains("genesis") || lower.contains("megadrive") -> "genesis"
            lower.contains("sega cd") || lower.contains("segacd") -> "segacd"
            lower.contains("saturn") -> "saturn"
            lower.contains("dreamcast") -> "dreamcast"
            lower.contains("master system") || lower.contains("mastersystem") -> "sms"
            lower.contains("game gear") || lower.contains("gamegear") -> "gg"
            
            // Sony systems
            lower.contains("psx") || lower.contains("playstation") && !lower.contains("2") -> "psx"
            lower.contains("ps2") || lower.contains("playstation 2") -> "ps2"
            lower.contains("psp") -> "psp"
            
            // Arcade
            lower.contains("arcade") || lower.contains("mame") -> "arcade"
            
            // Other systems
            lower.contains("atari2600") || lower.contains("atari 2600") -> "atari2600"
            lower.contains("lynx") -> "lynx"
            
            // Default to directory name if no mapping found
            else -> directoryName
        }
    }
    
    private fun identifyEmulator(directoryName: String): String? {
        return EmulatorMapper.identifyEmulator(directoryName)
    }
    
    private fun inferPlatformFromFilename(fileName: String): String {
        // Try to infer platform from filename patterns
        // This is a fallback when directory structure doesn't give us info
        return "unknown"
    }
    
    fun getDocumentFileForPath(baseUri: String, relativePath: String): DocumentFile? {
        try {
            Log.d("FileScanner", "getDocumentFileForPath: baseUri='$baseUri', relativePath='$relativePath'")
            
            val baseDir = DocumentFile.fromTreeUri(context, Uri.parse(baseUri))
            if (baseDir == null) {
                Log.e("FileScanner", "Failed to parse base URI: $baseUri")
                return null
            }
            
            if (relativePath.isEmpty()) {
                Log.d("FileScanner", "Empty relative path, returning base directory")
                return baseDir
            }
            
            // Split path and navigate step by step
            val pathParts = relativePath.split("/").filter { it.isNotEmpty() }
            Log.d("FileScanner", "Path parts: $pathParts")
            
            var currentDir: DocumentFile? = baseDir
            
            // Navigate to all parts (including the final file)
            for ((index, part) in pathParts.withIndex()) {
                if (currentDir == null) {
                    Log.e("FileScanner", "Current directory became null at part $index")
                    return null
                }
                
                Log.d("FileScanner", "Looking for part ${index + 1}/${pathParts.size}: '$part' in ${currentDir.name ?: "unknown"}")
                
                val foundItem = currentDir.findFile(part)
                if (foundItem == null) {
                    Log.e("FileScanner", "Could not find '$part' in directory '${currentDir.name ?: "unknown"}'")
                    
                    // Debug: List all files in current directory
                    val files = currentDir.listFiles()
                    Log.d("FileScanner", "Directory '${currentDir.name ?: "unknown"}' contains:")
                    files?.forEach { file ->
                        Log.d("FileScanner", "  - '${file.name ?: "unknown"}' (${if (file.isDirectory) "DIR" else "FILE"})")
                    }
                    
                    return null
                }
                
                Log.d("FileScanner", "Found '$part': ${foundItem.name ?: "unknown"} (${if (foundItem.isDirectory) "DIR" else "FILE"})")
                currentDir = foundItem
            }
            
            Log.d("FileScanner", "Successfully found file: ${currentDir?.name ?: "unknown"}")
            return currentDir
            
        } catch (e: Exception) {
            Log.e("FileScanner", "Error getting DocumentFile for path: $relativePath", e)
            return null
        }
    }
}
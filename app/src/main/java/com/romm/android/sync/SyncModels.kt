package com.romm.android.sync

import com.romm.android.data.Game
import com.romm.android.data.SaveFile
import com.romm.android.data.SaveState
import java.io.File
import java.time.LocalDateTime

enum class SyncDirection {
    UPLOAD_ONLY,
    DOWNLOAD_ONLY,
    BIDIRECTIONAL
}

enum class SyncItemType {
    SAVE_FILE,
    SAVE_STATE
}

enum class SyncAction {
    UPLOAD,
    DOWNLOAD,
    SKIP_CONFLICT,
    SKIP_IDENTICAL
}

data class LocalSyncItem(
    val file: File,
    val type: SyncItemType,
    val platform: String,
    val emulator: String?,
    val gameName: String?,
    val fileName: String,
    val lastModified: LocalDateTime,
    val sizeBytes: Long,
    val relativePathFromBaseDir: String // e.g., "snes9x/Super Mario World/save1.srm"
)

data class RemoteSyncItem(
    val saveFile: SaveFile? = null,
    val saveState: SaveState? = null,
    val type: SyncItemType,
    val platform: String,
    val emulator: String?,
    val gameName: String?,
    val fileName: String,
    val lastModified: LocalDateTime,
    val sizeBytes: Long,
    val romId: Int
) {
    val id: Int get() = saveFile?.id ?: saveState?.id ?: 0
}

data class SyncComparison(
    val localItem: LocalSyncItem?,
    val remoteItem: RemoteSyncItem?,
    val recommendedAction: SyncAction,
    val reason: String
) {
    val identifier: String get() = localItem?.relativePathFromBaseDir ?: remoteItem?.fileName ?: ""
    val platform: String get() = localItem?.platform ?: remoteItem?.platform ?: ""
    val type: SyncItemType get() = localItem?.type ?: remoteItem?.type ?: SyncItemType.SAVE_FILE
}

data class SyncPlan(
    val direction: SyncDirection,
    val comparisons: List<SyncComparison>,
    val totalUploadCount: Int,
    val totalDownloadCount: Int,
    val totalSkipCount: Int,
    val estimatedUploadSize: Long,
    val estimatedDownloadSize: Long
) {
    val hasWork: Boolean get() = totalUploadCount > 0 || totalDownloadCount > 0
}

data class SyncProgress(
    val currentStep: String,
    val itemsProcessed: Int,
    val totalItems: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val isComplete: Boolean,
    val hasErrors: Boolean,
    val errors: List<String> = emptyList()
) {
    val progressPercent: Float get() = if (totalItems > 0) (itemsProcessed.toFloat() / totalItems) * 100f else 0f
}

data class SyncResult(
    val success: Boolean,
    val uploadedCount: Int,
    val downloadedCount: Int,
    val skippedCount: Int,
    val errorCount: Int,
    val errors: List<String>,
    val duration: Long // milliseconds
)

// For external app integration
data class SyncRequest(
    val direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
    val saveFilesEnabled: Boolean = true,
    val saveStatesEnabled: Boolean = true,
    val platformFilter: String? = null, // null means all platforms
    val emulatorFilter: String? = null, // null means all emulators
    val dryRun: Boolean = false // if true, only plan but don't execute
)

data class PlatformEmulatorMapping(
    val platformSlug: String,
    val platformDisplayName: String,
    val emulatorName: String,
    val saveFileExtensions: List<String>,
    val saveStateExtensions: List<String>,
    val saveFileSubdirectory: String? = null, // e.g., "saves" or "battery"
    val saveStateSubdirectory: String? = null // e.g., "states" or "sstates"
)
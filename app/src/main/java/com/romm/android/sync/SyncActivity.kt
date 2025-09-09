package com.romm.android.sync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.romm.android.data.SettingsRepository
import com.romm.android.network.RomMApiService
import com.romm.android.ui.theme.RomMTheme
import com.romm.android.utils.DownloadManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SyncActivity : ComponentActivity() {
    
    private val viewModel: SyncViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Parse intent parameters
        val syncRequest = parseSyncIntent(intent)
        Log.d("SyncActivity", "Sync request: $syncRequest")
        
        setContent {
            RomMTheme {
                SyncScreen(
                    viewModel = viewModel,
                    syncRequest = syncRequest,
                    onComplete = { result ->
                        // Return result to calling app
                        val resultIntent = Intent().apply {
                            putExtra("success", result.success)
                            putExtra("uploaded_count", result.uploadedCount)
                            putExtra("downloaded_count", result.downloadedCount)
                            putExtra("skipped_count", result.skippedCount)
                            putExtra("error_count", result.errorCount)
                            putExtra("duration", result.duration)
                            putStringArrayListExtra("errors", ArrayList(result.errors))
                        }
                        
                        setResult(
                            if (result.success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                            resultIntent
                        )
                        finish()
                    }
                )
            }
        }
    }
    
    private fun parseSyncIntent(intent: Intent): SyncRequest {
        return SyncRequest(
            direction = when (intent.getStringExtra("sync_direction")) {
                "upload" -> SyncDirection.UPLOAD_ONLY
                "download" -> SyncDirection.DOWNLOAD_ONLY
                "bidirectional" -> SyncDirection.BIDIRECTIONAL
                else -> SyncDirection.BIDIRECTIONAL
            },
            saveFilesEnabled = intent.getBooleanExtra("sync_save_files", true),
            saveStatesEnabled = intent.getBooleanExtra("sync_save_states", true),
            platformFilter = intent.getStringExtra("platform_filter"),
            emulatorFilter = intent.getStringExtra("emulator_filter"),
            gameFilter = intent.getStringExtra("game_filter"),
            dryRun = intent.getBooleanExtra("dry_run", false)
        )
    }
    
    companion object {
        /**
         * Create intent for external apps to call sync
         * 
         * Example usage from external app:
         * ```
         * val intent = SyncActivity.createSyncIntent(
         *     context,
         *     direction = "bidirectional", // "upload", "download", or "bidirectional"
         *     saveFiles = true,
         *     saveStates = true,
         *     platform = "snes", // optional - filter to specific platform
         *     emulator = "snes9x", // optional - filter to specific emulator
         *     game = "Super Mario World", // optional - filter to specific game (filename without extension)
         *     dryRun = false
         * )
         * startActivityForResult(intent, REQUEST_SYNC)
         * ```
         */
        fun createSyncIntent(
            packageName: String = "com.romm.android",
            direction: String = "bidirectional",
            saveFiles: Boolean = true,
            saveStates: Boolean = true,
            platform: String? = null,
            emulator: String? = null,
            game: String? = null,
            dryRun: Boolean = false
        ): Intent {
            return Intent().apply {
                setClassName(packageName, "com.romm.android.sync.SyncActivity")
                putExtra("sync_direction", direction)
                putExtra("sync_save_files", saveFiles)
                putExtra("sync_save_states", saveStates)
                platform?.let { putExtra("platform_filter", it) }
                emulator?.let { putExtra("emulator_filter", it) }
                game?.let { putExtra("game_filter", it) }
                putExtra("dry_run", dryRun)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncManager: SyncManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()
    
    fun startSync(syncRequest: SyncRequest) {
        viewModelScope.launch {
            try {
                val settings = settingsRepository.getCurrentSettings()
                
                // Validate settings
                if (settings.host.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        error = "RomM server not configured. Please configure in main app settings."
                    )
                    return@launch
                }
                
                // Merge sync request with settings-based history limits
                val updatedSyncRequest = syncRequest.copy(
                    saveFileHistoryLimit = settings.saveFileHistoryLimit,
                    saveStateHistoryLimit = settings.saveStateHistoryLimit
                )
                
                if (updatedSyncRequest.saveFilesEnabled && settings.saveFilesDirectory.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        error = "Save files directory not configured. Please configure in main app settings."
                    )
                    return@launch
                }
                
                if (updatedSyncRequest.saveStatesEnabled && settings.saveStatesDirectory.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        error = "Save states directory not configured. Please configure in main app settings."
                    )
                    return@launch
                }
                
                // Start sync process
                _uiState.value = _uiState.value.copy(
                    isScanning = true,
                    currentStep = "Preparing sync..."
                )
                
                if (updatedSyncRequest.dryRun) {
                    // Just create plan, don't execute
                    val plan = syncManager.createSyncPlan(updatedSyncRequest, settings)
                    _uiState.value = _uiState.value.copy(
                        isScanning = false,
                        syncPlan = plan,
                        isDryRun = true
                    )
                } else {
                    // Execute full sync
                    syncManager.executeSync(
                        syncRequest = updatedSyncRequest,
                        settings = settings,
                        onProgress = { progress ->
                            _uiState.value = _uiState.value.copy(
                                isScanning = false,
                                syncProgress = progress,
                                currentStep = progress.currentStep
                            )
                        },
                        onComplete = { result ->
                            _uiState.value = _uiState.value.copy(
                                syncResult = result,
                                isComplete = true
                            )
                        }
                    )
                }
                
            } catch (e: Exception) {
                Log.e("SyncViewModel", "Sync failed", e)
                _uiState.value = _uiState.value.copy(
                    error = "Sync failed: ${e.message}",
                    isScanning = false
                )
            }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

data class SyncUiState(
    val isScanning: Boolean = false,
    val syncPlan: SyncPlan? = null,
    val syncProgress: SyncProgress? = null,
    val syncResult: SyncResult? = null,
    val currentStep: String = "",
    val error: String? = null,
    val isComplete: Boolean = false,
    val isDryRun: Boolean = false
)

@Composable
fun SyncScreen(
    viewModel: SyncViewModel,
    syncRequest: SyncRequest,
    onComplete: (SyncResult) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    LaunchedEffect(syncRequest) {
        viewModel.startSync(syncRequest)
    }
    
    LaunchedEffect(uiState.syncResult) {
        uiState.syncResult?.let { result ->
            onComplete(result)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (uiState.isDryRun) "Sync Plan" else "Save Data Sync",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Direction indicator
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (syncRequest.direction) {
                    SyncDirection.UPLOAD_ONLY -> Icons.Filled.Upload
                    SyncDirection.DOWNLOAD_ONLY -> Icons.Filled.Download
                    SyncDirection.BIDIRECTIONAL -> Icons.Filled.Sync
                },
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when (syncRequest.direction) {
                    SyncDirection.UPLOAD_ONLY -> "Upload Only"
                    SyncDirection.DOWNLOAD_ONLY -> "Download Only" 
                    SyncDirection.BIDIRECTIONAL -> "Two-Way Sync"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        when {
            uiState.error != null -> {
                val error = uiState.error!!
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Error",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            uiState.isScanning -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    uiState.currentStep,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            
            uiState.syncPlan != null -> {
                val plan = uiState.syncPlan!!
                SyncPlanDisplay(
                    plan = plan,
                    isDryRun = uiState.isDryRun
                )
            }
            
            uiState.syncProgress != null -> {
                val progress = uiState.syncProgress!!
                SyncProgressDisplay(progress = progress)
            }
            
            uiState.syncResult != null -> {
                val result = uiState.syncResult!!
                SyncResultDisplay(result = result)
            }
        }
    }
}

@Composable
fun SyncPlanDisplay(plan: SyncPlan, isDryRun: Boolean) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = if (isDryRun) "Sync Plan Preview" else "Ready to Sync",
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Upload", style = MaterialTheme.typography.titleMedium)
                    Text("${plan.totalUploadCount} files")
                    Text("${plan.estimatedUploadSize / 1024 / 1024} MB")
                }
                
                Column {
                    Text("Download", style = MaterialTheme.typography.titleMedium)
                    Text("${plan.totalDownloadCount} files")
                    Text("${plan.estimatedDownloadSize / 1024 / 1024} MB")
                }
                
                Column {
                    Text("Skipped", style = MaterialTheme.typography.titleMedium)
                    Text("${plan.totalSkipCount} files")
                }
            }
            
            if (plan.comparisons.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(plan.comparisons.take(20)) { comparison ->
                        SyncComparisonItem(comparison = comparison)
                    }
                    
                    if (plan.comparisons.size > 20) {
                        item {
                            Text(
                                "... and ${plan.comparisons.size - 20} more items",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SyncComparisonItem(comparison: SyncComparison) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (comparison.recommendedAction) {
                SyncAction.UPLOAD -> Icons.Filled.Upload
                SyncAction.DOWNLOAD -> Icons.Filled.Download
                else -> Icons.Filled.Remove
            },
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = when (comparison.recommendedAction) {
                SyncAction.UPLOAD -> MaterialTheme.colorScheme.primary
                SyncAction.DOWNLOAD -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.outline
            }
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                comparison.identifier,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                comparison.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun SyncProgressDisplay(progress: SyncProgress) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            progress = { progress.overallProgressPercent / 100f },
            modifier = Modifier.size(80.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            progress.currentStep,
            style = MaterialTheme.typography.titleMedium
        )
        
        Text(
            "${progress.itemsProcessed} of ${progress.totalItems}",
            style = MaterialTheme.typography.bodyMedium
        )
        
        // Show individual file progress if available
        if (progress.currentFileProgress > 0f && progress.currentFileName != null) {
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                progress.currentFileName!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            LinearProgressIndicator(
                progress = { progress.currentFileProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )
            
            Text(
                "${(progress.currentFileProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        
        if (progress.hasErrors) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "${progress.errors.size} errors occurred",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun SyncResultDisplay(result: SyncResult) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (result.success) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (result.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                if (result.success) "Sync Complete" else "Sync Failed",
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Uploaded", style = MaterialTheme.typography.labelMedium)
                    Text("${result.uploadedCount}", style = MaterialTheme.typography.headlineSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Downloaded", style = MaterialTheme.typography.labelMedium)
                    Text("${result.downloadedCount}", style = MaterialTheme.typography.headlineSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Skipped", style = MaterialTheme.typography.labelMedium)
                    Text("${result.skippedCount}", style = MaterialTheme.typography.headlineSmall)
                }
                if (result.errorCount > 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Errors", style = MaterialTheme.typography.labelMedium)
                        Text("${result.errorCount}", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            
            Text(
                "Completed in ${result.duration / 1000}s",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
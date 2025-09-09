package com.romm.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.romm.android.data.Platform
import com.romm.android.data.SaveFile
import com.romm.android.data.Game

data class PlatformSaveFiles(
    val platform: Platform,
    val saveCount: Int,
    val gameCount: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveFilesScreen(
    platforms: List<PlatformSaveFiles>,
    isLoading: Boolean,
    onPlatformClick: (Platform) -> Unit,
    onRefresh: () -> Unit,
    onDownloadAllSaves: () -> Unit,
    lazyListState: LazyListState = rememberLazyListState()
) {
    val swipeRefreshState = rememberSwipeRefreshState(isLoading)
    
    SwipeRefresh(
        state = swipeRefreshState,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        item {
            Card(
                onClick = onDownloadAllSaves,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Download All Save Files",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Download all save files from all platforms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null
                    )
                }
            }
        }
        
        item {
            Text(
                "Platforms with Save Files",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        // SwipeRefresh handles the loading indicator, no need for redundant CircularProgressIndicator
        
        if (platforms.isEmpty() && !isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.SaveAs,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "No save files found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Save files will appear here once they're uploaded to RomM",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        items(platforms) { platformSaveFiles ->
            PlatformSaveFileCard(
                platformSaveFiles = platformSaveFiles,
                onClick = { onPlatformClick(platformSaveFiles.platform) }
            )
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSaveFileCard(
    platformSaveFiles: PlatformSaveFiles,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    platformSaveFiles.platform.display_name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "${platformSaveFiles.saveCount} save files across ${platformSaveFiles.gameCount} games",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.ArrowForward,
                contentDescription = null
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveFileDetailScreen(
    platform: Platform,
    games: List<GameWithSaves>,
    isLoading: Boolean,
    onGameClick: (Game) -> Unit,
    onDownloadPlatformSaves: () -> Unit,
    onRefresh: () -> Unit,
    lazyListState: LazyListState = rememberLazyListState()
) {
    val swipeRefreshState = rememberSwipeRefreshState(isLoading)
    
    SwipeRefresh(
        state = swipeRefreshState,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        item {
            Card(
                onClick = onDownloadPlatformSaves,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Download All ${platform.display_name} Saves",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Download all save files for this platform",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null
                    )
                }
            }
        }
        
        item {
            Text(
                "Games with Save Files",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        // SwipeRefresh handles the loading indicator, no need for redundant CircularProgressIndicator
        
        items(games) { gameWithSaves ->
            GameSaveFileCard(
                gameWithSaves = gameWithSaves,
                onClick = { onGameClick(gameWithSaves.game) }
            )
        }
        }
    }
}

data class GameWithSaves(
    val game: Game,
    val saves: List<SaveFile>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSaveFileCard(
    gameWithSaves: GameWithSaves,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    gameWithSaves.game.name ?: gameWithSaves.game.fs_name_no_ext,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.ArrowForward,
                    contentDescription = null
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                "${gameWithSaves.saves.size} save files",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Show emulators used for saves
            val emulators = gameWithSaves.saves.mapNotNull { it.emulator }.distinct()
            if (emulators.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Emulators: ${emulators.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSaveFileListScreen(
    game: Game,
    saves: List<SaveFile>,
    isLoading: Boolean,
    onSaveFileClick: (SaveFile) -> Unit,
    onDownloadGameSaves: () -> Unit,
    onRefresh: () -> Unit,
    lazyListState: LazyListState = rememberLazyListState()
) {
    val swipeRefreshState = rememberSwipeRefreshState(isLoading)
    
    SwipeRefresh(
        state = swipeRefreshState,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(
                onClick = onDownloadGameSaves,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Download All Game Saves",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Download all save files for ${game.name ?: game.fs_name_no_ext}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null
                    )
                }
            }
        }
        
        item {
            Text(
                "Save Files",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        // SwipeRefresh handles the loading indicator, no need for redundant CircularProgressIndicator
        
        items(saves) { saveFile ->
            SaveFileCard(
                saveFile = saveFile,
                onClick = { onSaveFileClick(saveFile) }
            )
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveFileCard(
    saveFile: SaveFile,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    saveFile.name ?: saveFile.file_name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                "Size: ${formatFileSize(saveFile.file_size_bytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (saveFile.emulator != null) {
                Text(
                    "Emulator: ${saveFile.emulator}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                "Created: ${formatDate(saveFile.created_at)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    
    return when {
        mb >= 1 -> "%.1f MB".format(mb)
        kb >= 1 -> "%.1f KB".format(kb)
        else -> "$bytes bytes"
    }
}

private fun formatDate(dateString: String): String {
    // For now, just return the date string as-is
    // In a real app, you'd parse and format this properly
    return dateString.take(10) // Just the date part (YYYY-MM-DD)
}
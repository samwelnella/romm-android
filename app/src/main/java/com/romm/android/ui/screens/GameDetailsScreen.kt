package com.romm.android.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.romm.android.data.Game
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailsScreen(
    game: Game,
    onDownload: (Game) -> Unit,
    onBack: () -> Unit,
    hostUrl: String = "",
    username: String = "",
    password: String = ""
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    game.name ?: game.fs_name_no_ext,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Game Information Card (smaller now)
                Card(
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Game Information",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        GameInfoRow("Platform", game.platform_slug)
                        GameInfoRow("File Name", game.fs_name)
                        
                        if (game.regions.isNotEmpty()) {
                            GameInfoRow("Regions", game.regions.joinToString(", "))
                        }
                        
                        if (game.languages.isNotEmpty()) {
                            GameInfoRow("Languages", game.languages.joinToString(", "))
                        }
                        
                        if (game.revision != null) {
                            GameInfoRow("Revision", game.revision)
                        }
                        
                        GameInfoRow("Multi-disc", if (game.multi) "Yes" else "No")
                        GameInfoRow("Files", game.files.size.toString())
                    }
                }
                
                // Cover Image (on the right)
                if (!game.path_cover_small.isNullOrEmpty() && hostUrl.isNotEmpty()) {
                    val coverImageUrl = buildCoverImageUrl(hostUrl, game.path_cover_small)
                    Log.d("GameDetailsScreen", "Loading cover image from: $coverImageUrl")
                    Log.d("GameDetailsScreen", "Cover path: ${game.path_cover_small}")
                    
                    var showError by remember(coverImageUrl) { mutableStateOf(false) }
                    var isLoading by remember(coverImageUrl) { mutableStateOf(true) }
                    
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (showError) {
                            Icon(
                                Icons.Filled.Image,
                                contentDescription = "No cover art",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(coverImageUrl)
                                    .addHeader("Authorization", Credentials.basic(username, password))
                                    .addHeader("Accept", "image/*")
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Game cover art",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onState = { state ->
                                    when (state) {
                                        is AsyncImagePainter.State.Loading -> {
                                            isLoading = true
                                            showError = false
                                        }
                                        is AsyncImagePainter.State.Success -> {
                                            isLoading = false
                                            showError = false
                                        }
                                        is AsyncImagePainter.State.Error -> {
                                            isLoading = false
                                            showError = true
                                        }
                                        else -> {}
                                    }
                                }
                            )
                        }
                        
                        // Loading indicator
                        if (isLoading && !showError) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
        
        if (game.summary?.isNotEmpty() == true) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Summary",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            game.summary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        
        if (game.files.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Files",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        game.files.forEach { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    file.file_name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    formatBytes(file.file_size_bytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        
        item {
            Button(
                onClick = { onDownload(game) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Download Game")
            }
        }
    }
}

@Composable
fun GameInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    
    return "%.1f %s".format(size, units[unitIndex])
}

private fun getCoverImageUrl(game: Game, hostUrl: String): String? {
    // Try different cover sources in order of preference
    return when {
        // First try direct URL (might be external URL like IGDB)
        !game.url_cover.isNullOrEmpty() -> game.url_cover
        // Then try large cover path
        !game.path_cover_large.isNullOrEmpty() && hostUrl.isNotEmpty() -> 
            buildCoverImageUrl(hostUrl, game.path_cover_large)
        // Finally try small cover path
        !game.path_cover_small.isNullOrEmpty() && hostUrl.isNotEmpty() -> 
            buildCoverImageUrl(hostUrl, game.path_cover_small)
        // No cover available
        else -> null
    }
}

private fun buildCoverImageUrl(hostUrl: String, coverPath: String): String {
    val baseUrl = if (hostUrl.endsWith("/")) hostUrl else "$hostUrl/"
    // Remove the leading slash but keep "assets/" since we're accessing assets directly
    val cleanPath = coverPath.removePrefix("/")
    // Remove timestamp parameter (?ts=...) if present
    val pathWithoutTimestamp = cleanPath.split("?")[0]
    return "$baseUrl$pathWithoutTimestamp"
}

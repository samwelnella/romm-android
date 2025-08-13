package com.romm.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.romm.android.data.Game
import com.romm.android.ui.components.AlphabetScrubber
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameListScreen(
    games: List<Game>,
    isLoading: Boolean,
    loadingProgress: com.romm.android.LoadingProgress? = null,
    onGameClick: (Game) -> Unit,
    onRefresh: () -> Unit,
    lazyListState: LazyListState = rememberLazyListState(),
    searchQuery: String = "" // Search query passed from TopBar
) {
    val coroutineScope = rememberCoroutineScope()
    
    // Filter games based on search query
    val filteredGames = remember(games, searchQuery) {
        if (searchQuery.isEmpty()) {
            games
        } else {
            games.filter { game ->
                val name = game.name ?: game.fs_name_no_ext
                name.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    
    // Create alphabet index for filtered games (matching RomM's sorting logic)
    val gamesByLetter = remember(filteredGames) {
        filteredGames.groupBy { game ->
            val name = game.name ?: game.fs_name_no_ext
            val sortableName = removeSortingArticles(name)
            val firstChar = sortableName.firstOrNull()?.uppercaseChar()
            when {
                firstChar == null -> "#"
                firstChar.isLetter() -> firstChar.toString()
                else -> "#"
            }
        }.toSortedMap()
    }
    
    // Calculate item indices for each letter by walking through filteredGames in order
    val letterIndices = remember(filteredGames, isLoading) {
        val indices = mutableMapOf<String, Int>()
        var currentIndex = 0 // Start at 0 since headers are outside LazyColumn
        
        // Add loading item index if loading
        if (isLoading) currentIndex++
        
        // Walk through games in the order they appear in the LazyColumn
        filteredGames.forEachIndexed { gameIndex, game ->
            val name = game.name ?: game.fs_name_no_ext
            val sortableName = removeSortingArticles(name)
            val firstChar = sortableName.firstOrNull()?.uppercaseChar()
            val letter = when {
                firstChar == null -> "#"
                firstChar.isLetter() -> firstChar.toString()
                else -> "#"
            }
            
            // Only record the first occurrence of each letter
            if (!indices.containsKey(letter)) {
                indices[letter] = currentIndex + gameIndex
            }
        }
        indices
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Game list - now takes full height since header is in TopBar
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 48.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (loadingProgress != null) {
                                LinearProgressIndicator(
                                    progress = { loadingProgress.current.toFloat() / loadingProgress.total.toFloat() },
                                    modifier = Modifier.fillMaxWidth(0.8f)
                                )
                                Text(
                                    text = loadingProgress.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                CircularProgressIndicator()
                                Text(
                                    text = "Loading games...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            
            if (filteredGames.isEmpty() && searchQuery.isNotEmpty()) {
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
                                Icons.Filled.Search,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "No games found",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Try adjusting your search terms",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            items(filteredGames) { game ->
                GameCard(
                    game = game,
                    onClick = { onGameClick(game) }
                )
            }
        }
        
        // Alphabet scrubber positioned on the right side
        // Hide scrubber during search or when no filtered games
        if (filteredGames.isNotEmpty() && !isLoading && searchQuery.isEmpty()) {
            AlphabetScrubber(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(
                        end = 8.dp, 
                        top = 16.dp, // Just top padding since no header
                        bottom = 16.dp
                    ),
                onLetterSelected = { letter ->
                    val targetIndex = letterIndices[letter]
                    if (targetIndex != null) {
                        coroutineScope.launch {
                            // Scroll so the first game with this letter appears at the top
                            lazyListState.animateScrollToItem(
                                index = targetIndex,
                                scrollOffset = 0 // Ensure it's at the very top
                            )
                        }
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameCard(
    game: Game,
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
                    game.name ?: game.fs_name_no_ext,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                
                if (game.multi) {
                    AssistChip(
                        onClick = { },
                        label = { Text("Multi") }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Display regions, revision, and other metadata
            val metadata = buildList {
                if (game.regions.isNotEmpty()) {
                    add("Region: ${game.regions.joinToString(", ")}")
                }
                if (game.revision != null) {
                    add("Rev: ${game.revision}")
                }
                if (game.languages.isNotEmpty()) {
                    add("Lang: ${game.languages.joinToString(", ")}")
                }
            }
            
            metadata.forEach { info ->
                Text(
                    info,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (game.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(game.tags.take(3)) { tag ->
                        AssistChip(
                            onClick = { },
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Remove common articles from the beginning of game names for proper alphabetical sorting
 * This matches the server-side sorting logic used by RomM
 */
private fun removeSortingArticles(name: String): String {
    val trimmedName = name.trim()
    val articlesToRemove = listOf("The ", "A ", "An ")
    
    for (article in articlesToRemove) {
        if (trimmedName.startsWith(article, ignoreCase = true)) {
            return trimmedName.substring(article.length).trim()
        }
    }
    
    return trimmedName
}

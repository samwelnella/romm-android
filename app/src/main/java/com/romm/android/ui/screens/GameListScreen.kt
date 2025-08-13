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
    title: String,
    onGameClick: (Game) -> Unit,
    onDownloadAll: () -> Unit,
    onDownloadMissing: () -> Unit,
    onDownloadFirmware: (() -> Unit)?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    lazyListState: LazyListState = rememberLazyListState()
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
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
    
    // Create alphabet index for filtered games
    val gamesByLetter = remember(filteredGames) {
        filteredGames.groupBy { game ->
            val name = game.name ?: game.fs_name_no_ext
            val firstChar = name.firstOrNull()?.uppercaseChar()
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
        var currentIndex = 1 // Start at 1 to account for header item
        
        // Add search bar item
        currentIndex++
        
        // Add loading item index if loading
        if (isLoading) currentIndex++
        
        // Walk through games in the order they appear in the LazyColumn
        filteredGames.forEachIndexed { gameIndex, game ->
            val name = game.name ?: game.fs_name_no_ext
            val firstChar = name.firstOrNull()?.uppercaseChar()
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
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 48.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        title,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (!isSearchActive) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    }
                    IconButton(onClick = { showBottomSheet = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                }
            }
            
            item {
                if (isSearchActive) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search games...") },
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { 
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    }
                } else if (searchQuery.isNotEmpty()) {
                    // Show search summary when search is not active but has results
                    Text(
                        text = "Showing ${filteredGames.size} of ${games.size} games",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
            
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
            val topPadding = if (isSearchActive || searchQuery.isNotEmpty()) {
                16.dp + 56.dp + 56.dp + 16.dp // header + search bar + spacing
            } else {
                16.dp + 56.dp + 8.dp // header + spacing
            }
            
            AlphabetScrubber(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(
                        end = 8.dp, 
                        top = topPadding,
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
    
    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false }
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Download Options",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            onDownloadAll()
                            showBottomSheet = false 
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Download All Games")
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            onDownloadMissing()
                            showBottomSheet = false 
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.GetApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Download Missing Games")
                }
                
                if (onDownloadFirmware != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                onDownloadFirmware()
                                showBottomSheet = false 
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Memory, contentDescription = null)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Download Firmware")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }
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

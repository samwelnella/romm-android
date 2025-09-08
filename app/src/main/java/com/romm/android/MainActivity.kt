package com.romm.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.romm.android.data.*
import com.romm.android.ui.screens.PlatformSaveFiles
import com.romm.android.ui.screens.PlatformSaveStates
import com.romm.android.ui.screens.GameWithSaves
import com.romm.android.ui.screens.GameWithStates
import com.romm.android.network.RomMApiService
import com.romm.android.ui.theme.RomMTheme
import com.romm.android.ui.components.*
import com.romm.android.ui.screens.*
import com.romm.android.utils.DownloadManager
import com.romm.android.sync.SyncDirection
import com.romm.android.sync.SyncRequest
import com.romm.android.sync.SyncManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    private val viewModel: MainViewModel by viewModels()
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted
        } else {
            // Permission denied
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        setContent {
            RomMTheme {
                RomMApp(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameListTopBar(
    title: String,
    searchQuery: String,
    isSearchActive: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onSearchActiveChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onDownloadAll: () -> Unit,
    onDownloadMissing: () -> Unit,
    onDownloadFirmware: (() -> Unit)?,
    onSettings: () -> Unit
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    
    if (isSearchActive) {
        // Search mode top bar
        TopAppBar(
            title = {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChanged,
                    placeholder = { Text("Search games...") },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChanged("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            navigationIcon = {
                IconButton(onClick = { onSearchActiveChanged(false) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Close search")
                }
            }
        )
    } else {
        // Normal mode top bar
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { onSearchActiveChanged(true) }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
                IconButton(onClick = { showBottomSheet = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
            }
        )
    }
    
    // Bottom sheet for download options
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
fun RomMApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Create scroll states for different screens
    val platformListState = rememberLazyListState()
    val collectionListState = rememberLazyListState()
    val gameListStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    
    // Handle Android system back gesture
    BackHandler(enabled = uiState.screenHistory.isNotEmpty()) {
        // Save current scroll state before going back
        val currentScrollKey = uiState.currentScreen.getScrollKey()
        val currentScrollPosition = when (uiState.currentScreen) {
            is Screen.PlatformList -> platformListState.firstVisibleItemIndex
            is Screen.CollectionList -> collectionListState.firstVisibleItemIndex
            is Screen.GameList -> gameListStates[currentScrollKey]?.firstVisibleItemIndex ?: 0
            else -> 0
        }
        viewModel.saveScrollState(currentScrollKey, currentScrollPosition)
        viewModel.goBack()
    }
    
    Scaffold(
        topBar = {
            when (val screen = uiState.currentScreen) {
                is Screen.GameList -> {
                    GameListTopBar(
                        title = screen.title,
                        searchQuery = uiState.searchQuery,
                        isSearchActive = uiState.isSearchActive,
                        onSearchQueryChanged = viewModel::updateSearchQuery,
                        onSearchActiveChanged = viewModel::setSearchActive,
                        onBack = viewModel::goBack,
                        onDownloadAll = { viewModel.downloadAllGames(screen.platformId, screen.collectionId) },
                        onDownloadMissing = { viewModel.downloadMissingGames(screen.platformId, screen.collectionId) },
                        onDownloadFirmware = screen.platformId?.let { { viewModel.downloadFirmware(it) } },
                        onSettings = viewModel::showSettings
                    )
                }
                else -> {
                    TopAppBar(
                        title = { 
                            Text(when (screen) {
                                is Screen.Settings -> "Settings"
                                is Screen.PlatformList -> "RomM Android"
                                is Screen.CollectionList -> "Collections"
                                is Screen.GameDetails -> screen.game.name ?: screen.game.fs_name_no_ext
                                is Screen.SaveFilesList -> "Save Files"
                                is Screen.SaveStatesList -> "Save States"
                                is Screen.SaveFilesDetail -> "${screen.platform.display_name} - Save Files"
                                is Screen.SaveStatesDetail -> "${screen.platform.display_name} - Save States"
                                is Screen.GameSaveFiles -> "${screen.game.name ?: screen.game.fs_name_no_ext} - Save Files"
                                is Screen.GameSaveStates -> "${screen.game.name ?: screen.game.fs_name_no_ext} - Save States"
                                else -> "RomM Android"
                            })
                        },
                        navigationIcon = {
                            if (screen !is Screen.PlatformList) {
                                IconButton(onClick = viewModel::goBack) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        },
                        actions = {
                            if (screen !is Screen.Settings) {
                                // Sync button
                                IconButton(onClick = { viewModel.showSyncDialog() }) {
                                    Icon(Icons.Filled.Sync, contentDescription = "Sync")
                                }
                                // Settings button  
                                IconButton(onClick = { viewModel.showSettings() }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                }
                            }
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            if (uiState.currentScreen is Screen.PlatformList || uiState.currentScreen is Screen.CollectionList) {
                FloatingActionButton(
                    onClick = { viewModel.refreshData() }
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val screen = uiState.currentScreen) {
                is Screen.Settings -> {
                    SettingsScreen(
                        settings = uiState.settings,
                        onSettingsChanged = viewModel::updateSettings
                    )
                }
                is Screen.PlatformList -> {
                    // Handle scroll position restoration for back navigation
                    LaunchedEffect(uiState.isNavigatingBack) {
                        if (uiState.isNavigatingBack) {
                            val savedPosition = viewModel.getScrollState(screen.getScrollKey())
                            if (savedPosition > 0) {
                                platformListState.scrollToItem(savedPosition)
                            }
                            viewModel.resetNavigationFlag()
                        }
                    }
                    
                    // Handle scroll position reset for forward navigation (new screen entry)
                    LaunchedEffect(screen) {
                        if (!uiState.isNavigatingBack) {
                            platformListState.scrollToItem(0)
                        }
                    }
                    
                    PlatformListScreen(
                        platforms = uiState.platforms,
                        isLoading = uiState.isLoading,
                        onPlatformClick = { platform ->
                            // Save scroll state before navigating
                            viewModel.saveScrollState(screen.getScrollKey(), platformListState.firstVisibleItemIndex)
                            viewModel.selectPlatform(platform)
                        },
                        onCollectionsClick = {
                            // Save scroll state before navigating
                            viewModel.saveScrollState(screen.getScrollKey(), platformListState.firstVisibleItemIndex)
                            viewModel.showCollections()
                        },
                        onSaveFilesClick = {
                            // Save scroll state before navigating
                            viewModel.saveScrollState(screen.getScrollKey(), platformListState.firstVisibleItemIndex)
                            viewModel.showSaveFiles()
                        },
                        onSaveStatesClick = {
                            // Save scroll state before navigating
                            viewModel.saveScrollState(screen.getScrollKey(), platformListState.firstVisibleItemIndex)
                            viewModel.showSaveStates()
                        },
                        onRefresh = viewModel::refreshData,
                        lazyListState = platformListState
                    )
                }
                is Screen.CollectionList -> {
                    // Handle scroll position restoration for back navigation
                    LaunchedEffect(uiState.isNavigatingBack) {
                        if (uiState.isNavigatingBack) {
                            val savedPosition = viewModel.getScrollState(screen.getScrollKey())
                            if (savedPosition > 0) {
                                collectionListState.scrollToItem(savedPosition)
                            }
                            viewModel.resetNavigationFlag()
                        }
                    }
                    
                    // Handle scroll position reset for forward navigation (new screen entry)
                    LaunchedEffect(screen) {
                        if (!uiState.isNavigatingBack) {
                            collectionListState.scrollToItem(0)
                        }
                    }
                    
                    CollectionListScreen(
                        collections = uiState.collections,
                        isLoading = uiState.isLoading,
                        onCollectionClick = { collection ->
                            // Save scroll state before navigating
                            viewModel.saveScrollState(screen.getScrollKey(), collectionListState.firstVisibleItemIndex)
                            viewModel.selectCollection(collection)
                        },
                        onRefresh = viewModel::refreshData,
                        lazyListState = collectionListState
                    )
                }
                is Screen.GameList -> {
                    // Get or create scroll state for this specific game list
                    val scrollKey = screen.getScrollKey()
                    val gameListState = gameListStates.getOrPut(scrollKey) { 
                        androidx.compose.foundation.lazy.LazyListState()
                    }
                    
                    // Handle scroll position restoration for back navigation
                    LaunchedEffect(uiState.isNavigatingBack) {
                        if (uiState.isNavigatingBack) {
                            val savedPosition = viewModel.getScrollState(scrollKey)
                            if (savedPosition > 0) {
                                gameListState.scrollToItem(savedPosition)
                            }
                            viewModel.resetNavigationFlag()
                        }
                    }
                    
                    // Handle scroll position reset for forward navigation (new screen entry)
                    LaunchedEffect(screen) {
                        if (!uiState.isNavigatingBack) {
                            gameListState.scrollToItem(0)
                        }
                    }
                    
                    GameListScreen(
                        games = uiState.games,
                        isLoading = uiState.isLoading,
                        loadingProgress = uiState.loadingProgress,
                        searchQuery = uiState.searchQuery,
                        onGameClick = { game ->
                            // Save scroll state before navigating
                            viewModel.saveScrollState(scrollKey, gameListState.firstVisibleItemIndex)
                            viewModel.selectGame(game)
                        },
                        onRefresh = { viewModel.refreshGames(screen.platformId, screen.collectionId) },
                        lazyListState = gameListState
                    )
                }
                is Screen.GameDetails -> {
                    GameDetailsScreen(
                        game = screen.game,
                        onDownload = viewModel::downloadGame,
                        hostUrl = uiState.settings.host,
                        username = uiState.settings.username,
                        password = uiState.settings.password
                    )
                }
                is Screen.SaveFilesList -> {
                    SaveFilesScreen(
                        platforms = uiState.saveFilesPlatforms,
                        isLoading = uiState.isLoading,
                        onPlatformClick = { platform ->
                            viewModel.navigateToSaveFilesDetail(platform)
                        },
                        onRefresh = viewModel::refreshData,
                        onDownloadAllSaves = {
                            viewModel.downloadAllSaveFiles()
                        }
                    )
                }
                is Screen.SaveStatesList -> {
                    SaveStatesScreen(
                        platforms = uiState.saveStatesPlatforms,
                        isLoading = uiState.isLoading,
                        onPlatformClick = { platform ->
                            viewModel.navigateToSaveStatesDetail(platform)
                        },
                        onRefresh = viewModel::refreshData,
                        onDownloadAllStates = {
                            viewModel.downloadAllSaveStates()
                        }
                    )
                }
                is Screen.SaveFilesDetail -> {
                    SaveFileDetailScreen(
                        platform = screen.platform,
                        games = uiState.saveFilesForPlatform,
                        isLoading = uiState.isLoading,
                        onGameClick = { game -> 
                            viewModel.navigateToGameSaveFiles(game)
                        },
                        onDownloadPlatformSaves = {
                            viewModel.downloadPlatformSaveFiles(screen.platform)
                        },
                        onRefresh = { viewModel.refreshData() }
                    )
                }
                is Screen.SaveStatesDetail -> {
                    SaveStateDetailScreen(
                        platform = screen.platform,
                        games = uiState.saveStatesForPlatform,
                        isLoading = uiState.isLoading,
                        onGameClick = { game -> 
                            viewModel.navigateToGameSaveStates(game)
                        },
                        onDownloadPlatformStates = {
                            viewModel.downloadPlatformSaveStates(screen.platform)
                        },
                        onRefresh = { viewModel.refreshData() }
                    )
                }
                is Screen.GameSaveFiles -> {
                    GameSaveFileListScreen(
                        game = screen.game,
                        saves = uiState.gameSaves,
                        isLoading = uiState.isLoading,
                        onSaveFileClick = { saveFile ->
                            viewModel.downloadSingleSaveFile(saveFile)
                        },
                        onDownloadGameSaves = {
                            viewModel.downloadGameSaveFiles(screen.game)
                        },
                        onRefresh = { viewModel.refreshData() }
                    )
                }
                is Screen.GameSaveStates -> {
                    GameSaveStateListScreen(
                        game = screen.game,
                        states = uiState.gameStates,
                        isLoading = uiState.isLoading,
                        onSaveStateClick = { saveState ->
                            viewModel.downloadSingleSaveState(saveState)
                        },
                        onDownloadGameStates = {
                            viewModel.downloadGameSaveStates(screen.game)
                        },
                        onRefresh = { viewModel.refreshData() }
                    )
                }
            }
            
            // Show error messages
            val errorMessage = uiState.error
            if (errorMessage != null) {
                ErrorSnackbar(
                    error = errorMessage,
                    onDismiss = viewModel::clearError
                )
            }
            
            // Show success messages
            val successMessage = uiState.successMessage
            if (successMessage != null) {
                SuccessSnackbar(
                    message = successMessage,
                    onDismiss = viewModel::clearSuccessMessage
                )
            }
            
            // Show sync progress
            val syncProgress = uiState.syncProgress
            if (syncProgress != null && !syncProgress.isComplete) {
                val progressMessage = if (syncProgress.currentFileName != null && syncProgress.currentFileProgress > 0f) {
                    "(${syncProgress.itemsProcessed + 1} / ${syncProgress.totalItems}) Uploading ${syncProgress.currentFileName} (${(syncProgress.currentFileProgress * 100).toInt()}%)"
                } else {
                    "(${syncProgress.itemsProcessed} / ${syncProgress.totalItems}) ${syncProgress.currentStep}"
                }
                ProgressSnackbar(
                    message = progressMessage,
                    progress = syncProgress.overallProgressPercent / 100f
                )
            }
        }
        
        // Sync Dialog
        if (uiState.showSyncDialog) {
            SyncDialog(
                onDismiss = viewModel::hideSyncDialog,
                onStartSync = { direction -> 
                    viewModel.startSync(direction)
                    viewModel.hideSyncDialog()
                }
            )
        }
    }
}

@Composable
fun SyncDialog(
    onDismiss: () -> Unit,
    onStartSync: (SyncDirection) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Sync Save Data")
        },
        text = {
            Column {
                Text(
                    "Choose sync direction:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                SyncDirectionButton(
                    icon = Icons.Filled.Upload,
                    text = "Upload Only",
                    description = "Upload local saves to server",
                    onClick = { onStartSync(SyncDirection.UPLOAD_ONLY) }
                )
                
                SyncDirectionButton(
                    icon = Icons.Filled.Download,
                    text = "Download Only", 
                    description = "Download saves from server",
                    onClick = { onStartSync(SyncDirection.DOWNLOAD_ONLY) }
                )
                
                SyncDirectionButton(
                    icon = Icons.Filled.Sync,
                    text = "Two-Way Sync",
                    description = "Upload and download - newest files win",
                    onClick = { onStartSync(SyncDirection.BIDIRECTIONAL) }
                )
            }
        },
        confirmButton = {
            // No confirm button needed - actions are handled by the buttons above
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SyncDirectionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Data Classes
data class LoadingProgress(
    val current: Int,
    val total: Int,
    val message: String
)

data class UiState(
    val currentScreen: Screen = Screen.PlatformList,
    val screenHistory: List<Screen> = emptyList(),
    val platforms: List<Platform> = emptyList(),
    val collections: List<com.romm.android.data.Collection> = emptyList(), // Fully qualified name
    val games: List<Game> = emptyList(),
    val cachedGames: Map<String, List<Game>> = emptyMap(), // Cache games by platformId or collectionId
    val saveFiles: List<SaveFile> = emptyList(),
    val saveStates: List<SaveState> = emptyList(),
    val saveFilesPlatforms: List<PlatformSaveFiles> = emptyList(),
    val saveStatesPlatforms: List<PlatformSaveStates> = emptyList(),
    val saveStatesForPlatform: List<GameWithStates> = emptyList(),
    val saveFilesForPlatform: List<GameWithSaves> = emptyList(),
    val gameStates: List<SaveState> = emptyList(),
    val gameSaves: List<SaveFile> = emptyList(),
    val scrollStates: Map<String, Int> = emptyMap(), // Cache scroll positions by screen key
    val isNavigatingBack: Boolean = false, // Track if we're navigating back vs forward
    val isLoading: Boolean = false,
    val loadingProgress: LoadingProgress? = null,
    val error: String? = null,
    val successMessage: String? = null,
    val settings: AppSettings = AppSettings(),
    val searchQuery: String = "", // Search query for game list
    val isSearchActive: Boolean = false, // Whether search mode is active
    val showSyncDialog: Boolean = false, // Whether sync dialog is shown
    val syncProgress: com.romm.android.sync.SyncProgress? = null // Current sync progress
)

sealed class Screen {
    object Settings : Screen()
    object PlatformList : Screen()
    object CollectionList : Screen()
    object SaveFilesList : Screen()
    object SaveStatesList : Screen()
    data class GameList(
        val title: String,
        val platformId: Int? = null,
        val collectionId: Int? = null
    ) : Screen()
    data class GameDetails(val game: Game) : Screen()
    data class SaveFilesDetail(val platform: Platform) : Screen()
    data class SaveStatesDetail(val platform: Platform) : Screen()
    data class GameSaveFiles(val game: Game) : Screen()
    data class GameSaveStates(val game: Game) : Screen()
    
    // Generate unique keys for scroll state caching
    fun getScrollKey(): String {
        return when (this) {
            is Settings -> "settings"
            is PlatformList -> "platform_list"
            is CollectionList -> "collection_list"
            is SaveFilesList -> "save_files_list"
            is SaveStatesList -> "save_states_list"
            is GameList -> when {
                platformId != null -> "game_list_platform_$platformId"
                collectionId != null -> "game_list_collection_$collectionId"
                else -> "game_list_unknown"
            }
            is GameDetails -> "game_details_${game.id}"
            is SaveFilesDetail -> "save_files_detail_${platform.id}"
            is SaveStatesDetail -> "save_states_detail_${platform.id}"
            is GameSaveFiles -> "game_save_files_${game.id}"
            is GameSaveStates -> "game_save_states_${game.id}"
        }
    }
}

// MainViewModel.kt
@HiltViewModel
class MainViewModel @Inject constructor(
    private val apiService: RomMApiService,
    private val downloadManager: DownloadManager,
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(settings = settings)
                if (settings.host.isNotEmpty()) {
                    loadPlatforms()
                }
            }
        }
    }
    
    fun updateSettings(settings: AppSettings) {
        Log.d("MainViewModel", "Updating settings: $settings")
        Log.d("MainViewModel", "Save files directory: ${settings.saveFilesDirectory}")
        Log.d("MainViewModel", "Save states directory: ${settings.saveStatesDirectory}")
        viewModelScope.launch {
            try {
                settingsRepository.updateSettings(settings)
                
                // Update UI state immediately with the new settings
                _uiState.value = _uiState.value.copy(
                    settings = settings,
                    successMessage = "Settings saved successfully!"
                )
                
                // Navigate back to previous screen after successful save
                goBack()
                
                // Clear success message after 3 seconds
                kotlinx.coroutines.delay(3000)
                if (_uiState.value.successMessage == "Settings saved successfully!") {
                    clearSuccessMessage()
                }
                
                if (settings.host.isNotEmpty()) {
                    loadPlatforms()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to save settings", e)
                _uiState.value = _uiState.value.copy(error = "Failed to save settings: ${e.message}")
            }
        }
    }
    
    fun showSettings() {
        navigateToScreen(Screen.Settings)
    }
    
    fun showCollections() {
        navigateToScreen(Screen.CollectionList)
        loadCollections()
    }
    
    fun showSyncDialog() {
        _uiState.value = _uiState.value.copy(showSyncDialog = true)
    }
    
    fun hideSyncDialog() {
        _uiState.value = _uiState.value.copy(showSyncDialog = false)
    }
    
    fun startSync(direction: SyncDirection) {
        viewModelScope.launch {
            try {
                val settings = _uiState.value.settings
                val syncRequest = SyncRequest(
                    direction = direction,
                    saveFilesEnabled = true,
                    saveStatesEnabled = true
                )
                
                syncManager.executeSync(
                    syncRequest = syncRequest,
                    settings = settings,
                    onProgress = { progress ->
                        // Show progress in main UI
                        _uiState.value = _uiState.value.copy(syncProgress = progress)
                        Log.d("MainViewModel", "Sync progress: ${progress.currentStep}")
                    },
                    onComplete = { result ->
                        // Clear progress and show result
                        _uiState.value = _uiState.value.copy(syncProgress = null)
                        
                        if (result.success) {
                            _uiState.value = _uiState.value.copy(
                                successMessage = "Sync completed: ↑${result.uploadedCount} ↓${result.downloadedCount} ⏸${result.skippedCount}"
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(
                                error = "Sync failed: ${result.errors.firstOrNull() ?: "Unknown error"}"
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    syncProgress = null,
                    error = "Failed to start sync: ${e.message}"
                )
            }
        }
    }
    
    private fun navigateToScreen(newScreen: Screen) {
        val currentState = _uiState.value
        val newHistory = currentState.screenHistory + currentState.currentScreen
        
        // Clear scroll state for the new screen since we're navigating forward
        clearScrollState(newScreen.getScrollKey())
        
        _uiState.value = currentState.copy(
            currentScreen = newScreen,
            screenHistory = newHistory,
            isNavigatingBack = false // Forward navigation
        )
    }
    
    fun saveScrollState(screenKey: String, scrollPosition: Int) {
        val currentState = _uiState.value
        _uiState.value = currentState.copy(
            scrollStates = currentState.scrollStates + (screenKey to scrollPosition)
        )
    }
    
    fun getScrollState(screenKey: String): Int {
        return _uiState.value.scrollStates[screenKey] ?: 0
    }
    
    fun clearScrollState(screenKey: String) {
        val currentState = _uiState.value
        _uiState.value = currentState.copy(
            scrollStates = currentState.scrollStates - screenKey
        )
    }
    
    fun resetNavigationFlag() {
        val currentState = _uiState.value
        _uiState.value = currentState.copy(
            isNavigatingBack = false
        )
    }
    
    fun goBack() {
        val currentState = _uiState.value
        if (currentState.screenHistory.isNotEmpty()) {
            val previousScreen = currentState.screenHistory.last()
            val newHistory = currentState.screenHistory.dropLast(1)
            
            // Check if we have cached data for the previous screen
            val cachedGames = when (previousScreen) {
                is Screen.GameList -> {
                    val cacheKey = when {
                        previousScreen.platformId != null -> "platform_${previousScreen.platformId}"
                        previousScreen.collectionId != null -> "collection_${previousScreen.collectionId}"
                        else -> null
                    }
                    cacheKey?.let { currentState.cachedGames[it] }
                }
                is Screen.SaveFilesList -> null
                is Screen.SaveStatesList -> null
                is Screen.SaveFilesDetail -> null
                is Screen.SaveStatesDetail -> null
                is Screen.GameSaveFiles -> null
                is Screen.GameSaveStates -> null
                else -> null
            }
            
            _uiState.value = currentState.copy(
                currentScreen = previousScreen,
                screenHistory = newHistory,
                games = cachedGames ?: currentState.games, // Use cached games if available
                isNavigatingBack = true // Mark as back navigation
            )
            
            // Only reload if we don't have cached data
            if (cachedGames == null) {
                when (previousScreen) {
                    is Screen.GameList -> {
                        refreshGames(previousScreen.platformId, previousScreen.collectionId)
                    }
                    is Screen.SaveFilesList -> {
                        // TODO: Implement save files refresh
                    }
                    is Screen.SaveStatesList -> {
                        // TODO: Implement save states refresh
                    }
                    is Screen.SaveFilesDetail -> {
                        // TODO: Implement save files detail refresh
                    }
                    is Screen.SaveStatesDetail -> {
                        // TODO: Implement save states detail refresh
                    }
                    is Screen.GameSaveFiles -> {
                        // TODO: Implement game save files refresh
                    }
                    is Screen.GameSaveStates -> {
                        // TODO: Implement game save states refresh
                    }
                    else -> { /* No additional loading needed */ }
                }
            }
        } else {
            // No history, go to platform list
            _uiState.value = currentState.copy(
                currentScreen = Screen.PlatformList,
                isNavigatingBack = true
            )
        }
    }
    
    fun selectPlatform(platform: Platform) {
        navigateToScreen(Screen.GameList(
            title = platform.display_name,
            platformId = platform.id
        ))
        loadGamesForPlatform(platform.id)
    }
    
    fun selectCollection(collection: com.romm.android.data.Collection) { // Fully qualified name
        navigateToScreen(Screen.GameList(
            title = collection.name,
            collectionId = collection.id
        ))
        loadGamesForCollection(collection.id)
    }
    
    fun selectGame(game: Game) {
        navigateToScreen(Screen.GameDetails(game))
    }
    
    fun refreshData() {
        when (_uiState.value.currentScreen) {
            is Screen.PlatformList -> loadPlatforms()
            is Screen.CollectionList -> loadCollections()
            is Screen.GameList -> {
                val screen = _uiState.value.currentScreen as Screen.GameList
                refreshGames(screen.platformId, screen.collectionId)
            }
            is Screen.Settings -> { /* No refresh needed for settings */ }
            is Screen.GameDetails -> { /* No refresh needed for game details */ }
            is Screen.SaveFilesList -> loadSaveFiles()
            is Screen.SaveStatesList -> loadSaveStates()
            is Screen.SaveFilesDetail -> { /* TODO: Implement save files detail refresh */ }
            is Screen.SaveStatesDetail -> { /* TODO: Implement save states detail refresh */ }
            is Screen.GameSaveFiles -> { /* TODO: Implement game save files refresh */ }
            is Screen.GameSaveStates -> { /* TODO: Implement game save states refresh */ }
        }
    }
    
    fun refreshGames(platformId: Int?, collectionId: Int?) {
        when {
            platformId != null -> loadGamesForPlatform(platformId)
            collectionId != null -> loadGamesForCollection(collectionId)
        }
    }
    
    private fun loadPlatforms() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val platforms = apiService.getPlatforms()
                _uiState.value = _uiState.value.copy(
                    platforms = platforms,
                    isLoading = false
                )
                Log.d("MainViewModel", "Loaded ${platforms.size} platforms")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load platforms", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load platforms: ${e.message}",
                    isLoading = false
                )
            }
        }
    }
    
    private fun loadCollections() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val collections = apiService.getCollections()
                _uiState.value = _uiState.value.copy(
                    collections = collections,
                    isLoading = false
                )
                Log.d("MainViewModel", "Loaded ${collections.size} collections")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load collections", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load collections: ${e.message}",
                    isLoading = false
                )
            }
        }
    }
    
    private fun loadGamesForPlatform(platformId: Int) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isLoading = true, 
                    error = null, 
                    games = emptyList(),
                    loadingProgress = null
                )
                val games = apiService.getGames(
                    platformId = platformId,
                    onProgress = { current, total ->
                        _uiState.value = _uiState.value.copy(
                            loadingProgress = LoadingProgress(
                                current = current,
                                total = total,
                                message = "Loading games: $current of $total"
                            )
                        )
                    }
                )
                val cacheKey = "platform_$platformId"
                _uiState.value = _uiState.value.copy(
                    games = games,
                    cachedGames = _uiState.value.cachedGames + (cacheKey to games),
                    isLoading = false,
                    loadingProgress = null
                )
                Log.d("MainViewModel", "Loaded ${games.size} games for platform $platformId")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load games for platform $platformId", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load games: ${e.message}",
                    isLoading = false,
                    loadingProgress = null
                )
            }
        }
    }
    
    private fun loadGamesForCollection(collectionId: Int) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isLoading = true, 
                    error = null, 
                    games = emptyList(),
                    loadingProgress = null
                )
                val games = apiService.getGames(
                    collectionId = collectionId,
                    onProgress = { current, total ->
                        _uiState.value = _uiState.value.copy(
                            loadingProgress = LoadingProgress(
                                current = current,
                                total = total,
                                message = "Loading games: $current of $total"
                            )
                        )
                    }
                )
                val cacheKey = "collection_$collectionId"
                _uiState.value = _uiState.value.copy(
                    games = games,
                    cachedGames = _uiState.value.cachedGames + (cacheKey to games),
                    isLoading = false,
                    loadingProgress = null
                )
                Log.d("MainViewModel", "Loaded ${games.size} games for collection $collectionId")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load games for collection $collectionId", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load games: ${e.message}",
                    isLoading = false,
                    loadingProgress = null
                )
            }
        }
    }
    
    fun downloadGame(game: Game) {
        Log.d("MainViewModel", "Download requested for game: ${game.name ?: game.fs_name} (ID: ${game.id})")
        viewModelScope.launch {
            try {
                downloadManager.downloadGame(game, _uiState.value.settings)
                Log.d("MainViewModel", "Download manager called successfully")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error starting download", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to start download: ${e.message}"
                )
            }
        }
    }
    
    fun downloadAllGames(platformId: Int?, collectionId: Int?) {
        Log.d("MainViewModel", "Download all games requested - platform: $platformId, collection: $collectionId")
        Log.d("MainViewModel", "Total games to download: ${_uiState.value.games.size}")
        viewModelScope.launch {
            try {
                downloadManager.downloadAllGames(_uiState.value.games, _uiState.value.settings)
                Log.d("MainViewModel", "Bulk download manager called successfully")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error starting bulk download", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to start bulk download: ${e.message}"
                )
            }
        }
    }
    
    fun downloadMissingGames(platformId: Int?, collectionId: Int?) {
        val allGames = _uiState.value.games
        Log.d("MainViewModel", "Download missing games requested - checking ${allGames.size} total games")
        viewModelScope.launch {
            try {
                downloadManager.downloadMissingGames(allGames, _uiState.value.settings)
                Log.d("MainViewModel", "Missing games download manager called successfully")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error starting missing games download", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to start missing games download: ${e.message}"
                )
            }
        }
    }
    
    fun downloadFirmware(platformId: Int) {
        Log.d("MainViewModel", "Download firmware requested for platform: $platformId")
        viewModelScope.launch {
            try {
                val firmware = apiService.getFirmware(platformId)
                Log.d("MainViewModel", "Found ${firmware.size} firmware files")
                downloadManager.downloadFirmware(firmware, _uiState.value.settings)
                Log.d("MainViewModel", "Firmware download manager called successfully")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error starting firmware download", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load firmware: ${e.message}"
                )
            }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    fun clearSuccessMessage() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }
    
    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }
    
    fun setSearchActive(active: Boolean) {
        _uiState.value = _uiState.value.copy(isSearchActive = active)
        if (!active) {
            // Clear search when closing
            _uiState.value = _uiState.value.copy(searchQuery = "")
        }
    }
    
    fun showSaveFiles() {
        navigateToScreen(Screen.SaveFilesList)
        loadSaveFiles()
    }
    
    fun showSaveStates() {
        navigateToScreen(Screen.SaveStatesList)
        loadSaveStates()
    }
    
    fun navigateToSaveFilesDetail(platform: Platform) {
        navigateToScreen(Screen.SaveFilesDetail(platform))
        loadSaveFilesForPlatform(platform)
    }
    
    private fun loadSaveFilesForPlatform(platform: Platform) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // Try getting all saves first, then filter client-side
                Log.d("MainViewModel", "Loading all save files to filter for platform ${platform.display_name}")
                val allSaves = apiService.getSaves()
                Log.d("MainViewModel", "Found ${allSaves.size} total save files")
                
                // Filter saves by platform by checking each game's platform
                val platformSaves = mutableListOf<SaveFile>()
                for (save in allSaves) {
                    try {
                        val game = apiService.getGame(save.rom_id)
                        if (game.platform_slug == platform.slug) {
                            platformSaves.add(save)
                        }
                    } catch (e: Exception) {
                        Log.w("MainViewModel", "Failed to get game ${save.rom_id} for save ${save.id}", e)
                    }
                }
                
                Log.d("MainViewModel", "Found ${platformSaves.size} save files for platform ${platform.display_name}")
                
                // Group saves by game
                val savesByGame = platformSaves.groupBy { it.rom_id }
                val gamesWithSaves = mutableListOf<GameWithSaves>()
                
                for ((romId, gameSaves) in savesByGame) {
                    try {
                        val game = apiService.getGame(romId)
                        gamesWithSaves.add(GameWithSaves(game, gameSaves))
                    } catch (e: Exception) {
                        Log.w("MainViewModel", "Failed to get game $romId for saves", e)
                    }
                }
                
                _uiState.value = _uiState.value.copy(
                    saveFilesForPlatform = gamesWithSaves,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load save files for platform", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load save files: ${e.message}"
                )
            }
        }
    }
    
    fun navigateToSaveStatesDetail(platform: Platform) {
        navigateToScreen(Screen.SaveStatesDetail(platform))
        loadSaveStatesForPlatform(platform)
    }
    
    private fun loadSaveStatesForPlatform(platform: Platform) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // Try getting all states first, then filter client-side
                Log.d("MainViewModel", "Loading all save states to filter for platform ${platform.display_name}")
                val allStates = apiService.getStates()
                Log.d("MainViewModel", "Found ${allStates.size} total save states")
                
                // Filter states by platform by checking each game's platform
                val platformStates = mutableListOf<SaveState>()
                for (state in allStates) {
                    try {
                        val game = apiService.getGame(state.rom_id)
                        if (game.platform_slug == platform.slug) {
                            platformStates.add(state)
                        }
                    } catch (e: Exception) {
                        Log.w("MainViewModel", "Failed to get game ${state.rom_id} for state ${state.id}", e)
                    }
                }
                
                Log.d("MainViewModel", "Found ${platformStates.size} save states for platform ${platform.display_name}")
                
                // Group states by game
                val statesByGame = platformStates.groupBy { it.rom_id }
                val gamesWithStates = mutableListOf<GameWithStates>()
                
                for ((romId, gameStates) in statesByGame) {
                    try {
                        val game = apiService.getGame(romId)
                        gamesWithStates.add(GameWithStates(game, gameStates))
                    } catch (e: Exception) {
                        Log.w("MainViewModel", "Failed to get game $romId for states", e)
                    }
                }
                
                _uiState.value = _uiState.value.copy(
                    saveStatesForPlatform = gamesWithStates,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load save states for platform", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load save states: ${e.message}"
                )
            }
        }
    }
    
    fun navigateToGameSaveStates(game: Game) {
        navigateToScreen(Screen.GameSaveStates(game))
        loadGameSaveStates(game)
    }
    
    private fun loadGameSaveStates(game: Game) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                Log.d("MainViewModel", "Loading save states for game: ${game.name ?: game.fs_name_no_ext}")
                val states = apiService.getStates(romId = game.id)
                Log.d("MainViewModel", "Found ${states.size} save states for game")
                
                _uiState.value = _uiState.value.copy(
                    gameStates = states,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load save states for game", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load save states: ${e.message}"
                )
            }
        }
    }
    
    fun navigateToGameSaveFiles(game: Game) {
        navigateToScreen(Screen.GameSaveFiles(game))
        loadGameSaveFiles(game)
    }
    
    private fun loadGameSaveFiles(game: Game) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                Log.d("MainViewModel", "Loading save files for game: ${game.name ?: game.fs_name_no_ext}")
                val saves = apiService.getSaves(romId = game.id)
                Log.d("MainViewModel", "Found ${saves.size} save files for game")
                
                _uiState.value = _uiState.value.copy(
                    gameSaves = saves,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load save files for game", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load save files: ${e.message}"
                )
            }
        }
    }
    
    private fun loadSaveFiles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val platforms = _uiState.value.platforms
                if (platforms.isEmpty()) {
                    Log.w("MainViewModel", "No platforms loaded, loading platforms first")
                    loadPlatforms()
                    return@launch
                }
                
                Log.d("MainViewModel", "Loading all save files")
                val allSaves = apiService.getSaves()
                Log.d("MainViewModel", "Found ${allSaves.size} total save files")
                
                if (allSaves.isNotEmpty()) {
                    // Group by platform by checking each game's platform
                    val savesByPlatform = allSaves.groupBy { save ->
                        try {
                            val game = apiService.getGame(save.rom_id)
                            platforms.find { it.slug == game.platform_slug }
                        } catch (e: Exception) {
                            Log.w("MainViewModel", "Failed to get game ${save.rom_id} for save ${save.id}", e)
                            null
                        }
                    }.filterKeys { it != null }
                    
                    savesByPlatform.forEach { (platform, saves) ->
                        Log.d("MainViewModel", "${platform?.display_name}: ${saves.size} save files")
                    }
                    
                    val platformSaveFiles = savesByPlatform.map { (platform, saves) ->
                        val gameCount = saves.map { it.rom_id }.distinct().size
                        PlatformSaveFiles(
                            platform = platform!!,
                            saveCount = saves.size,
                            gameCount = gameCount
                        )
                    }.sortedBy { it.platform.display_name }
                    
                    _uiState.value = _uiState.value.copy(
                        saveFiles = allSaves,
                        saveFilesPlatforms = platformSaveFiles,
                        isLoading = false
                    )
                } else {
                    Log.d("MainViewModel", "No save files found")
                    _uiState.value = _uiState.value.copy(
                        saveFiles = emptyList(),
                        saveFilesPlatforms = emptyList(),
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load save files", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load save files: ${e.message}"
                )
            }
        }
    }
    
    private fun loadSaveStates() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val platforms = _uiState.value.platforms
                Log.d("MainViewModel", "Loading save states for ${platforms.size} platforms")
                
                val allSaveStates = mutableListOf<SaveState>()
                val saveStatesPlatforms = mutableListOf<PlatformSaveStates>()
                
                // First try to get all save states without platform filtering
                try {
                    val allStates = apiService.getStates()
                    Log.d("MainViewModel", "Found ${allStates.size} total save states across all platforms")
                    allSaveStates.addAll(allStates)
                    
                    if (allStates.isNotEmpty()) {
                        // Group by platform
                        val statesByPlatform = allStates.groupBy { state ->
                            // We need to get the game to find its platform - this is expensive but necessary
                            try {
                                val game = apiService.getGame(state.rom_id)
                                platforms.find { it.slug == game.platform_slug }
                            } catch (e: Exception) {
                                Log.w("MainViewModel", "Failed to get game ${state.rom_id} for state ${state.id}", e)
                                null
                            }
                        }.filterKeys { it != null }
                        
                        statesByPlatform.forEach { (platform, states) ->
                            if (platform != null) {
                                Log.d("MainViewModel", "Platform ${platform.display_name}: ${states.size} states")
                                saveStatesPlatforms.add(
                                    PlatformSaveStates(
                                        platform = platform,
                                        stateCount = states.size,
                                        gameCount = states.map { it.rom_id }.distinct().size
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to load all save states, trying per-platform", e)
                    
                    // Fallback: Get save states for each platform individually
                    for (platform in platforms) {
                        try {
                            val platformStates = apiService.getStates(platformId = platform.id)
                            Log.d("MainViewModel", "Platform ${platform.display_name}: ${platformStates.size} states")
                            if (platformStates.isNotEmpty()) {
                                allSaveStates.addAll(platformStates)
                                saveStatesPlatforms.add(
                                    PlatformSaveStates(
                                        platform = platform,
                                        stateCount = platformStates.size,
                                        gameCount = platformStates.map { it.rom_id }.distinct().size
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.w("MainViewModel", "Failed to load states for platform ${platform.display_name}", e)
                        }
                    }
                }
                
                Log.d("MainViewModel", "Final result: ${allSaveStates.size} total states, ${saveStatesPlatforms.size} platforms with states")
                
                _uiState.value = _uiState.value.copy(
                    saveStates = allSaveStates,
                    saveStatesPlatforms = saveStatesPlatforms,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load save states", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load save states: ${e.message}",
                    isLoading = false
                )
            }
        }
    }
    
    fun downloadAllSaveFiles() {
        viewModelScope.launch {
            try {
                val settings = _uiState.value.settings
                if (settings.saveFilesDirectory.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        error = "Please set a save files directory in Settings before downloading save files"
                    )
                    return@launch
                }
                val saveFiles = _uiState.value.saveFiles
                Log.d("MainViewModel", "Starting download of ${saveFiles.size} save files")
                Log.d("MainViewModel", "Save files directory: '${settings.saveFilesDirectory}'")
                downloadManager.downloadSaveFiles(saveFiles, settings)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to download all save files", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to start save files download: ${e.message}"
                )
            }
        }
    }
    
    fun downloadAllSaveStates() {
        viewModelScope.launch {
            try {
                val settings = _uiState.value.settings
                if (settings.saveStatesDirectory.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        error = "Please set a save states directory in Settings before downloading save states"
                    )
                    return@launch
                }
                val saveStates = _uiState.value.saveStates
                Log.d("MainViewModel", "Starting download of ${saveStates.size} save states")
                Log.d("MainViewModel", "Save states directory: '${settings.saveStatesDirectory}'")
                downloadManager.downloadSaveStates(saveStates, settings)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to download all save states", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to start save states download: ${e.message}"
                )
            }
        }
    }
    
    fun downloadPlatformSaveFiles(platform: Platform) {
        viewModelScope.launch {
            try {
                val settings = _uiState.value.settings
                if (settings.saveFilesDirectory.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        error = "Please set a save files directory in Settings before downloading save files"
                    )
                    return@launch
                }
                val platformSaveFiles = _uiState.value.saveFilesForPlatform.flatMap { it.saves }
                Log.d("MainViewModel", "Starting download of ${platformSaveFiles.size} save files for platform ${platform.display_name}")
                downloadManager.downloadSaveFiles(platformSaveFiles, settings)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to download platform save files", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to download save files: ${e.message}"
                )
            }
        }
    }
    
    fun downloadPlatformSaveStates(platform: Platform) {
        viewModelScope.launch {
            try {
                val settings = _uiState.value.settings
                if (settings.saveStatesDirectory.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        error = "Please set a save states directory in Settings before downloading save states"
                    )
                    return@launch
                }
                val platformSaveStates = _uiState.value.saveStatesForPlatform.flatMap { it.states }
                Log.d("MainViewModel", "Starting download of ${platformSaveStates.size} save states for platform ${platform.display_name}")
                downloadManager.downloadSaveStates(platformSaveStates, settings)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to download platform save states", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to download save states: ${e.message}"
                )
            }
        }
    }
    
    fun downloadGameSaveFiles(game: Game) {
        viewModelScope.launch {
            try {
                val settings = _uiState.value.settings
                if (settings.saveFilesDirectory.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        error = "Please set a save files directory in Settings before downloading save files"
                    )
                    return@launch
                }
                val gameSaveFiles = _uiState.value.gameSaves
                Log.d("MainViewModel", "Starting download of ${gameSaveFiles.size} save files for game ${game.name ?: game.fs_name_no_ext}")
                downloadManager.downloadSaveFiles(gameSaveFiles, settings)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to download game save files", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to download save files: ${e.message}"
                )
            }
        }
    }
    
    fun downloadGameSaveStates(game: Game) {
        viewModelScope.launch {
            try {
                val settings = _uiState.value.settings
                if (settings.saveStatesDirectory.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        error = "Please set a save states directory in Settings before downloading save states"
                    )
                    return@launch
                }
                val gameSaveStates = _uiState.value.gameStates
                Log.d("MainViewModel", "Starting download of ${gameSaveStates.size} save states for game ${game.name ?: game.fs_name_no_ext}")
                downloadManager.downloadSaveStates(gameSaveStates, settings)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to download game save states", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to download save states: ${e.message}"
                )
            }
        }
    }
    
    fun downloadSingleSaveFile(saveFile: com.romm.android.data.SaveFile) {
        viewModelScope.launch {
            try {
                val settings = _uiState.value.settings
                if (settings.saveFilesDirectory.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        error = "Please set a save files directory in Settings before downloading save files"
                    )
                    return@launch
                }
                Log.d("MainViewModel", "Starting download of save file: ${saveFile.file_name}")
                Log.d("MainViewModel", "Save files directory: '${settings.saveFilesDirectory}'")
                downloadManager.downloadSaveFiles(listOf(saveFile), settings)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to download save file", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to download save file: ${e.message}"
                )
            }
        }
    }
    
    fun downloadSingleSaveState(saveState: com.romm.android.data.SaveState) {
        viewModelScope.launch {
            try {
                val settings = _uiState.value.settings
                if (settings.saveStatesDirectory.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        error = "Please set a save states directory in Settings before downloading save states"
                    )
                    return@launch
                }
                Log.d("MainViewModel", "Starting download of save state: ${saveState.file_name}")
                Log.d("MainViewModel", "Save states directory: '${settings.saveStatesDirectory}'")
                downloadManager.downloadSaveStates(listOf(saveState), settings)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to download save state", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to download save state: ${e.message}"
                )
            }
        }
    }
}

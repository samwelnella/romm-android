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
import androidx.compose.foundation.layout.*
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
import com.romm.android.network.RomMApiService
import com.romm.android.ui.theme.RomMTheme
import com.romm.android.ui.components.*
import com.romm.android.ui.screens.*
import com.romm.android.utils.DownloadManager
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
fun RomMApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Handle Android system back gesture
    BackHandler(enabled = uiState.screenHistory.isNotEmpty()) {
        viewModel.goBack()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RomM Android") },
                actions = {
                    IconButton(onClick = { viewModel.showSettings() }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
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
                        onSettingsChanged = viewModel::updateSettings,
                        onBack = viewModel::goBack
                    )
                }
                is Screen.PlatformList -> {
                    PlatformListScreen(
                        platforms = uiState.platforms,
                        isLoading = uiState.isLoading,
                        onPlatformClick = viewModel::selectPlatform,
                        onCollectionsClick = viewModel::showCollections,
                        onRefresh = viewModel::refreshData
                    )
                }
                is Screen.CollectionList -> {
                    CollectionListScreen(
                        collections = uiState.collections,
                        isLoading = uiState.isLoading,
                        onCollectionClick = viewModel::selectCollection,
                        onBack = viewModel::goBack,
                        onRefresh = viewModel::refreshData
                    )
                }
                is Screen.GameList -> {
                    GameListScreen(
                        games = uiState.games,
                        isLoading = uiState.isLoading,
                        title = screen.title,
                        onGameClick = viewModel::selectGame,
                        onDownloadAll = { viewModel.downloadAllGames(screen.platformId, screen.collectionId) },
                        onDownloadMissing = { viewModel.downloadMissingGames(screen.platformId, screen.collectionId) },
                        onDownloadFirmware = screen.platformId?.let { { viewModel.downloadFirmware(it) } },
                        onBack = viewModel::goBack,
                        onRefresh = { viewModel.refreshGames(screen.platformId, screen.collectionId) }
                    )
                }
                is Screen.GameDetails -> {
                    GameDetailsScreen(
                        game = screen.game,
                        onDownload = viewModel::downloadGame,
                        onBack = viewModel::goBack
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
        }
    }
}

// Data Classes
data class UiState(
    val currentScreen: Screen = Screen.PlatformList,
    val screenHistory: List<Screen> = emptyList(),
    val platforms: List<Platform> = emptyList(),
    val collections: List<com.romm.android.data.Collection> = emptyList(), // Fully qualified name
    val games: List<Game> = emptyList(),
    val cachedGames: Map<String, List<Game>> = emptyMap(), // Cache games by platformId or collectionId
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    val settings: AppSettings = AppSettings()
)

sealed class Screen {
    object Settings : Screen()
    object PlatformList : Screen()
    object CollectionList : Screen()
    data class GameList(
        val title: String,
        val platformId: Int? = null,
        val collectionId: Int? = null
    ) : Screen()
    data class GameDetails(val game: Game) : Screen()
}

// MainViewModel.kt
@HiltViewModel
class MainViewModel @Inject constructor(
    private val apiService: RomMApiService,
    private val downloadManager: DownloadManager,
    private val settingsRepository: SettingsRepository
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
        viewModelScope.launch {
            try {
                settingsRepository.updateSettings(settings)
                
                // Show success message
                _uiState.value = _uiState.value.copy(successMessage = "Settings saved successfully!")
                
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
    
    private fun navigateToScreen(newScreen: Screen) {
        val currentState = _uiState.value
        val newHistory = currentState.screenHistory + currentState.currentScreen
        _uiState.value = currentState.copy(
            currentScreen = newScreen,
            screenHistory = newHistory
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
                else -> null
            }
            
            _uiState.value = currentState.copy(
                currentScreen = previousScreen,
                screenHistory = newHistory,
                games = cachedGames ?: currentState.games // Use cached games if available
            )
            
            // Only reload if we don't have cached data
            if (cachedGames == null) {
                when (previousScreen) {
                    is Screen.GameList -> {
                        refreshGames(previousScreen.platformId, previousScreen.collectionId)
                    }
                    else -> { /* No additional loading needed */ }
                }
            }
        } else {
            // No history, go to platform list
            _uiState.value = currentState.copy(currentScreen = Screen.PlatformList)
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
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val games = apiService.getGames(platformId = platformId)
                val cacheKey = "platform_$platformId"
                _uiState.value = _uiState.value.copy(
                    games = games,
                    cachedGames = _uiState.value.cachedGames + (cacheKey to games),
                    isLoading = false
                )
                Log.d("MainViewModel", "Loaded ${games.size} games for platform $platformId")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load games for platform $platformId", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load games: ${e.message}",
                    isLoading = false
                )
            }
        }
    }
    
    private fun loadGamesForCollection(collectionId: Int) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val games = apiService.getGames(collectionId = collectionId)
                val cacheKey = "collection_$collectionId"
                _uiState.value = _uiState.value.copy(
                    games = games,
                    cachedGames = _uiState.value.cachedGames + (cacheKey to games),
                    isLoading = false
                )
                Log.d("MainViewModel", "Loaded ${games.size} games for collection $collectionId")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load games for collection $collectionId", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load games: ${e.message}",
                    isLoading = false
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
}

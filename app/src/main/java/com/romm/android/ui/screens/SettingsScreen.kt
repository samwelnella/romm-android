package com.romm.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.romm.android.data.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit
) {
    var currentSettings by remember { mutableStateOf(settings) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    
    // Directory picker launcher for games/ROM downloads
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistent permission
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            
            // Update settings with the selected URI
            currentSettings = currentSettings.copy(downloadDirectory = uri.toString())
        }
    }
    
    // Directory picker launcher for save files
    val saveFilesDirectoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistent permission
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            
            // Update settings with the selected URI
            currentSettings = currentSettings.copy(saveFilesDirectory = uri.toString())
        }
    }
    
    // Directory picker launcher for save states
    val saveStatesDirectoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistent permission
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            
            // Update settings with the selected URI
            currentSettings = currentSettings.copy(saveStatesDirectory = uri.toString())
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "RomM Server",
                    style = MaterialTheme.typography.titleMedium
                )
                
                OutlinedTextField(
                    value = currentSettings.host,
                    onValueChange = { currentSettings = currentSettings.copy(host = it) },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://192.168.1.100:8080 or https://romm.example.com") },
                    supportingText = { Text("Include http:// or https:// and port if needed") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = currentSettings.username,
                    onValueChange = { currentSettings = currentSettings.copy(username = it) },
                    label = { Text("Username") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = currentSettings.password,
                    onValueChange = { currentSettings = currentSettings.copy(password = it) },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Download Settings",
                    style = MaterialTheme.typography.titleMedium
                )
                
                // Display selected directory
                if (currentSettings.downloadDirectory.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                "Selected Directory:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                getDisplayNameForUri(currentSettings.downloadDirectory),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                // Directory selection button
                Button(
                    onClick = { directoryPickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (currentSettings.downloadDirectory.isEmpty()) 
                            "Select Download Directory" 
                        else 
                            "Change Directory"
                    )
                }
                
                // Responsive inline number picker interface
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Max Concurrent Downloads:",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f, fill = false) // Take only needed space
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { 
                                if (currentSettings.maxConcurrentDownloads > 1) {
                                    currentSettings = currentSettings.copy(maxConcurrentDownloads = currentSettings.maxConcurrentDownloads - 1)
                                }
                            },
                            enabled = currentSettings.maxConcurrentDownloads > 1,
                            modifier = Modifier.widthIn(min = 40.dp) // Minimum width but can expand
                        ) {
                            Text("-")
                        }
                        
                        Text(
                            text = currentSettings.maxConcurrentDownloads.toString(),
                            modifier = Modifier.widthIn(min = 24.dp),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )
                        
                        Button(
                            onClick = { 
                                if (currentSettings.maxConcurrentDownloads < 10) {
                                    currentSettings = currentSettings.copy(maxConcurrentDownloads = currentSettings.maxConcurrentDownloads + 1)
                                }
                            },
                            enabled = currentSettings.maxConcurrentDownloads < 10,
                            modifier = Modifier.widthIn(min = 40.dp) // Minimum width but can expand
                        ) {
                            Text("+")
                        }
                    }
                }
            }
        }
        
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Save Files Directory",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Text(
                    "Choose where to save downloaded save files. They will be organized as [Directory]/[Platform]/[Emulator]/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Display selected directory
                if (currentSettings.saveFilesDirectory.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                "Selected Directory:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                getDisplayNameForUri(currentSettings.saveFilesDirectory),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                // Directory selection button
                Button(
                    onClick = { saveFilesDirectoryPickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (currentSettings.saveFilesDirectory.isEmpty()) 
                            "Select Save Files Directory" 
                        else 
                            "Change Directory"
                    )
                }
            }
        }
        
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Save States Directory",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Text(
                    "Choose where to save downloaded save states. They will be organized as [Directory]/[Platform]/[Emulator]/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Display selected directory
                if (currentSettings.saveStatesDirectory.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                "Selected Directory:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                getDisplayNameForUri(currentSettings.saveStatesDirectory),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                // Directory selection button
                Button(
                    onClick = { saveStatesDirectoryPickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (currentSettings.saveStatesDirectory.isEmpty()) 
                            "Select Save States Directory" 
                        else 
                            "Change Directory"
                    )
                }
            }
        }
        
        Button(
            onClick = { 
                try {
                    onSettingsChanged(currentSettings)
                    // Settings saved - navigation will be handled by the ViewModel/TopAppBar
                } catch (e: Exception) {
                    // If there's an error, stay on settings screen
                    // Error will be shown via the existing error handling
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = currentSettings.host.isNotEmpty() && currentSettings.downloadDirectory.isNotEmpty()
        ) {
            Text("Save Settings")
        }
        
        if (currentSettings.host.isEmpty() || currentSettings.downloadDirectory.isEmpty()) {
            Text(
                "Please configure server URL and download directory to continue",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// Helper function to get a user-friendly display name for the URI
private fun getDisplayNameForUri(uriString: String): String {
    if (uriString.isEmpty()) return "No directory selected"
    
    return try {
        val uri = Uri.parse(uriString)
        // Extract the last part of the path for display
        val pathSegments = uri.pathSegments
        if (pathSegments.isNotEmpty()) {
            pathSegments.last().split(":").lastOrNull() ?: "Selected Directory"
        } else {
            "Selected Directory"
        }
    } catch (e: Exception) {
        "Selected Directory"
    }
}

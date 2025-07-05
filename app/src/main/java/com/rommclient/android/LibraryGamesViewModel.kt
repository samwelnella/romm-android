
package com.rommclient.android

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryGamesViewModel(application: Application) : AndroidViewModel(application) {
    private val _files = MutableLiveData<List<String>>()
    val files: LiveData<List<String>> get() = _files

    fun loadGames(slug: String, baseUri: Uri?) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                val db = RommDatabaseHelper(context)

                val rawPairs = db.getDownloadsForSlug(slug)
                android.util.Log.d("LibraryViewModel", "DB results for slug '$slug': $rawPairs")

                val rawFiles = rawPairs.map { it.second }
                val rootDir = baseUri?.let { DocumentFile.fromTreeUri(context, it) }
                val platformDir = rootDir?.listFiles()?.find { it.name.equals(slug, ignoreCase = true) && it.isDirectory }

                android.util.Log.d("LibraryViewModel", "Base URI: $baseUri")
                rootDir?.listFiles()?.forEach {
                    android.util.Log.d("LibraryViewModel", "File in root folder: ${it.name}")
                }
                platformDir?.listFiles()?.forEach {
                    android.util.Log.d("LibraryViewModel", "File in platform folder '$slug': ${it.name}")
                }

                val visibleFiles = rawFiles.mapNotNull { fileName ->
                    val match = platformDir?.listFiles()?.find {
                        it.name?.trim()?.equals(fileName.trim(), ignoreCase = true) == true
                    }
                    android.util.Log.d("LibraryViewModel", "Checking: $fileName → Match: ${match?.name}")
                    if (match?.exists() == true) fileName else null
                }

                visibleFiles
            }
            _files.value = result
        }
    }
}

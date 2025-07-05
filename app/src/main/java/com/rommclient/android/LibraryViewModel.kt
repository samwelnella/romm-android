
package com.rommclient.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val _platformSlugs = MutableLiveData<List<String>>()
    val platformSlugs: LiveData<List<String>> get() = _platformSlugs

    fun loadSlugs() {
        viewModelScope.launch {
            val slugs = withContext(Dispatchers.IO) {
                RommDatabaseHelper(getApplication()).getPlatformSlugs()
            }
            _platformSlugs.value = slugs
        }
    }
}

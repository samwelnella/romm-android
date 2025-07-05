package com.rommclient.android

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.annotation.MainThread
import android.content.Context

object DownloadProgressTracker {
    private val progressMap = mutableMapOf<String, MutableLiveData<Int>>()

    // Public getter for LiveData so UI can observe
    @MainThread
    fun getProgressLiveData(fileName: String): LiveData<Int> {
        // Ensure MutableLiveData is only created on the main thread
        return progressMap.getOrPut(fileName) { MutableLiveData(0) }
    }

    // Called during download to post progress and persist completed downloads
    fun updateProgress(fileName: String, percent: Int, platformSlug: String, context: Context) {
        progressMap.getOrPut(fileName) { MutableLiveData(0) }.postValue(percent)

        if (percent == 100) {
            val db = RommDatabaseHelper(context)
            db.insertDownload(platformSlug, fileName)
        }
    }

    // Call when download completes or is canceled
    fun clearProgress(fileName: String) {
        progressMap.remove(fileName)
    }
}
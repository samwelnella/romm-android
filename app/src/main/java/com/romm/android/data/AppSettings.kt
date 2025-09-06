package com.romm.android.data

data class AppSettings(
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val downloadDirectory: String = "",
    val maxConcurrentDownloads: Int = 3,
    val saveFilesDirectory: String = "",
    val saveStatesDirectory: String = ""
)

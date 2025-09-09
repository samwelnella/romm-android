package com.romm.android.data

data class AppSettings(
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val downloadDirectory: String = "",
    val maxConcurrentDownloads: Int = 3,
    val saveFilesDirectory: String = "",
    val saveStatesDirectory: String = "",
    val saveFileHistoryLimit: Int = 0, // 0 = no limit, 1-10 = max versions to keep
    val saveStateHistoryLimit: Int = 0 // 0 = no limit, 1-10 = max versions to keep
)

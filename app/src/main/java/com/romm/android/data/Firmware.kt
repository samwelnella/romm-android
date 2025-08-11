package com.romm.android.data

data class Firmware(
    val id: Int,
    val file_name: String,
    val file_size_bytes: Long,
    val platform_id: Int
)

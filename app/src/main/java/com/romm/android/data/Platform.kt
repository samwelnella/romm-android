package com.romm.android.data

data class Platform(
    val id: Int,
    val name: String,
    val slug: String,
    val fs_slug: String,
    val rom_count: Int,
    val custom_name: String?,
    val display_name: String
)

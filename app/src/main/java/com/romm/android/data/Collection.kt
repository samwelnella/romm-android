package com.romm.android.data

data class Collection(
    val id: Int,
    val name: String,
    val description: String,
    val rom_count: Int,
    val is_public: Boolean
)

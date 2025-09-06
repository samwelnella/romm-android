package com.romm.android.data

import com.google.gson.annotations.SerializedName

data class SaveFile(
    val id: Int,
    val name: String? = null,
    val file_name: String,
    val file_path: String,
    val full_path: String? = null,
    val download_path: String? = null,
    val file_size_bytes: Long,
    val file_extension: String,
    val emulator: String? = null,
    val rom_id: Int,
    val user_id: Int,
    val missing_from_fs: Boolean? = null,
    val created_at: String,
    val updated_at: String
)

data class SaveFileResponse(
    val items: List<SaveFile>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

data class SaveState(
    val id: Int,
    val name: String? = null,
    val file_name: String,
    val file_path: String,
    val full_path: String? = null,
    val download_path: String? = null,
    val file_size_bytes: Long,
    val file_extension: String,
    val emulator: String? = null,
    val rom_id: Int,
    val user_id: Int,
    val missing_from_fs: Boolean? = null,
    val created_at: String,
    val updated_at: String
)

data class SaveStateResponse(
    val items: List<SaveState>,
    val total: Int,
    val limit: Int,
    val offset: Int
)
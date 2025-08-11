package com.romm.android.data

data class Game(
    val id: Int,
    val name: String?,
    val fs_name: String,
    val fs_name_no_tags: String,
    val fs_name_no_ext: String,
    val platform_slug: String,
    val platform_fs_slug: String,
    val revision: String?,
    val regions: List<String>,
    val languages: List<String>,
    val tags: List<String>,
    val multi: Boolean,
    val files: List<RomFile>,
    val summary: String?,
    val path_cover_small: String?,
    val missing_from_fs: Boolean
)

data class RomFile(
    val id: Int,
    val file_name: String,
    val file_size_bytes: Long,
    val file_path: String
)

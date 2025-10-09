package com.romm.android.utils

/**
 * Maps legacy/incorrect platform slugs from old remote files to correct platform slugs.
 * This handles files that were uploaded with incorrect platform mappings before fixes were applied.
 */
object LegacyPlatformSlugMapper {

    private val legacySlugMapping = mapOf(
        "turbografx16--1" to "tg16"
    )

    /**
     * Normalize a legacy platform slug to the correct slug.
     *
     * @param platformSlug The platform slug extracted from a remote file path
     * @return The normalized platform slug, or the original if no mapping exists
     */
    fun normalize(platformSlug: String): String {
        return legacySlugMapping[platformSlug] ?: platformSlug
    }

    /**
     * Check if a platform slug needs normalization.
     *
     * @param platformSlug The platform slug to check
     * @return True if the slug has a legacy mapping, false otherwise
     */
    fun isLegacySlug(platformSlug: String): Boolean {
        return legacySlugMapping.containsKey(platformSlug)
    }

    /**
     * Get all legacy platform slugs that have mappings.
     *
     * @return Set of all legacy platform slugs
     */
    fun getLegacySlugs(): Set<String> {
        return legacySlugMapping.keys
    }
}

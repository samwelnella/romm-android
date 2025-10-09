package com.romm.android.utils

import android.util.Log

/**
 * Centralized logging utility for sync operations with configurable log levels.
 *
 * This allows for detailed debug logging during development/troubleshooting,
 * while keeping production logs clean and focused on important events.
 */
object SyncLogger {

    enum class LogLevel {
        VERBOSE,  // Everything including detailed trace info
        DEBUG,    // Development debug information
        INFO,     // General informational messages
        WARN,     // Warning messages
        ERROR,    // Error messages only
        NONE      // No logging
    }

    /**
     * Current log level. Set to INFO by default.
     * Change to VERBOSE or DEBUG for detailed troubleshooting.
     * Change to WARN or ERROR for production to reduce log noise.
     */
    var level: LogLevel = LogLevel.INFO

    private const val TAG_SYNC = "SyncManager"
    private const val TAG_FILE_SCANNER = "FileScanner"

    // Convenience methods for SyncManager
    fun v(tag: String = TAG_SYNC, message: String) {
        if (level.ordinal <= LogLevel.VERBOSE.ordinal) {
            Log.v(tag, message)
        }
    }

    fun d(tag: String = TAG_SYNC, message: String) {
        if (level.ordinal <= LogLevel.DEBUG.ordinal) {
            Log.d(tag, message)
        }
    }

    fun i(tag: String = TAG_SYNC, message: String) {
        if (level.ordinal <= LogLevel.INFO.ordinal) {
            Log.i(tag, message)
        }
    }

    fun w(tag: String = TAG_SYNC, message: String, throwable: Throwable? = null) {
        if (level.ordinal <= LogLevel.WARN.ordinal) {
            if (throwable != null) {
                Log.w(tag, message, throwable)
            } else {
                Log.w(tag, message)
            }
        }
    }

    fun e(tag: String = TAG_SYNC, message: String, throwable: Throwable? = null) {
        if (level.ordinal <= LogLevel.ERROR.ordinal) {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
        }
    }

    /**
     * Log detailed processing information (file-by-file details).
     * These are VERBOSE level and only show when level is set to VERBOSE.
     */
    fun verbose(tag: String = TAG_SYNC, message: String) = v(tag, message)

    /**
     * Log debug information (function entry/exit, important state changes).
     * These are DEBUG level and show when level is DEBUG or VERBOSE.
     */
    fun debug(tag: String = TAG_SYNC, message: String) = d(tag, message)

    /**
     * Log important operational information (summary counts, major operations).
     * These are INFO level and show when level is INFO, DEBUG, or VERBOSE.
     */
    fun info(tag: String = TAG_SYNC, message: String) = i(tag, message)

    /**
     * Log warnings (recoverable errors, unexpected situations).
     * These are WARN level and show when level is WARN, INFO, DEBUG, or VERBOSE.
     */
    fun warn(tag: String = TAG_SYNC, message: String, throwable: Throwable? = null) = w(tag, message, throwable)

    /**
     * Log errors (failures, exceptions).
     * These are ERROR level and always show unless level is NONE.
     */
    fun error(tag: String = TAG_SYNC, message: String, throwable: Throwable? = null) = e(tag, message, throwable)
}

package com.rommclient.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class RommDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "romm.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.apply {
            execSQL("CREATE TABLE IF NOT EXISTS downloaded_roms (platform_slug TEXT, file_name TEXT, PRIMARY KEY(platform_slug, file_name))")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.apply {
            execSQL("DROP TABLE IF EXISTS downloaded_roms")
            onCreate(this)
        }
    }

    fun isDownloaded(platformSlug: String, fileName: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM downloaded_roms WHERE platform_slug=? AND file_name=?",
            arrayOf(platformSlug, fileName)
        )
        val exists = cursor.moveToFirst()
        android.util.Log.d("RommDB", "isDownloaded($platformSlug, $fileName): $exists")
        cursor.close()
        db.close()
        return exists
    }

    fun insertDownload(platformSlug: String, fileName: String) {
        writableDatabase.use { db ->
            val values = ContentValues().apply {
                put("platform_slug", platformSlug)
                put("file_name", fileName)
            }
            val result = db.insertWithOnConflict("downloaded_roms", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (result != -1L) {
                android.util.Log.d("RommDB", "insertDownload($platformSlug, $fileName): INSERTED")
            } else {
                android.util.Log.d("RommDB", "insertDownload($platformSlug, $fileName): ALREADY EXISTS")
            }
        }
    }

    fun getAllDownloads(): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val db = readableDatabase
        db.rawQuery("SELECT platform_slug, file_name FROM downloaded_roms", null).use { cursor ->
            while (cursor.moveToNext()) {
                val slug = cursor.getString(0)
                val file = cursor.getString(1)
                results.add(slug to file)
            }
        }
        db.close()
        return results
    }

    fun getPlatformSlugs(): List<String> {
        val results = mutableListOf<String>()
        val db = readableDatabase
        db.rawQuery("SELECT DISTINCT platform_slug FROM downloaded_roms", null).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(cursor.getString(0))
            }
        }
        db.close()
        return results
    }

    fun getDownloadsForSlug(slug: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val db = readableDatabase
        db.rawQuery("SELECT platform_slug, file_name FROM downloaded_roms WHERE platform_slug=?", arrayOf(slug)).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(cursor.getString(0) to cursor.getString(1))
            }
        }
        db.close()
        return results
    }

    fun deleteDownload(slug: String, fileName: String) {
        val db = writableDatabase
        db.delete("downloaded_roms", "platform_slug=? AND file_name=?", arrayOf(slug, fileName))
        db.close()
    }
    // Batch insert downloads with transaction
    fun insertDownloadsBatch(downloads: List<Pair<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR IGNORE INTO downloaded_roms (platform_slug, file_name) VALUES (?, ?)"
            )
            for ((platformSlug, fileName) in downloads) {
                stmt.bindString(1, platformSlug)
                stmt.bindString(2, fileName)
                stmt.executeInsert()
                android.util.Log.d("RommDB", "insertDownloadsBatch: $platformSlug, $fileName")
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }
}
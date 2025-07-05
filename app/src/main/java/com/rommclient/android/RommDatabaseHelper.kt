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
        return db.rawQuery(
            "SELECT 1 FROM downloaded_roms WHERE platform_slug=? AND file_name=?",
            arrayOf(platformSlug, fileName)
        ).use { cursor ->
            cursor.moveToFirst()
        }
    }

    fun insertDownload(platformSlug: String, fileName: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("platform_slug", platformSlug)
            put("file_name", fileName)
        }
        db.insertWithOnConflict("downloaded_roms", null, values, SQLiteDatabase.CONFLICT_IGNORE)
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
        return results
    }

    fun deleteDownload(slug: String, fileName: String) {
        val db = writableDatabase
        db.delete("downloaded_roms", "platform_slug=? AND file_name=?", arrayOf(slug, fileName))
    }
}

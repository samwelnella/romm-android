
package com.rommclient.android

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import android.content.DialogInterface

class LibraryGamesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        setContentView(layout)

        val slug = intent.getStringExtra("platform_slug") ?: return
        title = "Library: $slug"

        val db = RommDatabaseHelper(this)
        val files = db.getDownloadsForSlug(slug)

        for ((platform, fileName) in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val fileText = TextView(this@LibraryGamesActivity).apply {
                    text = fileName
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val deleteBtn = Button(this@LibraryGamesActivity).apply {
                    text = "🗑"
                    setOnClickListener {
                        AlertDialog.Builder(this@LibraryGamesActivity)
                            .setTitle("Delete Game")
                            .setMessage("Are you sure you want to delete \"$fileName\"?")
                            .setPositiveButton("Delete") { dialogInterface: DialogInterface, which: Int ->
                                try {
                                    val prefs = getSharedPreferences("romm_prefs", MODE_PRIVATE)
                                    val uri = Uri.parse(prefs.getString("download_directory", null))
                                    val baseDir = DocumentFile.fromTreeUri(this@LibraryGamesActivity, uri)
                                    val platformDir = baseDir?.findFile(slug)
                                    val gameFile = platformDir?.findFile(fileName)

                                    if (gameFile != null && gameFile.exists()) {
                                        if (!gameFile.delete()) {
                                            Toast.makeText(this@LibraryGamesActivity, "Could not delete file", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(this@LibraryGamesActivity, "File not found", Toast.LENGTH_SHORT).show()
                                    }

                                    db.deleteDownload(slug, fileName)
                                    recreate()
                                } catch (e: Exception) {
                                    Toast.makeText(this@LibraryGamesActivity, "Failed to delete", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
                addView(fileText)
                addView(deleteBtn)
            }
            layout.addView(row)
        }
    }
}

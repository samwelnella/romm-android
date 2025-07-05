package com.rommclient.android

import androidx.activity.viewModels
import androidx.lifecycle.Observer

import android.net.Uri
import android.os.Bundle
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
        val viewModel: LibraryGamesViewModel by viewModels()
        val prefs = getSharedPreferences("romm_prefs", MODE_PRIVATE)
        val uri = Uri.parse(prefs.getString("download_directory", null))
        val baseDir = DocumentFile.fromTreeUri(this, uri)

        viewModel.files.observe(this, Observer { files ->
            layout.removeAllViews()
            for (fileName in files) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val fileText = TextView(this@LibraryGamesActivity).apply {
                        text = fileName
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val deleteBtn = Button(this@LibraryGamesActivity).apply {
                        text = "🗑"
                        setOnClickListener {
                            confirmDelete(fileName, slug, baseDir, db)
                        }
                    }
                    addView(fileText)
                    addView(deleteBtn)
                }
                layout.addView(row)

                val spacer = Space(this@LibraryGamesActivity)
                spacer.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 12)
                layout.addView(spacer)
            }
        })

        viewModel.loadGames(slug, uri)

        // Removed obsolete for-loop over files. UI is now handled in LiveData observer above.
    }

    private fun confirmDelete(
        fileName: String,
        slug: String,
        baseDir: DocumentFile?,
        db: RommDatabaseHelper
    ) {
        AlertDialog.Builder(this@LibraryGamesActivity)
            .setTitle("Delete Game")
            .setMessage("Are you sure you want to delete \"$fileName\"?")
            .setPositiveButton("Delete") { dialogInterface: DialogInterface, which: Int ->
                try {
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

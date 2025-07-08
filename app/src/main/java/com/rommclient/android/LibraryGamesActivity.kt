package com.rommclient.android

import com.rommclient.android.MainActivity
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Observer
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import android.view.View
import android.widget.ImageButton
import android.widget.TextView

class LibraryGamesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LibraryGamesActivity)
        }
        setContentView(recyclerView)
        // Snackbar handling is now centralized in MainActivity.

        val slug = intent.getStringExtra("platform_slug") ?: return
        title = "Library: $slug"

        val db = RommDatabaseHelper(this)
        val viewModel: LibraryGamesViewModel by viewModels()
        val prefs = getSharedPreferences("romm_prefs", MODE_PRIVATE)
        val uri = Uri.parse(prefs.getString("download_directory", null))
        val baseDir = DocumentFile.fromTreeUri(this, uri)

        val adapter = LibraryGamesAdapter(slug, baseDir, db)
        recyclerView.adapter = adapter

        viewModel.files.observe(this, Observer { files ->
            adapter.updateFiles(files)
        })

        viewModel.loadGames(slug, uri)
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
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val platformDir = baseDir?.findFile(slug)
                    val gameFile = platformDir?.findFile(fileName)

                    if (gameFile != null && gameFile.exists()) {
                        if (!gameFile.delete()) {
                            Toast.makeText(this, "Could not delete file", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                    }

                    db.deleteDownload(slug, fileName)
                    recreate()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to delete", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class LibraryGamesAdapter(
        private val slug: String,
        private val baseDir: DocumentFile?,
        private val db: RommDatabaseHelper
    ) : RecyclerView.Adapter<LibraryGamesAdapter.GameViewHolder>() {

        private var files: List<String> = emptyList()

        fun updateFiles(newFiles: List<String>) {
            files = newFiles
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_library_game, parent, false)
            return GameViewHolder(view)
        }

        override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
            val fileName = files[position]
            holder.bind(fileName)
        }

        override fun getItemCount(): Int = files.size

        inner class GameViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val fileText: TextView = view.findViewById(R.id.game_name)
            private val deleteBtn: ImageButton = view.findViewById(R.id.delete_button)

            fun bind(fileName: String) {
                fileText.text = fileName
                deleteBtn.setOnClickListener {
                    confirmDelete(fileName, slug, baseDir, db)
                }
            }
        }
    }
}
package com.rommclient.android

import android.net.Uri
import android.util.Log
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class LibraryGamesFragment : Fragment() {
    private var snackbar: Snackbar? = null
    private lateinit var slug: String
    private lateinit var baseDir: DocumentFile
    private lateinit var db: RommDatabaseHelper
    private lateinit var adapter: LibraryGamesAdapter
    private lateinit var recyclerView: RecyclerView

    companion object {
        fun newInstance(slug: String): LibraryGamesFragment {
            return LibraryGamesFragment().apply {
                arguments = Bundle().apply {
                    putString("platform_slug", slug)
                }
            }
        }
    }

    private val viewModel: LibraryGamesViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
        }
        return recyclerView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        slug = requireArguments().getString("platform_slug") ?: return
        Log.d("LibraryGamesFragment", "Platform slug: $slug")
        requireActivity().title = "Library: $slug"

        db = RommDatabaseHelper(requireContext())
        val prefs = requireContext().getSharedPreferences("romm_prefs", 0)
        val uri = Uri.parse(prefs.getString("download_directory", null))
        baseDir = DocumentFile.fromTreeUri(requireContext(), uri)!!

        adapter = LibraryGamesAdapter(slug, baseDir, db)
        recyclerView.adapter = adapter

        viewModel.files.observe(viewLifecycleOwner, Observer { files ->
            Log.d("LibraryGamesFragment", "Files loaded: ${files.joinToString()}")
            adapter.updateFiles(files)
        })

        viewModel.loadGames(slug, uri)

        // Snackbar
        val rootView = requireActivity().findViewById<View>(android.R.id.content)
    }

    private fun confirmDelete(
        fileName: String,
        slug: String,
        baseDir: DocumentFile?,
        db: RommDatabaseHelper
    ) {
        Log.d("LibraryGamesFragment", "Deleting: $fileName from $slug")
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Game")
            .setMessage("Are you sure you want to delete \"$fileName\"?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val platformDir = baseDir?.findFile(slug)
                    val gameFile = platformDir?.findFile(fileName)

                    if (gameFile != null && gameFile.exists()) {
                        if (!gameFile.delete()) {
                            Toast.makeText(requireContext(), "Could not delete file", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(requireContext(), "File not found", Toast.LENGTH_SHORT).show()
                    }

                    db.deleteDownload(slug, fileName)
                    val prefs = requireContext().getSharedPreferences("romm_prefs", 0)
                    viewModel.loadGames(slug, Uri.parse(prefs.getString("download_directory", null)))
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to delete", Toast.LENGTH_SHORT).show()
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

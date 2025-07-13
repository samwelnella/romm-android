package com.rommclient.android

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File


import com.rommclient.android.PlatformMappings.esDeFolderMap

import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import androidx.core.content.ContextCompat



class GameListFragment : Fragment() {

    private val client = OkHttpClient()
    private var snackbar: Snackbar? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: GameAdapter
    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var pass: String
    private lateinit var downloadDir: String
    private lateinit var platformName: String
    private var platformId: Int? = null
    private var collectionId: Int? = null


    companion object {
        fun newInstance(
            host: String,
            port: String,
            user: String,
            pass: String,
            platformId: Int?,
            collectionId: Int?,
            name: String
        ): GameListFragment {
            return GameListFragment().apply {
                arguments = Bundle().apply {
                    putString("HOST", host)
                    putString("PORT", port)
                    putString("USER", user)
                    putString("PASS", pass)
                    putInt("PLATFORM_ID", platformId ?: -1)
                    putInt("COLLECTION_ID", collectionId ?: -1)
                    putString("NAME", name)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_game_list, container, false)
        recyclerView = view.findViewById(R.id.game_list_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("romm_prefs", 0)
        downloadDir = prefs.getString("download_directory", null) ?: run {
            Toast.makeText(requireContext(), "Please set your download directory in Settings first.", Toast.LENGTH_LONG).show()
            requireActivity().onBackPressedDispatcher.onBackPressed()
            return
        }

        val maxConcurrent = prefs.getInt("max_concurrent_downloads", 5)

        host = requireArguments().getString("HOST", "")
        port = requireArguments().getString("PORT", "")
        user = requireArguments().getString("USER", "")
        pass = requireArguments().getString("PASS", "")
        platformId = requireArguments().getInt("PLATFORM_ID").takeIf { it != -1 }
        collectionId = requireArguments().getInt("COLLECTION_ID").takeIf { it != -1 }
        platformName = requireArguments().getString("NAME", "RomM Platforms")

        (requireActivity() as AppCompatActivity).supportActionBar?.title = platformName

        loadGames()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.clear()
        inflater.inflate(R.menu.game_list_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_download_all -> {
                enqueueAllDownloads(skipExisting = false)
                true
            }
            R.id.menu_download_all_skip_existing -> {
                enqueueAllDownloads(skipExisting = true)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadGames() {
        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                LoadingDialogFragment.show(parentFragmentManager)
            }
            try {
                val roms = mutableListOf<JSONObject>()
                var offset = 0
                val limit = 100
                var totalCount = -1

                while (true) {
                    var url: String? = null
                    try {
                        // --- Begin new URL builder block ---
                        Log.d("URL_DEBUG", "host=$host, port=$port, user=$user, pass=$pass")

                        val hostInput = host.trim()
                        val uri = Uri.parse(if (hostInput.startsWith("http")) hostInput else "http://$hostInput")

                        val scheme = uri.scheme ?: "http"
                        val hostOnly = uri.host ?: throw IllegalArgumentException("Invalid host")
                        val portNumber = port?.trim()?.toIntOrNull()

                        val baseUrlBuilder = HttpUrl.Builder()
                            .scheme(scheme)
                            .host(hostOnly)
                        if (portNumber != null) {
                            baseUrlBuilder.port(portNumber)
                        }

                        val urlBuilder = baseUrlBuilder
                            .addPathSegment("api")
                            .addPathSegment("roms")
                            .addQueryParameter("limit", limit.toString())
                            .addQueryParameter("offset", offset.toString())
                            .addQueryParameter("expand", "platform")

                        platformId?.let {
                            urlBuilder.addQueryParameter("platform_id", it.toString())
                        }

                        collectionId?.let {
                            urlBuilder.addQueryParameter("collection_id", it.toString())
                        }

                        val httpUrl = urlBuilder.build()
                        url = httpUrl.toString()
                        Log.d("URL_DEBUG", "Built URL: $url")
                        // --- End new URL builder block ---
                    } catch (e: Exception) {
                        Log.e("URL_DEBUG", "Failed to build URL", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Failed to build URL: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    val requestBuilder = Request.Builder().url(url!!)
                        .addHeader("Accept", "application/json")

                    if (user.isNotBlank() && pass.isNotBlank()) {
                        val credential = Credentials.basic(user, pass)
                        requestBuilder.addHeader("Authorization", credential)
                    }

                    val request = requestBuilder.build()

                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Failed to load games: ${response.code} - ${response.message}", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    val body = response.body?.string()
                    if (body.isNullOrEmpty()) break

                    val root = JSONObject(body)
                    if (offset == 0) {
                        val newTitle = when {
                            root.has("collection") -> root.getJSONObject("collection").optString("name", null)
                            root.has("platform") -> root.getJSONObject("platform").optString("name", null)
                            else -> null
                        }
                        newTitle?.let {
                            withContext(Dispatchers.Main) {
                                (requireActivity() as AppCompatActivity).supportActionBar?.title = it
                            }
                        }
                    }

                    if (totalCount == -1) {
                        totalCount = root.optInt("total", -1)
                    }

                    val items = root.optJSONArray("items") ?: JSONArray()
                    if (items.length() == 0) break
                    for (i in 0 until items.length()) {
                        roms.add(items.getJSONObject(i))
                    }
                    withContext(Dispatchers.Main) {
                        val totalSoFar = roms.size
                        val percent = if (totalCount > 0) (totalSoFar * 100 / totalCount).coerceAtMost(100) else 100
                        LoadingDialogFragment.updateMessage(parentFragmentManager, "Loading games... $percent%")
                    }
                    if (totalCount > 0 && roms.size >= totalCount) break
                    offset += items.length()
                }

                withContext(Dispatchers.Main) {
                    adapter = GameAdapter(requireContext(), roms, object : GameAdapter.GameClickListener {
                        override fun onDownloadClick(game: JSONObject) {
                            handleDownloadClick(game)
                        }
                    }, viewLifecycleOwner)
                    recyclerView.adapter = adapter
                }
                withContext(Dispatchers.Main) {
                    LoadingDialogFragment.dismiss(parentFragmentManager)
                }
            } finally {
                // dismiss loading spinner if needed
            }
        }
    }

    private fun handleDownloadClick(game: JSONObject) {
        val files = game.optJSONArray("files") ?: return
        val file = files.optJSONObject(0) ?: return
        val fileName = file.optString("file_name")
        val platformSlug = game.optString("platform_fs_slug")
        val esDeFolder = esDeFolderMap[platformSlug] ?: platformSlug
        val romId = game.optInt("id")
        val fsName = file.optString("fs_name").ifBlank { file.optString("file_name") }
        val quotedFsName = Uri.encode(fsName)
        var downloadUrl: String? = null
        try {
            Log.d("URL_DEBUG", "host=$host, port=$port, user=$user, pass=$pass")
            val hostInput = host.trim()
            val uri = Uri.parse(if (hostInput.startsWith("http")) hostInput else "http://$hostInput")

            val scheme = uri.scheme ?: "http"
            val hostOnly = uri.host ?: throw IllegalArgumentException("Invalid host")
            val portNumber = port?.trim()?.toIntOrNull()

            val builder = okhttp3.HttpUrl.Builder()
                .scheme(scheme)
                .host(hostOnly)
            if (portNumber != null) {
                builder.port(portNumber)
            }
            builder.addPathSegment("api")
            builder.addPathSegment("roms")
            builder.addPathSegment(romId.toString())
            builder.addPathSegment("content")
            builder.addPathSegment(quotedFsName)
            builder.addQueryParameter("hidden_folder", "true")
            val httpUrl = builder.build()
            downloadUrl = httpUrl.toString()
            Log.d("URL_DEBUG", "Built URL: $downloadUrl")
        } catch (e: Exception) {
            Log.e("URL_DEBUG", "Failed to build download URL", e)
            Toast.makeText(requireContext(), "Failed to build download URL: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // Always start DownloadService directly
        val intent = Intent(requireContext(), DownloadService::class.java).apply {
            putExtra("file_url", downloadUrl)
            putExtra("file_name", fileName)
            putExtra("platform_slug", platformSlug)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }


    fun enqueueAllDownloads(skipExisting: Boolean) {
        val adapter = recyclerView.adapter as? GameAdapter ?: return
        val db = RommDatabaseHelper(requireContext())
        val games = adapter.games.toList()

        lifecycleScope.launch(Dispatchers.IO) {
            for (game in games) {
                val files = game.optJSONArray("files") ?: continue
                val file = files.optJSONObject(0) ?: continue
                val fileName = file.optString("file_name")
                val platformSlug = game.optString("platform_fs_slug")
                val esDeFolder = esDeFolderMap[platformSlug] ?: platformSlug

                if (skipExisting && !downloadDir.startsWith("content://")) {
                    val fileObj = File("$downloadDir/$esDeFolder/$fileName")
                    if (fileObj.exists() || db.isDownloaded(platformSlug, fileName)) continue
                } else if (skipExisting && db.isDownloaded(platformSlug, fileName)) continue

                val romId = game.optInt("id")
                val fsName = file.optString("fs_name").ifBlank { file.optString("file_name") }
                val quotedFsName = Uri.encode(fsName)
                var downloadUrl: String? = null
                try {
                    Log.d("URL_DEBUG", "host=$host, port=$port, user=$user, pass=$pass")
                    val hostInput = host.trim()
                    val uri = Uri.parse(if (hostInput.startsWith("http")) hostInput else "http://$hostInput")

                    val scheme = uri.scheme ?: "http"
                    val hostOnly = uri.host ?: throw IllegalArgumentException("Invalid host")
                    val portNumber = port?.trim()?.toIntOrNull()

                    val builder = okhttp3.HttpUrl.Builder()
                        .scheme(scheme)
                        .host(hostOnly)
                    if (portNumber != null) {
                        builder.port(portNumber)
                    }
                    builder.addPathSegment("api")
                    builder.addPathSegment("roms")
                    builder.addPathSegment(romId.toString())
                    builder.addPathSegment("content")
                    builder.addPathSegment(quotedFsName)
                    builder.addQueryParameter("hidden_folder", "true")
                    val httpUrl = builder.build()
                    downloadUrl = httpUrl.toString()
                    Log.d("URL_DEBUG", "Built URL: $downloadUrl")
                } catch (e: Exception) {
                    Log.e("URL_DEBUG", "Failed to build download URL", e)
                    continue
                }

                val intent = Intent(requireContext(), DownloadService::class.java).apply {
                    putExtra("file_url", downloadUrl)
                    putExtra("file_name", fileName)
                    putExtra("platform_slug", platformSlug)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
            }
        }
    }
}
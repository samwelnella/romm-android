package com.rommclient.android


import androidx.activity.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import android.view.View
import android.widget.TextView
import android.widget.Button
import android.view.Menu
import android.view.MenuItem

import android.app.ProgressDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

import com.rommclient.android.RommDatabaseHelper
import com.rommclient.android.DownloadProgressTracker

import com.rommclient.android.GameAdapter

// Map RomM platform slugs to ES-DE folder names
private val esDeFolderMap = mapOf(
    "3do" to "3do",
    "amiga" to "amiga",
    "arcade" to "arcade",
    "atari2600" to "atari2600",
    "atari5200" to "atari5200",
    "atari7800" to "atari7800",
    "atari800" to "atari800",
    "atarijaguar" to "atarijaguar",
    "atarilynx" to "atarilynx",
    "colecovision" to "colecovision",
    "dreamcast" to "dreamcast",
    "fds" to "fds",
    "gb" to "gb",
    "gba" to "gba",
    "gbc" to "gbc",
    "gc" to "gc",
    "genesis" to "genesis",
    "gg" to "gamegear",
    "intellivision" to "intellivision",
    "macintosh" to "macintosh",
    "mastersystem" to "mastersystem",
    "megadrive" to "megadrive",
    "msx" to "msx",
    "n64" to "n64",
    "nds" to "nds",
    "neogeo" to "neogeo",
    "nes" to "nes",
    "ngp" to "ngp",
    "ngpc" to "ngpc",
    "pc" to "pc",
    "pcengine" to "pcengine",
    "ps1" to "ps1",
    "ps2" to "ps2",
    "ps3" to "ps3",
    "psp" to "psp",
    "saturn" to "saturn",
    "scummvm" to "scummvm",
    "sega32x" to "sega32x",
    "segacd" to "segacd",
    "sg1000" to "sg-1000",
    "snes" to "snes",
    "switch" to "switch",
    "tic80" to "tic80",
    "trs80" to "trs-80",
    "vectrex" to "vectrex",
    "vic20" to "vic20",
    "virtualboy" to "virtualboy",
    "wii" to "wii",
    "wiiu" to "wiiu",
    "wswan" to "wonderswan",
    "wswancolor" to "wonderswancolor",
    "xbox" to "xbox",
    "xbox360" to "xbox360",
    "zxspectrum" to "zxspectrum"
)

class GameListActivity : AppCompatActivity() {
    private val client = OkHttpClient()

    // ActivityResultLauncher for folder selection
    private val selectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            // Persist permission
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // Save the URI string as the download directory
            val prefs = getSharedPreferences("romm_prefs", MODE_PRIVATE)
            prefs.edit().putString("download_directory", uri.toString()).apply()
            Toast.makeText(this, "Download directory set!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModel: GameListViewModel by viewModels()
        setContentView(R.layout.activity_game_list)

        // Set up the toolbar and set the title dynamically.
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)

        val platformName = intent.extras?.getString("PLATFORM_NAME")
        val collectionName = intent.extras?.getString("COLLECTION_NAME")
        val name = when {
            !collectionName.isNullOrBlank() -> collectionName
            !platformName.isNullOrBlank() -> platformName
            else -> "RomM Platforms"
        }
        supportActionBar?.title = name

        // Check and prompt for download directory if not already set
        val prefs = getSharedPreferences("romm_prefs", MODE_PRIVATE)
        val downloadDir = prefs.getString("download_directory", null)
        if (downloadDir == null) {
            Toast.makeText(this, "Please set your download directory in Settings first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Remove intent credential reading here; handle inside coroutine below

        val recyclerView = findViewById<RecyclerView>(R.id.game_list_view)
        recyclerView.layoutManager = LinearLayoutManager(this@GameListActivity)


        // Show loading dialog using LoadingDialogFragment
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                LoadingDialogFragment.show(supportFragmentManager)
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Revert to using intent extras for credentials as previously
            val host = intent.getStringExtra("HOST") ?: ""
            val port = intent.getStringExtra("PORT") ?: ""
            val user = intent.getStringExtra("USER") ?: ""
            val pass = intent.getStringExtra("PASS") ?: ""
            // Log credentials (user and pass length only) for debug
            Log.d("GameListActivity", "Credentials user=$user, pass=${"*".repeat(pass.length)}")
            Log.d("GameListActivity", "Using host=$host, port=$port, user=$user")
            try {
                // Log raw extras for debugging
                Log.d(
                    "GameListActivity",
                    "Raw extras: PLATFORM_ID=${intent.extras?.get("PLATFORM_ID")}, COLLECTION_ID=${intent.extras?.get("COLLECTION_ID")}"
                )
                val resolvedCollectionId = intent.extras?.get("COLLECTION_ID")?.toString()?.toIntOrNull()
                val resolvedPlatformId = intent.extras?.get("PLATFORM_ID")?.toString()?.toIntOrNull()
                Log.d("GameListActivity", "Resolved IDs: PLATFORM_ID=$resolvedPlatformId, COLLECTION_ID=$resolvedCollectionId")

                val roms = mutableListOf<org.json.JSONObject>()
                var offset = 0
                val limit = 100
                var totalCount = -1

                while (true) {
                    val url = when {
                        resolvedCollectionId != null -> {
                            Log.d("GameListActivity", "Using collection ID $resolvedCollectionId to fetch ROMs")
                            "http://$host:$port/api/roms?collection_id=$resolvedCollectionId&limit=$limit&offset=$offset&expand=collection"
                        }
                        resolvedPlatformId != null -> {
                            Log.d("GameListActivity", "Using platform ID $resolvedPlatformId to fetch ROMs")
                            "http://$host:$port/api/roms?platform_id=$resolvedPlatformId&limit=$limit&offset=$offset&expand=platform"
                        }
                        else -> {
                            Log.e("GameListActivity", "No valid platform or collection ID found in intent extras.")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@GameListActivity, "Invalid platform or collection ID", Toast.LENGTH_LONG).show()
                            }
                            return@launch
                        }
                    }

                    Log.d("GameListActivity", "Fetching offset $offset from: $url")
                    val credential = Credentials.basic(user, pass)
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", credential)
                        .addHeader("Accept", "application/json")
                        .build()

                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            val errorMsg = "Failed to load games: ${response.code} - ${response.message}"
                            Toast.makeText(this@GameListActivity, errorMsg, Toast.LENGTH_LONG).show()
                            Log.e("GameListActivity", errorMsg)
                        }
                        return@launch
                    }

                    val body = response.body?.string()
                    if (body.isNullOrEmpty()) break

                    val root = org.json.JSONObject(body)
                    // Update the activity title based on returned platform or collection name, only for the first page
                    if (offset == 0) {
                        val newTitle = when {
                            root.has("collection") -> root.getJSONObject("collection").optString("name", null)
                            root.has("platform") -> root.getJSONObject("platform").optString("name", null)
                            else -> null
                        }
                        if (!newTitle.isNullOrBlank()) {
                            withContext(Dispatchers.Main) {
                                supportActionBar?.title = newTitle
                            }
                        }
                    }
                    if (totalCount == -1) {
                        totalCount = root.optInt("total", -1)
                    }
                    val items = root.optJSONArray("items") ?: JSONArray()
                    if (items.length() == 0) {
                        Log.w("GameListActivity", "No ROMs returned at offset $offset")
                        break
                    }

                    for (i in 0 until items.length()) {
                        roms.add(items.getJSONObject(i))
                    }
                    Log.d("GameListActivity", "Fetched ${items.length()} items for offset $offset")
                    if (totalCount > 0 && roms.size >= totalCount) break

                    withContext(Dispatchers.Main) {
                        val totalSoFar = roms.size
                        val percent = if (totalCount > 0) (totalSoFar * 100 / totalCount).coerceAtMost(100) else 100
                        LoadingDialogFragment.updateMessage(supportFragmentManager, "Loading games... $percent%")
                    }

                    offset += items.length()
                }

                withContext(Dispatchers.Main) {
                    val adapter = GameAdapter(this@GameListActivity, roms, object : GameAdapter.GameClickListener {
                        override fun onDownloadClick(game: org.json.JSONObject) {
                            val prefs = getSharedPreferences("romm_prefs", MODE_PRIVATE)
                            val downloadDir = prefs.getString("download_directory", null)
                            if (downloadDir == null) {
                                Toast.makeText(this@GameListActivity, "Download directory not set in settings.", Toast.LENGTH_LONG).show()
                                return
                            }

                            val files = game.optJSONArray("files")
                            if (files == null || files.length() == 0) {
                                Toast.makeText(this@GameListActivity, "No files available for download.", Toast.LENGTH_LONG).show()
                                return
                            }

                            val file = files.getJSONObject(0)
                            val fileName = file.optString("file_name")
                            val platformSlug = game.optString("platform_fs_slug")
                            val esDeFolder = esDeFolderMap[platformSlug] ?: platformSlug
                            val romId = game.optInt("id")
                            var fsName = file.optString("fs_name")
                            if (fsName.isNullOrBlank()) {
                                fsName = file.optString("file_name")
                            }
                            Log.d("ResolvedFsName", "Using fsName: $fsName")
                            val quotedFsName = Uri.encode(fsName)
                            Log.d("DownloadURL", "http://$host:$port/api/roms/$romId/content/$quotedFsName?hidden_folder=true")
                            val downloadUrl = "http://$host:$port/api/roms/$romId/content/$quotedFsName?hidden_folder=true"

                            // If downloadDir is a URI string (from SAF), write using content resolver
                            if (downloadDir.startsWith("content://")) {
                                val dirUri = Uri.parse(downloadDir)
                                val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@GameListActivity, dirUri)
                            // Create ES-DE platform folder if not exists
                            val platformDir = docFile?.findFile(esDeFolder)
                                ?: docFile?.createDirectory(esDeFolder)
                                if (platformDir == null) {
                                    Toast.makeText(this@GameListActivity, "Unable to access/create platform folder.", Toast.LENGTH_LONG).show()
                                    return
                                }
                                // Create file
                                val outFile = platformDir.findFile(fileName)
                                    ?: platformDir.createFile("*/*", fileName)
                                if (outFile == null) {
                                    Toast.makeText(this@GameListActivity, "Unable to create output file.", Toast.LENGTH_LONG).show()
                                    return
                                }

                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val request = Request.Builder().url(downloadUrl).header("Authorization", Credentials.basic(user, pass)).build()
                                val response = client.newCall(request).execute()
                                if (!response.isSuccessful) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@GameListActivity, "Download failed: ${response.code}", Toast.LENGTH_LONG).show()
                                    }
                                    return@launch
                                }
                                val input = response.body?.byteStream()
                                val output = contentResolver.openOutputStream(outFile.uri)
                                if (input != null && output != null) {
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    var totalRead = 0L
                                    val contentLength = response.body?.contentLength() ?: -1L
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                        totalRead += bytesRead
                                        if (contentLength > 0) {
                                                val percent = (totalRead * 100 / contentLength).toInt()
                                                DownloadProgressTracker.updateProgress(fileName, percent, platformSlug, this@GameListActivity)
                                        }
                                    }
                                    input.close()
                                    output.close()
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@GameListActivity, "Error accessing file stream.", Toast.LENGTH_LONG).show()
                                    }
                                    return@launch
                                }
                                // Insert download record into DB
                                val db = RommDatabaseHelper(this@GameListActivity)
                                db.insertDownloadsBatch(listOf(platformSlug to fileName))
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@GameListActivity, "Downloaded to $platformSlug/$fileName", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@GameListActivity, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                            } else {
                            // Assume normal file path, using ES-DE folder
                            val outputPath = "$downloadDir/$esDeFolder/$fileName"
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val request = Request.Builder().url(downloadUrl).header("Authorization", Credentials.basic(user, pass)).build()
                                val response = client.newCall(request).execute()
                                if (!response.isSuccessful) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@GameListActivity, "Download failed: ${response.code}", Toast.LENGTH_LONG).show()
                                    }
                                    return@launch
                                }
                                val input = response.body?.byteStream()
                                val outputFile = java.io.File(outputPath)
                                outputFile.parentFile?.mkdirs()
                                val output = outputFile.outputStream()
                                if (input != null) {
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    var totalRead = 0L
                                    val contentLength = response.body?.contentLength() ?: -1L
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                        totalRead += bytesRead
                                        if (contentLength > 0) {
                                                val percent = (totalRead * 100 / contentLength).toInt()
                                                DownloadProgressTracker.updateProgress(fileName, percent, platformSlug, this@GameListActivity)
                                        }
                                    }
                                    input.close()
                                    output.close()
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@GameListActivity, "Error accessing file stream.", Toast.LENGTH_LONG).show()
                                    }
                                    return@launch
                                }
                                // Insert download record into DB
                                val db = RommDatabaseHelper(this@GameListActivity)
                                db.insertDownloadsBatch(listOf(platformSlug to fileName))
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@GameListActivity, "Downloaded to $outputPath", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@GameListActivity, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                            }
                        }
                    }, this@GameListActivity)
                    recyclerView.adapter = adapter
                }
                // === Begin WorkManager progress observer example ===
                // Example: create and enqueue work request, then observe progress
                // You may want to move this logic to where you actually want to trigger the worker!
                val workRequest = androidx.work.OneTimeWorkRequestBuilder<DownloadGameWorker>()
                    .setInputData(androidx.work.workDataOf("apiUrl" to "http://your.api.url")) // adjust input as needed
                    .build()

                androidx.work.WorkManager.getInstance(this@GameListActivity).enqueue(workRequest)

                withContext(Dispatchers.Main) {
                    androidx.work.WorkManager.getInstance(this@GameListActivity).getWorkInfoByIdLiveData(workRequest.id)
                        .observe(this@GameListActivity) { workInfo ->
                            if (workInfo != null && workInfo.state == androidx.work.WorkInfo.State.RUNNING) {
                                val progress = workInfo.progress.getInt("progress", 0)
                                android.util.Log.d("WorkProgress", "Download progress: $progress%")
                                // Update progress emoji on each visible game item
                                val recyclerView = findViewById<RecyclerView>(R.id.game_list_view)
                                val adapter = recyclerView.adapter as? GameAdapter
                                val fileName = workInfo.progress.getString("fileName")
                                if (!fileName.isNullOrEmpty()) {
                                    adapter?.updateDownloadProgress(fileName, progress)
                                }
                            }
                        }
                }
                // === End WorkManager progress observer example ===
            } finally {
                withContext(Dispatchers.Main) {
                    LoadingDialogFragment.dismiss(supportFragmentManager)
                }
            }
        }
    }

    class GameViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameView: TextView = view.findViewById(R.id.game_name)
        val downloadButton: Button = view.findViewById(R.id.download_button)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.game_list_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_download_all -> {
                downloadAllGamesUnified(false)
                true
            }
            R.id.menu_download_all_skip_existing -> {
                downloadAllGamesUnified(true)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun downloadAllGames(skipExisting: Boolean) {
        val recyclerView = findViewById<RecyclerView>(R.id.game_list_view)
        val adapter = recyclerView.adapter as? GameAdapter ?: return
        val prefs = getSharedPreferences("romm_prefs", MODE_PRIVATE)
        val downloadDir = prefs.getString("download_directory", null)
        if (downloadDir == null) {
            Toast.makeText(this, "Download directory not set in settings.", Toast.LENGTH_LONG).show()
            return
        }

        for (game in adapter.games) {
            val files = game.optJSONArray("files") ?: continue
            val file = files.optJSONObject(0) ?: continue
            val fileName = file.optString("file_name")
            val platformSlug = game.optString("platform_fs_slug")
            val esDeFolder = esDeFolderMap[platformSlug] ?: platformSlug
            if (skipExisting) {
                val fileObj = File("$downloadDir/$esDeFolder/$fileName")
                if (fileObj.exists()) continue
            }
        }
    }

    // Bulk download unified method for all games in the list, optionally skipping existing
    fun downloadAllGamesUnified(skipExisting: Boolean) {
        val recyclerView = findViewById<RecyclerView>(R.id.game_list_view)
        val adapter = recyclerView.adapter as? GameAdapter ?: return
        val prefs = getSharedPreferences("romm_prefs", MODE_PRIVATE)
        val downloadDir = prefs.getString("download_directory", null)
        if (downloadDir == null) {
            Toast.makeText(this, "Download directory not set in settings.", Toast.LENGTH_LONG).show()
            return
        }

        val db = RommDatabaseHelper(this)

        val gamesToDownload = adapter.games.filter { game ->
            val files = game.optJSONArray("files") ?: return@filter false
            val file = files.optJSONObject(0) ?: return@filter false
            val fileName = file.optString("file_name")
            val platformSlug = game.optString("platform_fs_slug")
            val esDeFolder = esDeFolderMap[platformSlug] ?: platformSlug
            val localFile = File("$downloadDir/$esDeFolder/$fileName")
            !(skipExisting && (localFile.exists() || db.isDownloaded(platformSlug, fileName)))
        }

        val progressDialog = ProgressDialog(this@GameListActivity).apply {
            setCancelable(false)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = gamesToDownload.size
            setMessage("Starting bulk download...")
            show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val host = intent.getStringExtra("HOST") ?: ""
            val port = intent.getStringExtra("PORT") ?: ""
            val user = intent.getStringExtra("USER") ?: ""
            val pass = intent.getStringExtra("PASS") ?: ""

            // Collect downloads to batch insert after all downloads
            val downloadsToInsert = mutableListOf<Pair<String, String>>()

            for ((index, game) in gamesToDownload.withIndex()) {
                withContext(Dispatchers.Main) {
                    progressDialog.setMessage("Downloading ${index + 1} of ${gamesToDownload.size}")
                    progressDialog.progress = index
                }

                val files = game.optJSONArray("files")
                val file = files?.optJSONObject(0)
                val fileName = file?.optString("file_name") ?: continue
                var fsName = file.optString("fs_name")
                if (fsName.isNullOrBlank()) fsName = fileName
                val platformSlug = game.optString("platform_fs_slug")
                val esDeFolder = esDeFolderMap[platformSlug] ?: platformSlug
                val romId = game.optInt("id")

                val quotedFsName = Uri.encode(fsName)
                val downloadUrl = "http://$host:$port/api/roms/$romId/content/$quotedFsName?hidden_folder=true"

                // --- Begin new logic for SAF and file paths ---
                var downloadSucceeded = false
                if (downloadDir.startsWith("content://")) {
                    val dirUri = Uri.parse(downloadDir)
                    val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@GameListActivity, dirUri)
                    val platformDir = docFile?.findFile(esDeFolder) ?: docFile?.createDirectory(esDeFolder)
                    val outFile = platformDir?.findFile(fileName) ?: platformDir?.createFile("*/*", fileName)

                    if (outFile == null) {
                        Log.e("BulkDownload", "Unable to create file for $fileName")
                        continue
                    }

                    try {
                        val request = Request.Builder()
                            .url(downloadUrl)
                            .addHeader("Authorization", Credentials.basic(user, pass))
                            .build()
                        val response = client.newCall(request).execute()
                        if (!response.isSuccessful) {
                            Log.e("BulkDownload", "Failed to download $fileName: ${response.message}")
                            continue
                        }
                        response.body?.byteStream()?.use { input ->
                            contentResolver.openOutputStream(outFile.uri)?.use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                var totalRead = 0L
                                val contentLength = response.body?.contentLength() ?: -1L
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    totalRead += bytesRead
                                    if (contentLength > 0) {
                                        val percent = (totalRead * 100 / contentLength).toInt()
                                        DownloadProgressTracker.updateProgress(fileName, percent, platformSlug, this@GameListActivity)
                                    }
                                }
                            } ?: Log.e("BulkDownload", "Could not open output stream for $fileName")
                        }
                        //db.insertDownload(platformSlug, fileName)
                        downloadsToInsert.add(platformSlug to fileName)
                        downloadSucceeded = true
                    } catch (e: Exception) {
                        Log.e("BulkDownload", "Error downloading $fileName", e)
                    }
                    // UI progress update on download succeeded (SAF branch)
                    if (downloadSucceeded) {
                        withContext(Dispatchers.Main) {
                            val recyclerView = findViewById<RecyclerView>(R.id.game_list_view)
                            val adapter = recyclerView.adapter as? GameAdapter
                            adapter?.updateDownloadProgress(fileName, 100)
                        }
                    }
                } else {
                    val outputPath = "$downloadDir/$esDeFolder/$fileName"
                    val outputFile = File(outputPath)
                    outputFile.parentFile?.mkdirs()
                    try {
                        val request = Request.Builder()
                            .url(downloadUrl)
                            .addHeader("Authorization", Credentials.basic(user, pass))
                            .build()
                        val response = client.newCall(request).execute()
                        if (!response.isSuccessful) {
                            Log.e("BulkDownload", "Failed to download $fileName: ${response.message}")
                            continue
                        }
                        response.body?.byteStream()?.use { input ->
                            outputFile.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                var totalRead = 0L
                                val contentLength = response.body?.contentLength() ?: -1L
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    totalRead += bytesRead
                                    if (contentLength > 0) {
                                        val percent = (totalRead * 100 / contentLength).toInt()
                                        DownloadProgressTracker.updateProgress(fileName, percent, platformSlug, this@GameListActivity)
                                    }
                                }
                            }
                        }
                        //db.insertDownload(platformSlug, fileName)
                        downloadsToInsert.add(platformSlug to fileName)
                        downloadSucceeded = true
                    } catch (e: Exception) {
                        Log.e("BulkDownload", "Error downloading $fileName", e)
                    }
                    // UI progress update on download succeeded (non-SAF branch)
                    if (downloadSucceeded) {
                        withContext(Dispatchers.Main) {
                            val recyclerView = findViewById<RecyclerView>(R.id.game_list_view)
                            val adapter = recyclerView.adapter as? GameAdapter
                            adapter?.updateDownloadProgress(fileName, 100)
                        }
                    }
                }
                // --- End new logic for SAF and file paths ---
            }

            // Batch insert all downloads at once
            db.insertDownloadsBatch(downloadsToInsert)

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                Toast.makeText(this@GameListActivity, "Bulk download complete!", Toast.LENGTH_LONG).show()
            }
        }
    }



}
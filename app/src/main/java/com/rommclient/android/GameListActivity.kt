package com.rommclient.android

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import android.view.View
import android.view.Menu
import android.view.MenuItem

import android.app.ProgressDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

import com.rommclient.android.RommDatabaseHelper

import com.rommclient.android.GameAdapter

// Map RomM platform slugs to ES-DE folder names
private val esDeFolderMap = mapOf(
    "3do" to "Panasonic 3DO",
    "amiga" to "Commodore Amiga",
    "arcade" to "Arcade",
    "atari2600" to "Atari 2600",
    "atari5200" to "Atari 5200",
    "atari7800" to "Atari 7800",
    "atari800" to "Atari 800",
    "atarijaguar" to "Atari Jaguar",
    "atarilynx" to "Atari Lynx",
    "colecovision" to "ColecoVision",
    "dreamcast" to "Sega Dreamcast",
    "fds" to "Famicom Disk System",
    "gb" to "Nintendo Game Boy",
    "gba" to "Nintendo Game Boy Advance",
    "gbc" to "Nintendo Game Boy Color",
    "gc" to "Nintendo GameCube",
    "genesis" to "Sega Genesis",
    "gg" to "Sega Game Gear",
    "intellivision" to "Mattel Intellivision",
    "macintosh" to "Apple Macintosh",
    "mastersystem" to "Sega Master System",
    "megadrive" to "Sega Mega Drive",
    "msx" to "MSX",
    "n64" to "Nintendo 64",
    "nds" to "Nintendo DS",
    "neogeo" to "Neo Geo",
    "nes" to "Nintendo Entertainment System",
    "ngp" to "Neo Geo Pocket",
    "ngpc" to "Neo Geo Pocket Color",
    "pc" to "PC",
    "pcengine" to "PC Engine",
    "ps1" to "Sony PlayStation",
    "ps2" to "Sony PlayStation 2",
    "ps3" to "Sony PlayStation 3",
    "psp" to "Sony PSP",
    "saturn" to "Sega Saturn",
    "scummvm" to "ScummVM",
    "sega32x" to "Sega 32X",
    "segacd" to "Sega CD",
    "sg1000" to "Sega SG-1000",
    "snes" to "Super Nintendo Entertainment System",
    "switch" to "Nintendo Switch",
    "tic80" to "TIC-80",
    "trs80" to "TRS-80",
    "vectrex" to "Vectrex",
    "vic20" to "Commodore VIC-20",
    "virtualboy" to "Nintendo Virtual Boy",
    "wii" to "Nintendo Wii",
    "wiiu" to "Nintendo Wii U",
    "wswan" to "WonderSwan",
    "wswancolor" to "WonderSwan Color",
    "xbox" to "Microsoft Xbox",
    "xbox360" to "Microsoft Xbox 360",
    "zxspectrum" to "ZX Spectrum"
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
        setContentView(R.layout.activity_game_list)

        // Check and prompt for download directory if not already set
        val prefs = getSharedPreferences("romm_prefs", MODE_PRIVATE)
        val downloadDir = prefs.getString("download_directory", null)
        if (downloadDir == null) {
            Toast.makeText(this, "Please select a download directory", Toast.LENGTH_LONG).show()
            selectFolderLauncher.launch(null)
        }

        // Remove intent credential reading here; handle inside coroutine below

        val recyclerView = findViewById<RecyclerView>(R.id.game_list_view)
        recyclerView.layoutManager = LinearLayoutManager(this@GameListActivity)

        // If R.id.btn_set_download_dir is defined in your activity_game_list.xml, uncomment the following lines:
        // val btnSetDownloadDir = findViewById<Button?>(R.id.btn_set_download_dir)
        // btnSetDownloadDir?.setOnClickListener {
        //     // Launch folder picker
        //     selectFolderLauncher.launch(null)
        // }

        val progressDialog = ProgressDialog(this@GameListActivity)
        progressDialog.setMessage("Loading games... 0%")
        progressDialog.setCancelable(false)

        progressDialog.show()

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
                            "http://$host:$port/api/roms?collection_id=$resolvedCollectionId&limit=$limit&offset=$offset"
                        }
                        resolvedPlatformId != null -> {
                            Log.d("GameListActivity", "Using platform ID $resolvedPlatformId to fetch ROMs")
                            "http://$host:$port/api/roms?platform_id=$resolvedPlatformId&limit=$limit&offset=$offset"
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
                        progressDialog.setMessage("Loading games... $percent%")
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

                                val progressDialog = ProgressDialog(this@GameListActivity)
                                progressDialog.setMessage("Downloading $fileName... 0%")
                                progressDialog.setCancelable(false)
                                progressDialog.show()

                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        val request = Request.Builder().url(downloadUrl).header("Authorization", Credentials.basic(user, pass)).build()
                                        val response = client.newCall(request).execute()
                                        if (!response.isSuccessful) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@GameListActivity, "Download failed: ${response.code}", Toast.LENGTH_LONG).show()
                                                progressDialog.dismiss()
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
                                                    withContext(Dispatchers.Main) {
                                                        progressDialog.setMessage("Downloading $fileName... $percent%")
                                                    }
                                                }
                                            }
                                            input.close()
                                            output.close()
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@GameListActivity, "Error accessing file stream.", Toast.LENGTH_LONG).show()
                                                progressDialog.dismiss()
                                            }
                                            return@launch
                                        }
                                    // Insert download record into DB
                                    val db = RommDatabaseHelper(this@GameListActivity)
                                    db.insertDownload(platformSlug, fileName)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@GameListActivity, "Downloaded to $platformSlug/$fileName", Toast.LENGTH_LONG).show()
                                        progressDialog.dismiss()
                                    }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@GameListActivity, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
                                            progressDialog.dismiss()
                                        }
                                    }
                                }
                            } else {
                            // Assume normal file path, using ES-DE folder
                            val outputPath = "$downloadDir/$esDeFolder/$fileName"
                                val progressDialog = ProgressDialog(this@GameListActivity)
                                progressDialog.setMessage("Downloading $fileName... 0%")
                                progressDialog.setCancelable(false)
                                progressDialog.show()

                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        val request = Request.Builder().url(downloadUrl).header("Authorization", Credentials.basic(user, pass)).build()
                                        val response = client.newCall(request).execute()
                                        if (!response.isSuccessful) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@GameListActivity, "Download failed: ${response.code}", Toast.LENGTH_LONG).show()
                                                progressDialog.dismiss()
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
                                                    withContext(Dispatchers.Main) {
                                                        progressDialog.setMessage("Downloading $fileName... $percent%")
                                                    }
                                                }
                                            }
                                            input.close()
                                            output.close()
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@GameListActivity, "Error accessing file stream.", Toast.LENGTH_LONG).show()
                                                progressDialog.dismiss()
                                            }
                                            return@launch
                                        }
                                    // Insert download record into DB
                                    val db = RommDatabaseHelper(this@GameListActivity)
                                    db.insertDownload(platformSlug, fileName)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@GameListActivity, "Downloaded to $outputPath", Toast.LENGTH_LONG).show()
                                        progressDialog.dismiss()
                                    }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@GameListActivity, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
                                            progressDialog.dismiss()
                                        }
                                    }
                                }
                            }
                        }
                    })
                    recyclerView.adapter = adapter
                }
            } finally {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
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
                                input.copyTo(output)
                            } ?: Log.e("BulkDownload", "Could not open output stream for $fileName")
                        }
                        db.insertDownload(platformSlug, fileName)
                    } catch (e: Exception) {
                        Log.e("BulkDownload", "Error downloading $fileName", e)
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
                                input.copyTo(output)
                            }
                        }
                        db.insertDownload(platformSlug, fileName)
                    } catch (e: Exception) {
                        Log.e("BulkDownload", "Error downloading $fileName", e)
                    }
                }
                // --- End new logic for SAF and file paths ---
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                Toast.makeText(this@GameListActivity, "Bulk download complete!", Toast.LENGTH_LONG).show()
            }
        }
    }



}
package com.rommclient.android
import com.rommclient.android.RommDatabaseHelper
import android.net.Uri
import android.content.Context
import com.rommclient.android.RommApp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
@OptIn(kotlin.ExperimentalStdlibApi::class)
object DownloadManager {

    // Tracks the max number of lines ever displayed in the snackbar until queue is empty
    private var maxDisplayedLines = 1

    data class DownloadItem(
        val fileName: String,
        val platformSlug: String,
        val url: String,
        val outputUri: Uri?,          // for SAF (used only if already resolved)
        val outputFile: File?,        // for normal FS
        val isSingleDownload: Boolean = false,
        val platformDirUri: Uri? = null,  // for deferred SAF resolution
        val useDocumentFile: Boolean = false
    )

    private val maxConcurrent = MutableStateFlow(3)  // adjustable later

    private val activeDownloads = mutableListOf<DownloadItem>()
    private val queuedDownloads = mutableListOf<DownloadItem>()
    private val completedDownloads = mutableListOf<String>()
    private val _downloadProgressFlow = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgressFlow: StateFlow<Map<String, Int>> = _downloadProgressFlow

    private val _snackbarFlow = MutableStateFlow("")
    val snackbarFlow: StateFlow<String> = _snackbarFlow

    private val _state = MutableStateFlow<List<DownloadItem>>(emptyList())
    val state: StateFlow<List<DownloadItem>> = _state

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun setMaxConcurrent(limit: Int) {
        maxConcurrent.value = limit.coerceIn(1, 3)
    }

    fun enqueue(item: DownloadItem) {
        synchronized(this) {
            completedDownloads.clear()
            queuedDownloads.add(item)
        }
        updateSnackbarMessage()
        processQueue()
    }

    fun enqueueAll(items: List<DownloadItem>) {
        coroutineScope.launch(Dispatchers.Default) {
            synchronized(this@DownloadManager) {
                completedDownloads.clear()
                queuedDownloads.addAll(items)
            }
            withContext(Dispatchers.Main.immediate) {
                updateSnackbarMessage()
            }
            processQueue()
        }
    }

    fun getProgress(fileName: String): Int {
        return _downloadProgressFlow.value[fileName] ?: 0
    }

    private fun processQueue() {
        coroutineScope.launch {
            while (true) {
                var next: DownloadItem?
                synchronized(this@DownloadManager) {
                    if (activeDownloads.size >= maxConcurrent.value || queuedDownloads.isEmpty()) return@launch
                    val candidate = queuedDownloads.removeAt(0)
                    if (candidate != null) {
                        activeDownloads.add(candidate)
                    } else {
                        println("Warning: Null download item encountered in queue")
                        return@launch
                    }
                    next = candidate
                }
                startDownload(next!!)
            }
        }
    }

    private suspend fun startDownload(item: DownloadItem) {
        withContext(Dispatchers.IO) {
            // Insert null check for item
            if (item == null) {
                println("Warning: Tried to download a null item")
                return@withContext
            }
            try {
                val request = Request.Builder().url(item.url).build()
                val client = OkHttpClient()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")

                    val body = response.body ?: throw IOException("Empty body")

                    val contentLength = body.contentLength()
                    val inputStream = body.byteStream()

                    val context: Context = RommApp.instance.applicationContext
                    val outputStream = when {
                        item.outputFile != null -> FileOutputStream(item.outputFile)
                        item.outputUri != null -> {
                            context.contentResolver.openOutputStream(item.outputUri)
                                ?: throw IOException("Unable to open outputUri")
                        }
                        item.useDocumentFile && item.platformDirUri != null -> {
                            val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, item.platformDirUri)
                            // Ensure platform directory exists inside SAF tree
                            val platformDir = dir?.findFile(item.platformSlug)
                                ?: dir?.createDirectory(item.platformSlug)
                            if (platformDir == null) throw IOException("Unable to create or find platform directory")

                            val outFile = platformDir.findFile(item.fileName) ?: platformDir.createFile("*/*", item.fileName)
                            if (outFile == null) throw IOException("Unable to create SAF file")
                            context.contentResolver.openOutputStream(outFile.uri)
                                ?: throw IOException("Unable to open created SAF outputUri")
                        }
                        else -> throw IOException("No output destination")
                    }

                    outputStream.use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesCopied: Long = 0
                        var lastPercent = -1

                        while (true) {
                            val read = inputStream.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            bytesCopied += read

                            if (contentLength > 0) {
                                val percent = (bytesCopied * 100 / contentLength).toInt()
                                if (percent != lastPercent) {
                                    _downloadProgressFlow.value = _downloadProgressFlow.value.toMutableMap().apply {
                                        put(item.fileName, percent)
                                    }
                                    updateSnackbarMessage()
                                    lastPercent = percent
                                }
                            }
                        }
                    }

                    synchronized(this@DownloadManager) {
                        activeDownloads.remove(item)
                        completedDownloads.add(item.fileName)
                    }
                    val db = RommDatabaseHelper(RommApp.instance.applicationContext)
                    db.insertDownloadsBatch(listOf(Pair(item.platformSlug, item.fileName)))
                    android.util.Log.d("DownloadManager", "Inserted into DB: ${item.platformSlug}, ${item.fileName}")
                    _state.value = activeDownloads.toList()
                    updateSnackbarMessage()
                    processQueue()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                synchronized(this@DownloadManager) {
                    activeDownloads.remove(item)
                }
                _state.value = activeDownloads.toList()
                updateSnackbarMessage()
                processQueue()
            }
        }
    }

    private fun updateSnackbarMessage() {
        // Show the 3 oldest active downloads (in insertion order)
        val active = activeDownloads.toList().take(3)
        val progressMap = _downloadProgressFlow.value

        val messageLines = mutableListOf<String>()
        val total = completedDownloads.size + activeDownloads.size + queuedDownloads.size
        val inProgress = activeDownloads.size + queuedDownloads.size
        val completed = total - inProgress
        messageLines += "Downloading ${completed + 1} of $total"
        active.forEach { item ->
            if (item != null) {
                val percent = progressMap[item.fileName] ?: 0
                messageLines += "- ${item.fileName} (${item.platformSlug}): $percent%"
            } else {
                messageLines += "- [null item encountered]"
            }
        }

        // Only reset maxDisplayedLines if all queues are empty
        if (activeDownloads.isEmpty() && queuedDownloads.isEmpty()) {
            maxDisplayedLines = 1
            _snackbarFlow.value = ""
            return
        }

        if (messageLines.size > maxDisplayedLines) {
            maxDisplayedLines = messageLines.size
        }

        // Pad with blank lines if needed, but never shrink unless queue is empty
        while (messageLines.size < maxDisplayedLines) {
            messageLines += " "
        }

        _snackbarFlow.value = messageLines.joinToString("\n")
    }

    fun enqueue(
        platformSlug: String,
        fileName: String,
        url: String,
        outputUri: Uri? = null,
        outputFile: File? = null
    ) {
        val item = DownloadItem(
            fileName = fileName,
            platformSlug = platformSlug,
            url = url,
            outputUri = outputUri,
            outputFile = outputFile
        )
        enqueue(item)
    }
}
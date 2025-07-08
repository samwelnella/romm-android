package com.rommclient.android

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

import com.rommclient.android.DownloadManager.DownloadItem

class DownloadGameWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        Log.d("DownloadGameWorker", "doWork() invoked")
        val url = inputData.getString("url") ?: return Result.failure()
        val fileName = inputData.getString("file_name") ?: return Result.failure()
        val platformSlug = inputData.getString("platform_slug") ?: return Result.failure()
        val outputUriStr = inputData.getString("output_uri")
        val platformDirUriStr = inputData.getString("platform_dir_uri")
        val useDocumentFile = inputData.getBoolean("use_document_file", false)

        val outputUri = outputUriStr?.let { android.net.Uri.parse(it) }
        val platformDirUri = platformDirUriStr?.let { android.net.Uri.parse(it) }

        val item = DownloadItem(
            fileName = fileName,
            platformSlug = platformSlug,
            url = url,
            outputUri = outputUri,
            outputFile = null,
            isSingleDownload = false,
            platformDirUri = platformDirUri,
            useDocumentFile = useDocumentFile
        )
        Log.d(
            "DownloadGameWorker",
            "Starting download with values: fileName=$fileName, platformSlug=$platformSlug, url=$url, outputUri=$outputUri, platformDirUri=$platformDirUri, useDocumentFile=$useDocumentFile"
        )

        return try {
            val client = OkHttpClient()
            val request = Request.Builder().url(item.url).build()
            Log.d("DownloadGameWorker", "Making network request to URL: ${item.url}")
            val response = client.newCall(request).execute()
            Log.d("DownloadGameWorker", "Network response received: code=${response.code}, success=${response.isSuccessful}")

            if (!response.isSuccessful || response.body == null) {
                Log.e("DownloadGameWorker", "Download failed: ${response.code}")
                return Result.failure()
            }

            Log.d("DownloadGameWorker", "Download response received, writing to destination...")
            val inputStream = response.body!!.byteStream()
            val outputStream = when {
                item.outputFile != null -> {
                    Log.d("DownloadGameWorker", "Using outputFile: ${item.outputFile.absolutePath}")
                    FileOutputStream(item.outputFile)
                }
                item.outputUri != null -> {
                    Log.d("DownloadGameWorker", "Attempting to write to outputUri: ${item.outputUri}")
                    applicationContext.contentResolver.openOutputStream(item.outputUri).also {
                        if (it == null) {
                            Log.e("DownloadGameWorker", "Failed to open outputUri: ${item.outputUri}")
                        }
                    } ?: return Result.failure()
                }
                item.useDocumentFile && item.platformDirUri != null -> {
                    Log.d("DownloadGameWorker", "Resolving DocumentFile from platformDirUri: ${item.platformDirUri}")
                    val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(applicationContext, item.platformDirUri)
                    if (dir == null) {
                        Log.e("DownloadGameWorker", "Unable to resolve platformDirUri: ${item.platformDirUri}")
                        return Result.failure()
                    }

                    val outFile = dir.findFile(item.fileName) ?: dir.createFile("application/octet-stream", item.fileName)
                    if (outFile == null) {
                        Log.e("DownloadGameWorker", "Failed to create SAF file: ${item.fileName}")
                        return Result.failure()
                    }

                    Log.d("DownloadGameWorker", "SAF output file resolved: ${outFile.name}, uri: ${outFile.uri}")

                    if (applicationContext.contentResolver.getType(outFile.uri) == null) {
                        Log.w("DownloadGameWorker", "SAF uri type is null for ${outFile.uri}")
                    }
                    applicationContext.contentResolver.openOutputStream(outFile.uri).also {
                        if (it == null) {
                            Log.e("DownloadGameWorker", "Failed to open created SAF output stream: ${outFile.uri}")
                        }
                    } ?: return Result.failure()
                }
                else -> {
                    Log.e(
                        "DownloadGameWorker",
                        "No valid output destination. useDocumentFile=${item.useDocumentFile}, platformDirUri=${item.platformDirUri}, outputUri=${item.outputUri}, outputFile=${item.outputFile}"
                    )
                    return Result.failure()
                }
            }

            inputStream.use { input ->
                Log.d("DownloadGameWorker", "Writing file to destination...")
                outputStream.use { output ->
                    input.copyTo(output)
                    Log.d("DownloadGameWorker", "Finished writing file: $fileName")
                }
            }

            Log.d("DownloadGameWorker", "Download completed and written to: ${item.outputUri ?: item.outputFile ?: item.platformDirUri}")
            Log.d("DownloadGameWorker", "Returning Result.success() from doWork()")
            Result.success()
        } catch (e: Exception) {
            Log.e("DownloadGameWorker", "Exception occurred during download of $fileName", e)
            Log.e("DownloadGameWorker", "Download error: ${e.message}", e)
            Result.failure()
        }
    }
}
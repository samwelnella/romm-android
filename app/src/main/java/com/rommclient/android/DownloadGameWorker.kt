package com.rommclient.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class DownloadGameWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val apiUrl = inputData.getString("apiUrl") ?: return Result.failure()
        return try {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val response = connection.inputStream.bufferedReader().use { it.readText() }

            val jsonArray = JSONArray(response)
            val gameList = mutableListOf<Game>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                gameList.add(
                    Game(
                        id = obj.getInt("id"),
                        name = obj.getString("name"),
                        fileName = obj.getString("fileName")
                    )
                )
                val progress = ((i + 1) * 100) / jsonArray.length()
                setProgress(androidx.work.workDataOf("progress" to progress))
            }

            val jsonGames = gameList.map {
                org.json.JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("fileName", it.fileName)
                }
            }

            val intent = android.content.Intent("com.rommclient.android.GAMES_DOWNLOADED")
            intent.putExtra("games", org.json.JSONArray(jsonGames).toString())
            applicationContext.sendBroadcast(intent)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}
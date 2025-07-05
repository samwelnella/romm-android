package com.rommclient.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONObject
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class GameListViewModel(application: Application) : AndroidViewModel(application) {
    private val _games = MutableLiveData<List<JSONObject>>()
    val games: LiveData<List<JSONObject>> get() = _games

    // BroadcastReceiver to listen for downloaded games
    private val gameReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action == "com.rommclient.android.GAMES_DOWNLOADED") {
                val jsonString = intent.getStringExtra("games") ?: return
                val jsonArray = org.json.JSONArray(jsonString)
                val gameList = mutableListOf<JSONObject>()
                for (i in 0 until jsonArray.length()) {
                    gameList.add(jsonArray.getJSONObject(i))
                }
                postGames(gameList)
            }
        }
    }

    init {
        val filter = android.content.IntentFilter("com.rommclient.android.GAMES_DOWNLOADED")
        getApplication<Application>().registerReceiver(gameReceiver, filter)
    }

    fun postGames(list: List<JSONObject>) {
        _games.postValue(list)
    }

    fun fetchGames(apiUrl: String) {
        val context = getApplication<Application>().applicationContext
        val inputData = workDataOf("apiUrl" to apiUrl)
        val request = OneTimeWorkRequestBuilder<DownloadGameWorker>()
            .setInputData(inputData)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}

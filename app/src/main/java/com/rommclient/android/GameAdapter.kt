package com.rommclient.android

import com.google.android.material.progressindicator.CircularProgressIndicator

import androidx.lifecycle.LiveData
import com.rommclient.android.DownloadProgressTracker

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject


import com.rommclient.android.DownloadStatus.downloadStates
//import com.rommclient.android.DownloadStatus.DownloadState

// Singleton object for RommDatabaseHelper
object RommDBSingleton {
    lateinit var helper: RommDatabaseHelper

    fun init(context: Context) {
        helper = RommDatabaseHelper(context.applicationContext)
    }
}


class GameAdapter(
    private val context: Context,
    val games: List<JSONObject>,
    private val listener: GameClickListener,
    private val lifecycleOwner: LifecycleOwner
) : RecyclerView.Adapter<GameAdapter.GameViewHolder>() {

    private val itemProgress = mutableMapOf<String, Int>()
    init {
        RommDBSingleton.init(context)
    }

    interface GameClickListener {
        fun onDownloadClick(game: JSONObject)
    }

    inner class GameViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameView: TextView = view.findViewById(R.id.game_name)
        val downloadButton: ImageButton = view.findViewById(R.id.download_button)
        val progressIndicator: CircularProgressIndicator = view.findViewById(R.id.progress_indicator)

        fun updateDownloadStatus(platformSlug: String, fileName: String) {
            val alreadyDownloaded = RommDBSingleton.helper.isDownloaded(platformSlug, fileName)
            val percent = itemProgress[fileName] ?: 0
            val isCompleted = percent >= 100 || alreadyDownloaded
            val state = downloadStates[fileName] ?: if (isCompleted) DownloadState.COMPLETED else DownloadState.NOT_STARTED

            val iconRes = when {
                isCompleted -> android.R.drawable.checkbox_on_background
                state == DownloadState.QUEUED -> android.R.drawable.ic_menu_recent_history
                else -> android.R.drawable.stat_sys_download
            }

            downloadButton.setImageResource(iconRes)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_game, parent, false)
        return GameViewHolder(view)
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        val game = games[position]
        val name = game.optString("name", "Unnamed")

        val regionArray = game.optJSONArray("regions")
        val regionRaw = if (regionArray != null && regionArray.length() > 0) {
            regionArray.getString(0).trim()
        } else {
            ""
        }
        val normalizedRegion = regionRaw.replace("-", "").replace("_", "").uppercase()
        val emoji = when (normalizedRegion) {
            "USA", "US", "NA" -> "🇺🇸"
            "JPN", "JP", "JAPAN" -> "🇯🇵"
            "EUR", "EU", "EUROPE" -> "🇪🇺"
            "KOR", "KR" -> "\uD83C\uDDF0\uD83C\uDDF7"
            "WORLD" -> "\uD83C\uDDFA\uD83C\uDDF3"
            else -> "\u2753"
        }

        val files = game.optJSONArray("files")
        val fileName = files?.optJSONObject(0)?.optString("file_name") ?: ""
        val platformSlug = game.optString("platform_fs_slug")
        holder.nameView.text = "$name $emoji"
        // Observe download progress for this row
        val progressLiveData: LiveData<Int> = DownloadProgressTracker.getProgressLiveData(fileName)
        progressLiveData.observe(lifecycleOwner) { percent ->
            val alreadyDownloaded = RommDBSingleton.helper.isDownloaded(platformSlug, fileName)
            val isCompleted = percent >= 100 || alreadyDownloaded
            itemProgress[fileName] = percent

            when {
                isCompleted -> {
                    downloadStates[fileName] = DownloadState.COMPLETED
                    holder.downloadButton.visibility = View.VISIBLE
                    holder.progressIndicator.visibility = View.GONE
                    holder.downloadButton.setImageResource(android.R.drawable.checkbox_on_background)
                }
                percent > 0 -> {
                    downloadStates[fileName] = DownloadState.QUEUED
                    holder.downloadButton.visibility = View.GONE
                    holder.progressIndicator.visibility = View.VISIBLE
                    holder.progressIndicator.setProgressCompat(percent, true)
                }
                else -> {
                    downloadStates[fileName] = DownloadState.NOT_STARTED
                    holder.downloadButton.visibility = View.VISIBLE
                    holder.progressIndicator.visibility = View.GONE
                    holder.downloadButton.setImageResource(android.R.drawable.stat_sys_download)
                }
            }
        }
        holder.downloadButton.setOnClickListener {
            listener.onDownloadClick(game)
            holder.updateDownloadStatus(platformSlug, fileName)
        }
        holder.updateDownloadStatus(platformSlug, fileName)

        // Remove redundant text assignment for downloadButton
    }

    override fun getItemCount(): Int = games.size

    fun updateDownloadProgress(fileName: String, percent: Int) {
        val index = games.indexOfFirst { game ->
            val files = game.optJSONArray("files")
            val name = files?.optJSONObject(0)?.optString("file_name") ?: ""
            name == fileName
        }
        if (index != -1) {
            val currentProgress = itemProgress[fileName]
            if (currentProgress != percent) {
                itemProgress[fileName] = percent
                notifyItemChanged(index)
            }
        }
    }

    fun updateDownloadCompleted(fileName: String) {
        updateDownloadProgress(fileName, 100)
    }

    fun updateDownloadFailed(fileName: String) {
        val index = games.indexOfFirst { game ->
            val files = game.optJSONArray("files")
            val name = files?.optJSONObject(0)?.optString("file_name") ?: ""
            name == fileName
        }
        if (index != -1) {
            itemProgress.remove(fileName)
            notifyItemChanged(index)
        }
    }

    fun getListener(): GameClickListener = listener
}

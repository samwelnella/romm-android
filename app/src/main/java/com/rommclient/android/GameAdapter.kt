
package com.rommclient.android

import androidx.lifecycle.LiveData
import com.rommclient.android.DownloadProgressTracker

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

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
        val downloadButton: Button = view.findViewById(R.id.download_button)

        fun updateDownloadStatus(platformSlug: String, fileName: String) {
            val alreadyDownloaded = RommDBSingleton.helper.isDownloaded(platformSlug, fileName)
            android.util.Log.d("GameAdapter", "updateDownloadStatus($platformSlug, $fileName): isDownloaded=$alreadyDownloaded")
            downloadButton.text = if (alreadyDownloaded) "✅" else "⬇️"
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
            "EUR", "EU" -> "🇪🇺"
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
            val currentProgress = itemProgress[fileName]
            if (currentProgress == percent) return@observe

            // Avoid overriding ✅ if already marked as downloaded
            val alreadyDownloaded = RommDBSingleton.helper.isDownloaded(platformSlug, fileName)
            if (currentProgress == null && alreadyDownloaded) {
                return@observe
            }

            itemProgress[fileName] = percent
            holder.downloadButton.text = when {
                percent >= 100 -> "✅"
                percent in 0..99 -> "⬇️ $percent%"
                else -> "⬇️"
            }
        }
        holder.downloadButton.setOnClickListener {
            listener.onDownloadClick(game)
            holder.updateDownloadStatus(platformSlug, fileName)
        }
        holder.updateDownloadStatus(platformSlug, fileName)

        // Fallback: show button state if progress is not tracked yet
        val progress = itemProgress[fileName]
        holder.downloadButton.text = when {
            progress == null -> if (RommDBSingleton.helper.isDownloaded(platformSlug, fileName)) "✅" else "⬇️"
            progress in 0..99 -> "⬇️ $progress%"
            progress >= 100 -> "✅"
            else -> "⬇️"
        }
        // holder.downloadButton.text = if (alreadyDownloaded) "✅" else "⬇️"
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
}

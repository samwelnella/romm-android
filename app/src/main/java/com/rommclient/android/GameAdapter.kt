package com.rommclient.android

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class GameAdapter(
    private val context: Context,
    val games: List<JSONObject>,
    private val listener: GameClickListener
) : RecyclerView.Adapter<GameAdapter.GameViewHolder>() {


    interface GameClickListener {
        fun onDownloadClick(game: JSONObject)
    }

    inner class GameViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameView: TextView = view.findViewById(R.id.game_name)
        val downloadButton: Button = view.findViewById(R.id.download_button)

        fun updateDownloadStatus(platformSlug: String, fileName: String) {
            val db = RommDatabaseHelper(itemView.context)
            val alreadyDownloaded = db.isDownloaded(platformSlug, fileName)
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
        val db = RommDatabaseHelper(context)
        val alreadyDownloaded = db.isDownloaded(platformSlug, fileName)

        holder.nameView.text = "$name $emoji"
        holder.downloadButton.text = if (alreadyDownloaded) "✅" else "⬇️"
        holder.downloadButton.setOnClickListener {
            listener.onDownloadClick(game)
            holder.updateDownloadStatus(platformSlug, fileName)
        }
    }

    override fun getItemCount(): Int = games.size
}

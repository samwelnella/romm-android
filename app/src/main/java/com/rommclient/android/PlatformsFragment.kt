package com.rommclient.android

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class PlatformsFragment : Fragment() {
    private val client = OkHttpClient()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_list, container, false)
        val listView = root.findViewById<ListView>(R.id.listView)

        val prefs = requireContext().getSharedPreferences("RomMConfig", 0)
        val host = prefs.getString("host", null)
        val port = prefs.getString("port", null)
        val user = prefs.getString("user", null)
        val pass = prefs.getString("pass", null)

        if (host.isNullOrBlank() || port.isNullOrBlank() || user.isNullOrBlank() || pass.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing connection info", Toast.LENGTH_LONG).show()
            return root
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url("http://$host:$port/api/platforms")
                    .addHeader("Authorization", Credentials.basic(user, pass))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()
                val platforms = JSONArray(body)

                val items = mutableListOf<Pair<String, Int>>()
                for (i in 0 until platforms.length()) {
                    val platform = platforms.getJSONObject(i)
                    items.add(Pair(platform.getString("display_name"), platform.getInt("id")))
                }

                withContext(Dispatchers.Main) {
                    val names = items.map { it.first }
                    listView.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
                    listView.setOnItemClickListener { _, _, position, _ ->
                        val platformId = items[position].second
                        val intent = Intent(requireContext(), GameListActivity::class.java).apply {
                            putExtra("HOST", host)
                            putExtra("PORT", port)
                            putExtra("USER", user)
                            putExtra("PASS", pass)
                            putExtra("PLATFORM_ID", platformId)
                        }
                        startActivity(intent)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Failed to load platforms: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        return root
    }
}
package com.rommclient.android


import android.util.Log

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import androidx.appcompat.app.AppCompatActivity

class PlatformsFragment : Fragment() {
    private val client = OkHttpClient()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_list, container, false)
        val listView = root.findViewById<ListView>(R.id.listView)

        val context = requireContext()

        val prefs = requireActivity().getSharedPreferences("romm_prefs",AppCompatActivity.MODE_PRIVATE)
        val host = prefs.getString("host", "") ?: ""
        val port = prefs.getString("port", "") ?: ""
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        if (host.isNullOrBlank() || port.isNullOrBlank() || user.isNullOrBlank() || pass.isNullOrBlank()) {
            Toast.makeText(context, "Missing connection info", Toast.LENGTH_LONG).show()
            return root
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("PlatformsFragment", "Fetching platforms from http://$host:$port/api/platforms")
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
                    listView.adapter = ArrayAdapter(
                        context,
                        android.R.layout.simple_list_item_1,
                        names
                    )
                    listView.setOnItemClickListener { _, _, position, _ ->
                        val (platformName, platformId) = items[position]
                        val intent = Intent(context, GameListActivity::class.java).apply {
                            putExtra("HOST", host)
                            putExtra("PORT", port as String?)
                            putExtra("USER", user as String?)
                            putExtra("PASS", pass as String?)
                            putExtra("PLATFORM_ID", platformId)
                            putExtra("PLATFORM_NAME", platformName as String)
                        }
                        startActivity(intent)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Failed to load platforms: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        return root
    }
}
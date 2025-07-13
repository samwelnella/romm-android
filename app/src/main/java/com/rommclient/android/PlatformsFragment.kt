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

        if (host.isBlank()) {
            Toast.makeText(context, "Missing host info", Toast.LENGTH_LONG).show()
            return root
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("PlatformsFragment", "Fetching platforms from $host:$port/api/platforms")
                val portPart = if (port.isNotBlank()) ":$port" else ""
                val baseUrl = "$host$portPart/api/platforms"
                val builder = Request.Builder().url(baseUrl)
                if (user.isNotBlank() && pass.isNotBlank()) {
                    builder.addHeader("Authorization", Credentials.basic(user, pass))
                }
                val request = builder.build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()
                val platforms = JSONArray(body)

                val items = mutableListOf<Triple<String, Int, JSONArray?>>()
                for (i in 0 until platforms.length()) {
                    val platform = platforms.getJSONObject(i)
                    val firmwareArray = platform.optJSONArray("firmware")
                    items.add(Triple(platform.getString("display_name"), platform.getInt("id"), firmwareArray))
                }

                withContext(Dispatchers.Main) {
                    val names = items.map { it.first }
                    listView.adapter = ArrayAdapter(
                        context,
                        android.R.layout.simple_list_item_1,
                        names
                    )
                    listView.setOnItemClickListener { _, _, position, _ ->
                        val (platformName, platformId, firmwareArray) = items[position]
                        (requireActivity() as? MainActivity)?.showGameListFragment(
                            host = host,
                            port = port,
                            user = user,
                            pass = pass,
                            platformId = platformId,
                            collectionId = null,
                            name = platformName,
                            firmwareJson = firmwareArray?.toString()
                        )
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
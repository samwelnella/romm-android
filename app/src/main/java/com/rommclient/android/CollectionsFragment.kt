package com.rommclient.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.fragment.app.Fragment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class CollectionsFragment : Fragment() {
    private val client = OkHttpClient()
    private var collections: JSONArray = JSONArray()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_collections, container, false)
        val listView: ListView = view.findViewById(R.id.collection_list_view)

        val prefs = requireActivity().getSharedPreferences("romm_prefs",AppCompatActivity.MODE_PRIVATE)
        val host = prefs.getString("host", "") ?: ""
        val port = prefs.getString("port", "") ?: ""
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        if (host.isBlank() || port.isBlank() || user.isBlank() || pass.isBlank()) {
            Toast.makeText(requireContext(), "Missing host/port/user/pass", Toast.LENGTH_LONG).show()
            return view
        }
        // removed duplicate client

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "http://$host:$port/api/collections"
                val credential = Credentials.basic(user, pass)
                val request = Request.Builder().url(url).header("Authorization", credential).build()
                val response = client.newCall(request).execute()
                val json = response.body?.string() ?: "[]"
                val array = JSONArray(json)

                val collectionObjects = mutableListOf<JSONObject>()
                val names = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    collectionObjects.add(obj)
                    names.add(obj.getString("name"))
                }

                withContext(Dispatchers.Main) {
                    val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
                    listView.adapter = adapter

                    listView.setOnItemClickListener { _, _, position, _ ->
                        val selected = collectionObjects[position]
                        (requireActivity() as? MainActivity)?.showGameListFragment(
                            host = host,
                            port = port,
                            user = user,
                            pass = pass,
                            platformId = null,
                            collectionId = selected.getInt("id"),
                            name = selected.getString("name")
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error loading collections: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        return view
    }
}

// --- Example: Add this log and file check code to your GameListActivity RecyclerView Adapter:
// Wherever you check/download, for example in onBindViewHolder:
/*
val basePath = Uri.parse(downloadDir).path ?: ""
val localFile = File(basePath, "$esdeFolder/$fsName")
Log.d("DownloadCheck", "Checking if exists: ${localFile.absolutePath}, exists=${localFile.exists()}")
// Use localFile.exists() to determine if the file is present
*/
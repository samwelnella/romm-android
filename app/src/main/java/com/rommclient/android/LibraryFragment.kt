
package com.rommclient.android

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.fragment.app.Fragment

class LibraryFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = inflater.inflate(R.layout.fragment_list, container, false)
        val listView = root.findViewById<ListView>(R.id.listView)

        val db = RommDatabaseHelper(requireContext())
        val slugs = db.getPlatformSlugs()

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, slugs)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val intent = Intent(requireContext(), LibraryGamesActivity::class.java)
            intent.putExtra("platform_slug", slugs[position])
            startActivity(intent)
        }

        return root
    }
}


package com.rommclient.android

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment

class LibraryFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = inflater.inflate(R.layout.fragment_list, container, false)
        val listView = root.findViewById<ListView>(R.id.listView)

        val context = requireContext()
        val db = RommDatabaseHelper(context)
        val slugs = db.getPlatformSlugs()

        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, slugs)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val intent = Intent(context, LibraryGamesActivity::class.java)
            intent.putExtra("platform_slug", slugs[position])
            startActivity(intent)
        }

        return root
    }
}

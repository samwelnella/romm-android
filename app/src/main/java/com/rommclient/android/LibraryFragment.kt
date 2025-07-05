
package com.rommclient.android

import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer

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
        val viewModel: LibraryViewModel by viewModels()
        val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter

        viewModel.platformSlugs.observe(viewLifecycleOwner, Observer { slugs ->
            adapter.clear()
            adapter.addAll(slugs)
            adapter.notifyDataSetChanged()
        })

        viewModel.loadSlugs()

        listView.setOnItemClickListener { _, _, position, _ ->
            val intent = Intent(context, LibraryGamesActivity::class.java)
            val selectedSlug = adapter.getItem(position)
            if (selectedSlug != null) {
                intent.putExtra("platform_slug", selectedSlug)
                startActivity(intent)
            }
        }

        return root
    }

    override fun onResume() {
        super.onResume()
        val viewModel: LibraryViewModel by viewModels()
        viewModel.loadSlugs()
    }
}

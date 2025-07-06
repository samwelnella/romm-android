
package com.rommclient.android

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure we always use "romm_prefs" as preferences file name
        val prefs = getSharedPreferences("romm_prefs", MODE_PRIVATE)
        val host = prefs.getString("host", null)
        android.util.Log.d("MainActivity", "Loaded host: $host")
        val downloadDir = prefs.getString("download_directory", null)
        android.util.Log.d("MainActivity", "Loaded downloadDir: $downloadDir")

        if (host.isNullOrBlank() || downloadDir.isNullOrBlank()) {
            // Optionally log what's missing
            if (host.isNullOrBlank()) {
                android.util.Log.w("MainActivity", "Missing or blank 'host' in preferences")
            }
            if (downloadDir.isNullOrBlank()) {
                android.util.Log.w("MainActivity", "Missing or blank 'download_directory' in preferences")
            }
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)
        toolbar.title = "RomM Platforms"

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = TabsPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Platforms"
                1 -> "Collections"
                else -> "Library"
            }
        }.attach()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

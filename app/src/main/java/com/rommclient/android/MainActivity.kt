package com.rommclient.android

import android.os.Build
import android.content.pm.PackageManager
import android.Manifest

import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MenuInflater
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {
    private lateinit var coordinatorRoot: View
    private var snackbar: com.google.android.material.snackbar.Snackbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        // Ensure we always use "romm_prefs" as preferences file name
        val prefs = getSharedPreferences("romm_prefs", MODE_PRIVATE)
        val maxDownloads = prefs.getInt("max_concurrent_downloads", 3)
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
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)

        // Removed moreButton.setOnClickListener block

        // Force overflow menu icon to always show
        try {
            val config = android.view.ViewConfiguration.get(this)
            val menuKeyField = android.view.ViewConfiguration::class.java.getDeclaredField("sHasPermanentMenuKey")
            menuKeyField.isAccessible = true
            menuKeyField.setBoolean(config, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        coordinatorRoot = findViewById(R.id.coordinatorRoot)

        toolbar.title = "RomM Platforms"
        // Removed toolbar.inflateMenu(R.menu.test_menu) and toolbar.setOnMenuItemClickListener block

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = TabsPagerAdapter(this)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val tag = "android:switcher:${viewPager.id}:$position"
                val fragment = supportFragmentManager.findFragmentByTag(tag)
                // No longer needed: setSnackbarView
            }
        })

        // Removed MenuProvider for main menu; handled in moreButton popup menu now.

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Platforms"
                1 -> "Collections"
                else -> "Library"
            }
        }.attach()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val isGameList = currentFragment is GameListFragment
        menu.findItem(R.id.menu_download_all)?.isVisible = isGameList
        menu.findItem(R.id.menu_download_all_skip_existing)?.isVisible = isGameList
        menu.findItem(R.id.menu_settings)?.isVisible = !isGameList
        return super.onPrepareOptionsMenu(menu)
    }

    fun showGameListFragment(
        host: String,
        port: String,
        user: String,
        pass: String,
        platformId: Int?,
        collectionId: Int?,
        name: String,
        firmwareJson: String? = null
    ) {
        val fragment = GameListFragment.newInstance(host, port, user, pass, platformId, collectionId, name, firmwareJson)
        findViewById<ViewPager2>(R.id.viewPager).visibility = View.GONE
        findViewById<TabLayout>(R.id.tabLayout).visibility = View.GONE
        findViewById<View>(R.id.fragment_container).visibility = View.VISIBLE

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .setPrimaryNavigationFragment(fragment)
            .addToBackStack(null)
            .commit()
        invalidateOptionsMenu()
    }

    fun showLibraryGamesFragment(slug: String) {
        val fragment = LibraryGamesFragment.newInstance(slug)
        findViewById<ViewPager2>(R.id.viewPager).visibility = View.GONE
        findViewById<TabLayout>(R.id.tabLayout).visibility = View.GONE
        findViewById<View>(R.id.fragment_container).visibility = View.VISIBLE

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
        invalidateOptionsMenu()
    }
    // Move onBackPressed override into MainActivity class body
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            findViewById<ViewPager2>(R.id.viewPager).visibility = View.VISIBLE
            findViewById<TabLayout>(R.id.tabLayout).visibility = View.VISIBLE
            findViewById<View>(R.id.fragment_container).visibility = View.GONE
        } else {
            super.onBackPressed()
        }
    }
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.game_list_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        return when (item.itemId) {
            R.id.menu_download_all -> {
                if (currentFragment is GameListFragment) {
                    currentFragment.enqueueAllDownloads(skipExisting = false)
                }
                true
            }
            R.id.menu_download_all_skip_existing -> {
                if (currentFragment is GameListFragment) {
                    currentFragment.enqueueAllDownloads(skipExisting = true)
                }
                true
            }
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
package com.rommclient.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.provider.DocumentsContract
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import android.widget.ProgressBar
import android.view.View

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("romm_prefs", Context.MODE_PRIVATE)

        val hostInput = findViewById<EditText>(R.id.settings_host_input)
        val portInput = findViewById<EditText>(R.id.settings_port_input)
        val userInput = findViewById<EditText>(R.id.settings_user_input)
        val passInput = findViewById<EditText>(R.id.settings_pass_input)
        val saveButton = findViewById<Button>(R.id.save_settings_button)

        hostInput.setText(prefs.getString("host", ""))
        portInput.setText(prefs.getString("port", ""))
        userInput.setText(prefs.getString("username", ""))
        passInput.setText(prefs.getString("password", ""))

        val downloadPathView = findViewById<TextView>(R.id.download_path)
        val selectButton = findViewById<Button>(R.id.select_directory_button)

        val downloadDir = prefs.getString("download_dir", "Not set")
        downloadPathView.text = "Current download directory: $downloadDir"

        selectButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            startActivityForResult(intent, 1001)
        }

        saveButton.setOnClickListener {
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim()
            val user = userInput.text.toString().trim()
            val pass = passInput.text.toString().trim()

            if (host.isBlank() || port.isBlank() || user.isBlank() || pass.isBlank()) {
                Toast.makeText(this, "All fields are required.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().apply {
                putString("host", host)
                putString("port", port)
                putString("username", user)
                putString("password", pass)
                apply()
            }

            Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

            val prefs = getSharedPreferences("romm_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("download_dir", uri.toString()).apply()

            val downloadPathView = findViewById<TextView>(R.id.download_path)
            downloadPathView.text = "Current download directory: $uri"
        }
    }
}
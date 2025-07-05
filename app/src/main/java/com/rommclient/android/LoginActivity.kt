
package com.rommclient.android

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedPrefs = getSharedPreferences("RomMConfig", MODE_PRIVATE)

        if (sharedPrefs.contains("host") && sharedPrefs.contains("port")
            && sharedPrefs.contains("user") && sharedPrefs.contains("pass")) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        setContentView(R.layout.activity_login)

        val hostInput = findViewById<EditText>(R.id.hostEditText)
        val portInput = findViewById<EditText>(R.id.portEditText)
        val userInput = findViewById<EditText>(R.id.userEditText)
        val passInput = findViewById<EditText>(R.id.passEditText)
        val loginButton = findViewById<Button>(R.id.loginButton)

        loginButton.setOnClickListener {
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim()
            val user = userInput.text.toString().trim()
            val pass = passInput.text.toString().trim()

            if (isAnyBlank(host, port, user, pass)) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val editor = sharedPrefs.edit().apply {
                putString("host", host)
                putString("port", port)
                putString("user", user)
                putString("pass", pass)
            }
            editor.apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun isAnyBlank(vararg fields: String) = fields.any { it.isBlank() }
}

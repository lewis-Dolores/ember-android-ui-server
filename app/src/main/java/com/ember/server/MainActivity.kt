package com.ember.server

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val etPort = findViewById<EditText>(R.id.etPort)

        etPort.setText(prefs.getInt("port", 8765).toString())

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val port = etPort.text.toString().toIntOrNull()
            if (port == null || port !in 1024..65535) {
                Toast.makeText(this, "Invalid port (1024–65535)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putInt("port", port).apply()
            Toast.makeText(this, "Saved. Restart the accessibility service to apply.", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}

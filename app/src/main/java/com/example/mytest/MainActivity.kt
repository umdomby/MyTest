package com.example.mytest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startWebRTCService()
            showToast("Service started successfully")
        } else {
            showToast("Required permissions not granted")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkAllPermissionsGranted()) {
            startWebRTCService()
            showToast("Service started successfully")
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun startWebRTCService() {
        try {
            val serviceIntent = Intent(this, WebRTCService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            // Не закрываем Activity сразу, даем пользователю возможность взаимодействовать
        } catch (e: Exception) {
            showToast("Failed to start service: ${e.message}")
            Log.e("MainActivity", "Service start failed", e)
        }
    }

    private fun checkAllPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show() // Используем LENGTH_LONG
    }
}
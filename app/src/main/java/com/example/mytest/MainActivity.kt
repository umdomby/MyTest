package com.example.mytest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        Manifest.permission.FOREGROUND_SERVICE_CAMERA
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startWebRTCService()
            showToast("Service started successfully")
        } else {
            showToast("Not all permissions granted")
            finish()
        }
    }
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startWebRTCService()
        } else {
            showToast("Media projection permission denied")
            finish()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkAllPermissionsGranted()) {
            requestMediaProjection()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun requestMediaProjection() {
        val mediaManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaManager.createScreenCaptureIntent())
    }

    private fun startWebRTCService() {
        try {
            val serviceIntent = Intent(this, WebRTCService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            showToast("Error starting service: ${e.message}")
            Log.e("MainActivity", "Service start error", e)
        }
    }

    private fun checkAllPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
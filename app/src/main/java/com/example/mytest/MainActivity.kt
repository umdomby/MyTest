// MainActivity.kt
package com.example.mytest

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.mytest.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import java.util.*
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private var currentRoomName: String = generateRandomRoomName()
    private var isServiceRunning: Boolean = false
    private val roomList = mutableListOf<String>()
    private lateinit var roomListAdapter: ArrayAdapter<String>

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            if (isCameraPermissionGranted()) {
                val mediaManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mediaManager.createScreenCaptureIntent())
                checkBatteryOptimization()
            } else {
                showToast("Camera permission required")
                finish()
            }
        } else {
            showToast("Not all permissions granted")
            finish()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startWebRTCService(result.data!!)
        } else {
            showToast("Screen recording access denied")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent keyboard from popping up automatically
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        loadRoomList()
        setupUI()
        setupRoomListAdapter()
        updateButtonStates()
    }

    private fun loadRoomList() {
        val jsonString = sharedPreferences.getString(ROOM_LIST_KEY, null)
        jsonString?.let {
            val jsonArray = JSONArray(it)
            for (i in 0 until jsonArray.length()) {
                roomList.add(jsonArray.getString(i))
            }
        }
        if (roomList.isEmpty()) {
            roomList.add(currentRoomName)
            saveRoomList()
        } else {
            currentRoomName = roomList.first()
        }
    }

    private fun saveRoomList() {
        val jsonArray = JSONArray()
        roomList.forEach { jsonArray.put(it) }
        sharedPreferences.edit()
            .putString(ROOM_LIST_KEY, jsonArray.toString())
            .apply()
    }

    private fun setupRoomListAdapter() {
        roomListAdapter = ArrayAdapter(
            this,
            com.google.android.material.R.layout.support_simple_spinner_dropdown_item,
            roomList
        )
        binding.roomListView.adapter = roomListAdapter
        binding.roomListView.setOnItemClickListener { _, _, position, _ ->
            currentRoomName = roomList[position]
            binding.roomCodeEditText.setText(currentRoomName)
            updateButtonStates()
        }
        binding.roomListView.setOnItemLongClickListener { _, _, position, _ ->
            showDeleteRoomDialog(position)
            true
        }
    }

    private fun showDeleteRoomDialog(position: Int) {
        if (roomList.size <= 1) {
            showToast("Cannot delete the last room")
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Room")
            .setMessage("Are you sure you want to delete room '${roomList[position]}'?")
            .setPositiveButton("Delete") { _, _ ->
                val removedRoom = roomList.removeAt(position)
                saveRoomList()
                roomListAdapter.notifyDataSetChanged()

                if (currentRoomName == removedRoom) {
                    currentRoomName = roomList.first()
                    binding.roomCodeEditText.setText(currentRoomName)
                }

                showToast("Room deleted")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupUI() {
        binding.roomCodeEditText.setText(currentRoomName)
        binding.roomCodeEditText.clearFocus()

        binding.roomCodeEditText.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || isServiceRunning) return

                isFormatting = true

                val text = s.toString().replace("-", "").uppercase(Locale.getDefault())
                if (text.length > 12) {
                    s?.replace(0, s.length, currentRoomName)
                } else {
                    val formatted = StringBuilder()
                    for (i in text.indices) {
                        if (i > 0 && i % 4 == 0) {
                            formatted.append('-')
                        }
                        formatted.append(text[i])
                    }
                    s?.replace(0, s.length, formatted.toString())
                }

                isFormatting = false
            }
        })

        binding.saveCodeButton.setOnClickListener {
            if (isServiceRunning) {
                showToast("Stop the service first")
                return@setOnClickListener
            }

            val newRoomName = binding.roomCodeEditText.text.toString().trim()
            if (newRoomName.isNotBlank()) {
                if (!roomList.contains(newRoomName)) {
                    roomList.add(0, newRoomName)
                    saveRoomList()
                    roomListAdapter.notifyDataSetChanged()
                    currentRoomName = newRoomName
                    showToast("Room saved: $newRoomName")
                } else {
                    showToast("Room already exists")
                }
            } else {
                showToast("Enter room name")
            }
        }

        binding.generateCodeButton.setOnClickListener {
            if (isServiceRunning) {
                showToast("Stop the service first")
                return@setOnClickListener
            }

            val randomRoomName = generateRandomRoomName()
            binding.roomCodeEditText.setText(randomRoomName)
            showToast("Generated: $randomRoomName")
        }

        binding.copyCodeButton.setOnClickListener {
            val roomName = binding.roomCodeEditText.text.toString()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Room name", roomName)
            clipboard.setPrimaryClip(clip)
            showToast("Copied: $roomName")
        }

        binding.shareCodeButton.setOnClickListener {
            val roomName = binding.roomCodeEditText.text.toString()
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, "Join my room: $roomName")
                type = "text/plain"
            }
            startActivity(Intent.createChooser(shareIntent, "Share Room"))
        }

        binding.startButton.setOnClickListener {
            if (isServiceRunning) {
                showToast("Service already running")
                return@setOnClickListener
            }

            currentRoomName = binding.roomCodeEditText.text.toString().trim()
            if (currentRoomName.isEmpty()) {
                showToast("Enter room name")
                return@setOnClickListener
            }

            if (checkAllPermissionsGranted() && isCameraPermissionGranted()) {
                val mediaManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mediaManager.createScreenCaptureIntent())
                checkBatteryOptimization()
            } else {
                requestPermissionLauncher.launch(requiredPermissions)
            }
        }

        binding.stopButton.setOnClickListener {
            if (!isServiceRunning) {
                showToast("Service not running")
                return@setOnClickListener
            }
            stopWebRTCService()
        }
    }

    private fun generateRandomRoomName(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = Random.Default
        val code = StringBuilder()

        repeat(4) { group ->
            repeat(4) {
                code.append(chars[random.nextInt(chars.length)])
            }
            if (group < 3) code.append('-')
        }

        return code.toString()
    }

    private fun startWebRTCService(resultData: Intent) {
        try {
            WebRTCService.currentRoomName = currentRoomName
            val serviceIntent = Intent(this, WebRTCService::class.java).apply {
                putExtra("resultCode", RESULT_OK)
                putExtra("resultData", resultData)
                putExtra("roomName", currentRoomName)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            isServiceRunning = true
            updateButtonStates()
            showToast("Сервис запущен: $currentRoomName")
        } catch (e: Exception) {
            showToast("Ошибка запуска: ${e.message}")
            Log.e("MainActivity", "Ошибка запуска сервиса", e)
        }
    }

    private fun stopWebRTCService() {
        try {
            val stopIntent = Intent(this, WebRTCService::class.java).apply {
                action = "STOP"
            }
            startService(stopIntent)
            isServiceRunning = false
            updateButtonStates()
            showToast("Сервис остановлен")
        } catch (e: Exception) {
            showToast("Ошибка остановки: ${e.message}")
            Log.e("MainActivity", "Ошибка остановки сервиса", e)
        }
    }

    private fun isServiceRunning(): Boolean {
        return WebRTCService.isRunning
    }

    private fun updateButtonStates() {
        binding.apply {
            startButton.isEnabled = !isServiceRunning
            stopButton.isEnabled = isServiceRunning
            roomCodeEditText.isEnabled = !isServiceRunning
            saveCodeButton.isEnabled = !isServiceRunning
            generateCodeButton.isEnabled = !isServiceRunning

            startButton.setBackgroundColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (isServiceRunning) android.R.color.darker_gray else R.color.green
                )
            )
            stopButton.setBackgroundColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (isServiceRunning) R.color.red else android.R.color.darker_gray
                )
            )
        }
    }

    private fun checkAllPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(PowerManager::class.java)
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val PREFS_NAME = "WebRTCPrefs"
        private const val ROOM_LIST_KEY = "room_list"
        private const val DEFAULT_ROOM_NAME = "room1"
    }
}
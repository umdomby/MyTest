// file: src/main/java/com/example/mytest/MainActivity.kt
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
import androidx.activity.result.registerForActivityResult
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
    private var currentRoomName: String = ""
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
        // Загружаем список комнат
        val jsonString = sharedPreferences.getString(ROOM_LIST_KEY, null)
        jsonString?.let {
            val jsonArray = JSONArray(it)
            for (i in 0 until jsonArray.length()) {
                roomList.add(jsonArray.getString(i))
            }
        }

        // Загружаем последнее использованное имя комнаты
        currentRoomName = sharedPreferences.getString(LAST_USED_ROOM_KEY, "") ?: ""

        // Если нет сохраненных комнат или последнее имя пустое, генерируем новое
        if (roomList.isEmpty() || currentRoomName.isEmpty()) {
            currentRoomName = generateRandomRoomName()
            roomList.add(currentRoomName)
            saveRoomList()
        }

        // Устанавливаем последнее использованное имя в поле ввода
        binding.roomCodeEditText.setText(formatRoomName(currentRoomName))
    }

    private fun saveRoomList() {
        val jsonArray = JSONArray()
        roomList.forEach { jsonArray.put(it) }
        sharedPreferences.edit()
            .putString(ROOM_LIST_KEY, jsonArray.toString())
            .putString(LAST_USED_ROOM_KEY, currentRoomName)
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
            binding.roomCodeEditText.setText(formatRoomName(currentRoomName))
            updateButtonStates()
        }
    }

    private fun setupUI() {
        // Обработчик ввода с автоматической вставкой тире
        binding.roomCodeEditText.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            private var deletingHyphen = false
            private var hyphenPositions = listOf(4, 9, 14)

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (isFormatting) return

                // Проверяем, удаляется ли тире
                if (count == 1 && after == 0 && hyphenPositions.contains(start)) {
                    deletingHyphen = true
                } else {
                    deletingHyphen = false
                }
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || isServiceRunning) return

                isFormatting = true

                val text = s.toString().replace("-", "")
                if (text.length > 16) {
                    s?.replace(0, s.length, formatRoomName(currentRoomName))
                } else {
                    val formatted = StringBuilder()
                    for (i in text.indices) {
                        if (i > 0 && i % 4 == 0) {
                            formatted.append('-')
                        }
                        formatted.append(text[i])
                    }

                    // Если удаляли тире, корректируем позицию курсора
                    val cursorPos = binding.roomCodeEditText.selectionStart
                    if (deletingHyphen && cursorPos > 0 && cursorPos < formatted.length &&
                        formatted[cursorPos] == '-') {
                        formatted.deleteCharAt(cursorPos)
                    }

                    s?.replace(0, s.length, formatted.toString())
                }

                isFormatting = false

                // Проверяем валидность имени для активации кнопки Save
                val cleanName = binding.roomCodeEditText.text.toString().replace("-", "")
                binding.saveCodeButton.isEnabled = cleanName.length == 16 &&
                        !roomList.contains(cleanName)
            }
        })

        binding.generateCodeButton.setOnClickListener {
            currentRoomName = generateRandomRoomName()
            binding.roomCodeEditText.setText(formatRoomName(currentRoomName))
            showToast("Generated: $currentRoomName")
        }

        binding.deleteRoomButton.setOnClickListener {
            val selectedRoom = binding.roomCodeEditText.text.toString().replace("-", "")
            if (roomList.contains(selectedRoom)) {
                showDeleteConfirmationDialog(selectedRoom)
            } else {
                showToast(getString(R.string.room_not_found))
            }
        }

        binding.saveCodeButton.setOnClickListener {
            val newRoomName = binding.roomCodeEditText.text.toString().replace("-", "")
            if (newRoomName.length == 16) {
                if (!roomList.contains(newRoomName)) {
                    roomList.add(0, newRoomName)
                    currentRoomName = newRoomName
                    saveRoomList()
                    roomListAdapter.notifyDataSetChanged()
                    showToast("Room saved: ${formatRoomName(newRoomName)}")
                } else {
                    showToast("Room already exists")
                }
            }
        }

        // Остальные кнопки без изменений
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

            currentRoomName = binding.roomCodeEditText.text.toString().replace("-", "")
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

    // Форматирование имени комнаты с тире
    private fun formatRoomName(name: String): String {
        if (name.length != 16) return name

        return buildString {
            for (i in 0 until 16) {
                if (i > 0 && i % 4 == 0) append('-')
                append(name[i])
            }
        }
    }

    private fun showDeleteConfirmationDialog(roomName: String) {
        if (roomList.size <= 1) {
            showToast(getString(R.string.cannot_delete_last))
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_confirm_title))
            .setMessage(getString(R.string.delete_confirm_message, formatRoomName(roomName)))
            .setPositiveButton(getString(R.string.delete_button)) { _, _ ->
                roomList.remove(roomName)
                saveRoomList()
                roomListAdapter.notifyDataSetChanged()

                if (currentRoomName == roomName) {
                    currentRoomName = roomList.first()
                    binding.roomCodeEditText.setText(formatRoomName(currentRoomName))
                }

                showToast("Room deleted")
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
    }

    private fun generateRandomRoomName(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = Random.Default
        val code = StringBuilder()

        repeat(16) {
            code.append(chars[random.nextInt(chars.length)])
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
            showToast("Service started: ${formatRoomName(currentRoomName)}")
        } catch (e: Exception) {
            showToast("Start error: ${e.message}")
            Log.e("MainActivity", "Service start error", e)
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
            showToast("Service stopped")
        } catch (e: Exception) {
            showToast("Stop error: ${e.message}")
            Log.e("MainActivity", "Service stop error", e)
        }
    }

    private fun updateButtonStates() {
        binding.apply {
            startButton.isEnabled = !isServiceRunning
            stopButton.isEnabled = isServiceRunning
            roomCodeEditText.isEnabled = !isServiceRunning
            saveCodeButton.isEnabled = !isServiceRunning &&
                    binding.roomCodeEditText.text.toString().replace("-", "").length == 16 &&
                    !roomList.contains(binding.roomCodeEditText.text.toString().replace("-", ""))
            generateCodeButton.isEnabled = !isServiceRunning
            deleteRoomButton.isEnabled = !isServiceRunning &&
                    roomList.contains(binding.roomCodeEditText.text.toString().replace("-", ""))

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
        private const val LAST_USED_ROOM_KEY = "last_used_room"
    }
}
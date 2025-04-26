package com.example.mytest

import android.Manifest
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.mytest.databinding.ActivityMainBinding
import java.util.*
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private var currentRoomName: String = DEFAULT_ROOM_NAME
    private var isServiceRunning: Boolean = false

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
                // Прямой запуск mediaProjectionLauncher
                val mediaManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mediaManager.createScreenCaptureIntent())
                checkBatteryOptimization()
            } else {
                showToast("Требуется разрешение на использование камеры")
                finish()
            }
        } else {
            showToast("Не все разрешения предоставлены")
            finish()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startWebRTCService(result.data!!)
        } else {
            showToast("Доступ к записи экрана не предоставлен")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentRoomName = sharedPreferences.getString(ROOM_NAME_KEY, DEFAULT_ROOM_NAME) ?: DEFAULT_ROOM_NAME
        isServiceRunning = isServiceRunning()

        setupUI()
        updateButtonStates()
    }

    private fun setupUI() {
        binding.roomCodeEditText.setText(currentRoomName)

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
                showToast("Сначала остановите сервис")
                return@setOnClickListener
            }

            val newRoomName = binding.roomCodeEditText.text.toString()
            if (newRoomName.isNotBlank() && newRoomName != currentRoomName) {
                saveRoomName(newRoomName)
                showToast("Имя комнаты сохранено: $newRoomName")
            } else {
                showToast("Введите новое имя комнаты")
            }
        }

        binding.generateCodeButton.setOnClickListener {
            if (isServiceRunning) {
                showToast("Сначала остановите сервис")
                return@setOnClickListener
            }

            val randomRoomName = generateRandomRoomName()
            binding.roomCodeEditText.setText(randomRoomName)
            showToast("Сгенерировано новое имя: $randomRoomName")
        }

        binding.copyCodeButton.setOnClickListener {
            val roomName = binding.roomCodeEditText.text.toString()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Room name", roomName)
            clipboard.setPrimaryClip(clip)
            showToast("Скопировано в буфер обмена: $roomName")
        }

        binding.shareCodeButton.setOnClickListener {
            val roomName = binding.roomCodeEditText.text.toString()
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, "Присоединяйтесь к моей комнате: $roomName")
                type = "text/plain"
            }
            startActivity(Intent.createChooser(shareIntent, "Поделиться комнатой"))
        }

        binding.startButton.setOnClickListener {
            if (isServiceRunning) {
                showToast("Сервис уже запущен")
                return@setOnClickListener
            }

            if (checkAllPermissionsGranted() && isCameraPermissionGranted()) {
                // Исправленная строка - используем mediaProjectionLauncher вместо requestMediaProjection()
                val mediaManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mediaManager.createScreenCaptureIntent())
                checkBatteryOptimization()
            } else {
                requestPermissionLauncher.launch(requiredPermissions)
            }
        }

        binding.stopButton.setOnClickListener {
            if (!isServiceRunning) {
                showToast("Сервис не запущен")
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

        // Сохраняем сгенерированное имя сразу
        saveRoomName(code.toString())
        return code.toString()
    }

    private fun saveRoomName(roomName: String) {
        currentRoomName = roomName
        sharedPreferences.edit()
            .putString(ROOM_NAME_KEY, roomName)
            .apply()
    }

    private fun startWebRTCService(resultData: Intent) {
        try {
            val roomName = binding.roomCodeEditText.text.toString()
            if (roomName.isBlank()) {
                showToast("Введите имя комнаты")
                return
            }

            val serviceIntent = Intent(this, WebRTCService::class.java).apply {
                putExtra("resultCode", RESULT_OK)
                putExtra("resultData", resultData)
                putExtra("roomName", roomName)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            isServiceRunning = true
            updateButtonStates()
            showToast("Сервис запущен с комнатой: $roomName")
        } catch (e: Exception) {
            showToast("Ошибка запуска сервиса: ${e.message}")
            Log.e("MainActivity", "Ошибка запуска сервиса", e)
        }
    }

    private fun stopWebRTCService() {
        try {
            val serviceIntent = Intent(this, WebRTCService::class.java).apply {
                action = "STOP" // Явно указываем действие STOP
            }
            stopService(serviceIntent)
            isServiceRunning = false
            updateButtonStates()
            showToast("Сервис остановлен")
        } catch (e: Exception) {
            showToast("Ошибка остановки сервиса: ${e.message}")
            Log.e("MainActivity", "Ошибка остановки сервиса", e)
        }
    }

    private fun isServiceRunning(): Boolean {
        return WebRTCService.isRunning
    }

    private fun updateButtonStates() {
        binding.startButton.isEnabled = !isServiceRunning
        binding.stopButton.isEnabled = isServiceRunning
        binding.roomCodeEditText.isEnabled = !isServiceRunning
        binding.saveCodeButton.isEnabled = !isServiceRunning
        binding.generateCodeButton.isEnabled = !isServiceRunning
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
        private const val ROOM_NAME_KEY = "room_name"
        private const val DEFAULT_ROOM_NAME = "room1"
    }
}
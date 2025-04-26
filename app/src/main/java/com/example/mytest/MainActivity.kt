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
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.util.*
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var roomCodeEditText: EditText

    // Генерация случайного кода комнаты
    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString {
            repeat(4) { part ->
                repeat(4) {
                    append(chars[Random.nextInt(chars.length)])
                }
                if (part < 3) append("-")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("WebRTCPrefs", MODE_PRIVATE)
        roomCodeEditText = findViewById(R.id.roomCodeEditText)

        // Загрузка сохраненного кода комнаты
        val savedRoomCode = prefs.getString("roomCode", "room1")
        roomCodeEditText.setText(savedRoomCode)

        val generateCodeButton = findViewById<Button>(R.id.generateCodeButton)
        val copyCodeButton = findViewById<Button>(R.id.copyCodeButton)
        val shareCodeButton = findViewById<Button>(R.id.shareCodeButton)
        val saveCodeButton = findViewById<Button>(R.id.saveCodeButton)
        val startButton = findViewById<Button>(R.id.startButton)

        // Загрузка сохраненного кода комнаты
        roomCodeEditText.setText(savedRoomCode)

        // Автоматическое добавление тире при вводе
        roomCodeEditText.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting) return
                isFormatting = true

                val str = s.toString().replace("-", "")
                if (str.length > 0) {
                    val builder = StringBuilder()
                    for (i in 0 until str.length) {
                        builder.append(str[i])
                        if (i % 4 == 3 && i != str.length - 1) {
                            builder.append('-')
                        }
                    }
                    roomCodeEditText.setText(builder.toString())
                    roomCodeEditText.setSelection(builder.length)
                }

                isFormatting = false
            }
        })

        // Генерация нового кода комнаты
        generateCodeButton.setOnClickListener {
            val newCode = generateRoomCode()
            roomCodeEditText.setText(newCode)
        }

        // Копирование кода в буфер обмена
        copyCodeButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Room Code", roomCodeEditText.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Код скопирован", Toast.LENGTH_SHORT).show()
        }

        // Поделиться кодом через другие приложения
        shareCodeButton.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Присоединяйтесь к моей комнате: ${roomCodeEditText.text}")
                putExtra(Intent.EXTRA_SUBJECT, "Приглашение в WebRTC комнату")
            }
            startActivity(Intent.createChooser(shareIntent, "Поделиться кодом комнаты"))
        }

        // Сохранение кода комнаты
        saveCodeButton.setOnClickListener {
            val roomCode = roomCodeEditText.text.toString().trim()
            if (roomCode.isNotEmpty()) {
                prefs.edit().putString("roomCode", roomCode).apply()
                Toast.makeText(this, "Код комнаты сохранен", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Введите код комнаты", Toast.LENGTH_SHORT).show()
            }
        }

        // Запуск трансляции
        startButton.setOnClickListener {
            if (checkAllPermissionsGranted() && isCameraPermissionGranted()) {
                requestMediaProjection()
                checkBatteryOptimization()
            } else {
                requestPermissionLauncher.launch(requiredPermissions)
            }
        }

        val stopButton = findViewById<Button>(R.id.stopButton)
        stopButton.setOnClickListener {
            stopService(Intent(this, WebRTCService::class.java))
            Toast.makeText(this, "Сервис остановлен", Toast.LENGTH_SHORT).show()
        }
    }

    // Остальные методы остаются без изменений
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
                requestMediaProjection()
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

    private fun requestMediaProjection() {
        val mediaManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaManager.createScreenCaptureIntent())
    }

    private fun startWebRTCService(resultData: Intent) {
        try {
            val roomCode = prefs.getString("roomCode", "room1") ?: "room1"
            val serviceIntent = Intent(this, WebRTCService::class.java).apply {
                putExtra("resultCode", RESULT_OK)
                putExtra("resultData", resultData)
                putExtra("roomName", roomCode)
            }

            // Останавливаем сервис, если он уже запущен
            stopService(Intent(this, WebRTCService::class.java))

            // Запускаем сервис с новыми параметрами
            ContextCompat.startForegroundService(this, serviceIntent)
            showToast("Сервис запущен для комнаты $roomCode")
        } catch (e: Exception) {
            showToast("Ошибка запуска сервиса: ${e.message}")
            Log.e("MainActivity", "Ошибка запуска сервиса", e)
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
}
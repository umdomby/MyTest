// file: src/main/java/com/example/mytest/WebSocketClient.kt
package com.example.mytest

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface WebSocketListener {
    fun onMessage(message: JSONObject)
    fun onConnected()
    fun onDisconnected()
    fun onError(error: String)
}

class WebSocketClient(private val listener: WebSocketListener) {
    private var webSocket: okhttp3.WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun connect(url: String) {
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                Log.d("WebSocket", "Connection opened")
                listener.onConnected()
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                try {
                    Log.d("WebSocket", "Received: $text")
                    listener.onMessage(JSONObject(text))
                } catch (e: Exception) {
                    listener.onError("Parse error: ${e.message}")
                }
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                Log.d("WebSocket", "Connection closed: $reason")
                listener.onDisconnected()
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocket", "Connection error", t)
                listener.onError(t.message ?: "Unknown error")
            }
        })
    }

    fun send(message: String) {
        webSocket?.send(message)
    }

    fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        client.dispatcher.executorService.shutdown()
    }
}
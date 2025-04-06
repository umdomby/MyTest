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
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun connect(url: String) {
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    listener.onMessage(JSONObject(text))
                } catch (e: Exception) {
                    listener.onError("Failed to parse message: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "Unknown error")
            }
        })
    }

    fun send(message: JSONObject) {
        webSocket?.send(message.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        client.dispatcher.executorService.shutdown()
    }
}
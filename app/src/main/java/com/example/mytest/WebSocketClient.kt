package com.example.mytest

import android.annotation.SuppressLint
import android.util.Log
import okhttp3.*
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class WebSocketClient(private val listener: WebSocketListener) {
    private var webSocket: WebSocket? = null
    private var isConnectionActive = false
    private val client: OkHttpClient = createUnsafeOkHttpClient()

    fun connect(url: String) {
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnectionActive = true
                listener.onOpen(webSocket, response)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(webSocket, text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnectionActive = false
                listener.onClosed(webSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnectionActive = false
                listener.onFailure(webSocket, t, response)
            }
        })
    }

    fun send(message: String): Boolean {
        return if (isConnected()) {
            webSocket?.send(message) ?: false
        } else {
            false
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        client.dispatcher.executorService.shutdown()
        isConnectionActive = false
    }

    fun isConnected(): Boolean {
        return isConnectionActive && webSocket != null
    }

    @SuppressLint("CustomX509TrustManager")
    private fun createUnsafeOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS) // Ping каждые 20 секунд для поддержания соединения
            .sslSocketFactory(getUnsafeSSLSocketFactory(), getTrustAllCerts()[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true } // Пропускаем проверку SSL
            .build()
    }

    private fun getUnsafeSSLSocketFactory(): SSLSocketFactory {
        val trustAllCerts = getTrustAllCerts()
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext.socketFactory
    }

    private fun getTrustAllCerts(): Array<TrustManager> {
        return arrayOf(
            @SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )
    }
}
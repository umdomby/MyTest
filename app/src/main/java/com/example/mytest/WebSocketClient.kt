package com.example.mytest

import android.annotation.SuppressLint
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class WebSocketClient(private val listener: okhttp3.WebSocketListener) {
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .hostnameVerifier { _, _ -> true }
        .sslSocketFactory(getUnsafeSSLSocketFactory(), getTrustAllCerts()[0] as X509TrustManager)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

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
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
    }

    fun connect(url: String) {
        try {
            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, listener)
            isConnected = true
        } catch (e: Exception) {
            Log.e("WebSocketClient", "Connection error", e)
            isConnected = false
            throw e
        }
    }

    fun send(message: String) {
        if (isConnected) {
            try {
                webSocket?.send(message)
            } catch (e: Exception) {
                Log.e("WebSocketClient", "Send error", e)
                isConnected = false
            }
        }
    }

    fun disconnect() {
        try {
            isConnected = false
            webSocket?.close(1000, "Normal closure")
        } catch (e: Exception) {
            Log.e("WebSocketClient", "Disconnect error", e)
        }
    }
}
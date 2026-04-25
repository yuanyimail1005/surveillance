package com.example.pisurveillance.websocket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import timber.log.Timber

/**
 * Manages video stream WebSocket connection and MJPEG frame processing
 */
class VideoStreamManager(
    private val serverUrl: String
) {
    private var webSocket: WebSocket? = null
    private val httpClient = OkHttpClient()

    private val _frames = MutableStateFlow<Bitmap?>(null)
    val frames: StateFlow<Bitmap?> = _frames

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private var frameBuffer = ByteArray(0)
    private var isReadingFrame = false

    /**
     * Connect to video feed WebSocket
     */
    fun connect() {
        if (webSocket != null) {
            Timber.w("Video WebSocket already connected")
            return
        }

        val wsUrl = serverUrl.replace(Regex("^https://"), "wss://")
            .replace(Regex("^http://"), "ws://")
            .trimEnd('/') + "/video_feed"

        val request = Request.Builder().url(wsUrl).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.d("Video WebSocket connected")
                _isConnected.value = true
                _errorMessage.value = null
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                processVideoFrame(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Timber.d("Video text message: $text")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.e(t, "Video WebSocket connection failed")
                _isConnected.value = false
                _errorMessage.value = t.message ?: "Connection failed"
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("Video WebSocket closed: $reason")
                _isConnected.value = false
            }
        })
    }

    /**
     * Process incoming video frame (MJPEG)
     */
    private fun processVideoFrame(data: ByteArray) {
        try {
            // MJPEG frames are typically preceded by boundary markers
            // For simplicity, we'll attempt to decode the data directly
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            if (bitmap != null) {
                _frames.value = bitmap
                _errorMessage.value = null
            } else {
                Timber.w("Failed to decode frame")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing video frame")
            _errorMessage.value = "Frame decode error: ${e.message}"
        }
    }

    /**
     * Disconnect from video feed
     */
    fun disconnect() {
        webSocket?.close(1000, "Client closing")
        webSocket = null
        _isConnected.value = false
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = _isConnected.value
}

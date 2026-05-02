package com.example.pisurveillance.websocket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.pisurveillance.models.FaceAiData
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import timber.log.Timber

/**
 * Manages video stream WebSocket connection and MJPEG frame processing
 */
class VideoStreamManager(
    private val serverUrl: String,
    private val httpClient: OkHttpClient
) {
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val frameChannel = Channel<ByteArray>(Channel.CONFLATED)
    private var processingJob: Job? = null

    private val _frames = MutableStateFlow<Bitmap?>(null)
    val frames: StateFlow<Bitmap?> = _frames

    private val _faceData = MutableStateFlow<FaceAiData?>(null)
    val faceData: StateFlow<FaceAiData?> = _faceData

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    init {
        startFrameProcessor()
    }

    private fun startFrameProcessor() {
        processingJob?.cancel()
        processingJob = processingScope.launch {
            for (data in frameChannel) {
                if (!isActive) break
                processVideoFrame(data)
            }
        }
    }

    /**
     * Connect to video feed WebSocket
     */
    fun connect() {
        if (webSocket != null) return
        
        // Ensure processor is running
        if (processingJob == null || !processingJob!!.isActive) {
            startFrameProcessor()
        }

        try {
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

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val data = gson.fromJson(text, FaceAiData::class.java)
                        if (data.type == "face_data") {
                            _faceData.value = data
                        }
                    } catch (e: Exception) {
                        Timber.w("Failed to parse text message: $text")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Mechanism to drop stale frames: Channel.CONFLATED 
                    // will only keep the most recent frame data if the processor is busy.
                    frameChannel.trySend(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.e(t, "Video WebSocket failure")
                    handleConnectionLoss(t.message)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Timber.d("Video WebSocket closed")
                    handleConnectionLoss(null)
                }
            })
        } catch (e: Exception) {
            Timber.e(e, "Error connecting to video stream")
            _errorMessage.value = e.message
            _isConnected.value = false
        }
    }

    private fun handleConnectionLoss(error: String?) {
        _isConnected.value = false
        _errorMessage.value = error
        webSocket = null
    }

    /**
     * Process incoming video frame (MJPEG)
     */
    private fun processVideoFrame(data: ByteArray) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            if (bitmap != null) {
                _frames.value = bitmap
                _errorMessage.value = null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error decoding video frame")
        }
    }

    /**
     * Disconnect from video feed
     */
    fun disconnect() {
        webSocket?.close(1000, "Client closing")
        webSocket = null
        _isConnected.value = false
        
        // Cancel processing to free up resources
        processingJob?.cancel()
        processingJob = null
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = _isConnected.value
}

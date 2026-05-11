package com.example.pisurveillance.websocket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.pisurveillance.models.FaceAiData
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import timber.log.Timber

/**
 * Represents a video frame with its sequence number for synchronization
 */
data class VideoFrame(
    val bitmap: Bitmap,
    val sequence: Long,
    val rawData: ByteArray
)

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
    private val frameChannel = Channel<Pair<ByteArray, Long>>(Channel.CONFLATED)
    private var processingJob: Job? = null
    
    private var nextFrameSeq: Long = 0
    private var lastHandledSeq: Long = 0

    private val _frames = MutableStateFlow<VideoFrame?>(null)
    val frames: StateFlow<VideoFrame?> = _frames

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
            for ((data, seq) in frameChannel) {
                if (!isActive) break
                processVideoFrame(data, seq)
            }
        }
    }

    /**
     * Connect to video feed WebSocket
     */
    fun connect() {
        if (webSocket != null) return
        
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
                    nextFrameSeq = 0
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = gson.fromJson(text, JsonObject::class.java)
                        val type = json.get("type")?.asString
                        
                        if (type == "frame_meta") {
                            nextFrameSeq = json.get("broadcast_frame_seq")?.asLong ?: 0
                        } else if (type == "face_data") {
                            val data = gson.fromJson(text, FaceAiData::class.java)
                            _faceData.value = data
                        }
                    } catch (e: Exception) {
                        Timber.w("Failed to parse text message: $text")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val seq = if (nextFrameSeq > 0) nextFrameSeq else 0
                    
                    // Drop frames that arrive out of order or are older than what we've handled
                    if (seq > 0 && seq <= lastHandledSeq) {
                        nextFrameSeq = 0
                        return
                    }
                    
                    if (seq > 0) {
                        lastHandledSeq = seq
                    }

                    frameChannel.trySend(Pair(bytes.toByteArray(), seq))
                    nextFrameSeq = 0 
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

    private fun processVideoFrame(data: ByteArray, seq: Long) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            if (bitmap != null) {
                _frames.value = VideoFrame(bitmap, seq, data)
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
        processingJob?.cancel()
        processingJob = null
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = _isConnected.value
}

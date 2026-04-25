package com.example.pisurveillance.websocket

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import timber.log.Timber

/**
 * Manages talkback audio WebSocket connection - send microphone audio to server
 * Audio format: 48kHz, mono, 16-bit PCM
 */
class TalkbackManager(
    private val serverUrl: String
) {
    private var webSocket: WebSocket? = null
    private val httpClient = OkHttpClient()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var recordingScope: CoroutineScope? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SIZE = 2048  // ~42ms at 48kHz
    }

    /**
     * Connect to talkback WebSocket
     */
    fun connect() {
        if (webSocket != null) {
            Timber.w("Talkback WebSocket already connected")
            return
        }

        try {
            val wsUrl = serverUrl.replace(Regex("^https://"), "wss://")
                .replace(Regex("^http://"), "ws://")
                .trimEnd('/') + "/ws/talk"

            val request = Request.Builder().url(wsUrl).build()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Timber.d("Talkback WebSocket connected")
                    _isConnected.value = true
                    _errorMessage.value = null
                    startRecording()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Timber.d("Talkback text message: $text")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.e(t, "Talkback WebSocket connection failed")
                    _isConnected.value = false
                    _isRecording.value = false
                    _errorMessage.value = t.message ?: "Connection failed"
                    stopRecording()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Timber.d("Talkback WebSocket closed: $reason")
                    _isConnected.value = false
                    _isRecording.value = false
                    stopRecording()
                }
            })
        } catch (e: Exception) {
            Timber.e(e, "Error connecting to talkback stream")
            _errorMessage.value = e.message
            _isConnected.value = false
        }
    }

    /**
     * Start recording from microphone and sending to server
     */
    private fun startRecording() {
        if (_isRecording.value) {
            return
        }

        try {
            initializeAudioRecord()

            recordingScope = CoroutineScope(Dispatchers.IO)
            recordingJob = recordingScope?.launch {
                val buffer = ByteArray(FRAME_SIZE * 2)  // 16-bit = 2 bytes per sample
                audioRecord?.startRecording()
                _isRecording.value = true

                while (_isRecording.value && isActive) {
                    try {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (bytesRead!! > 0) {
                            webSocket?.send(ByteString.of(buffer, 0, bytesRead))
                        } else if (bytesRead < 0) {
                            Timber.e("AudioRecord error code: $bytesRead")
                            stopRecording()
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error reading from AudioRecord")
                        break
                    }
                }

                audioRecord?.stop()
                _isRecording.value = false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error starting talkback recording")
            _errorMessage.value = e.message
            _isRecording.value = false
        }
    }

    /**
     * Initialize AudioRecord for microphone input
     */
    private fun initializeAudioRecord() {
        if (audioRecord != null) {
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )
    }

    /**
     * Stop recording from microphone
     */
    private fun stopRecording() {
        try {
            _isRecording.value = false
            recordingJob?.cancel()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordingScope?.cancel()
        } catch (e: Exception) {
            Timber.e(e, "Error stopping audio recording")
        }
    }

    /**
     * Disconnect talkback stream
     */
    fun disconnect() {
        stopRecording()
        webSocket?.close(1000, "Client closing")
        webSocket = null
        _isConnected.value = false
    }

    /**
     * Check if connected and recording
     */
    fun isConnected(): Boolean = _isConnected.value
}

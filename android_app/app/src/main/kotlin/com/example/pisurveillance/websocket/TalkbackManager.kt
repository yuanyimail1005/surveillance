package com.example.pisurveillance.websocket

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString.Companion.toByteString
import timber.log.Timber

/**
 * Manages talkback audio WebSocket connection - send microphone audio to server
 * Audio format: 48kHz, mono, 16-bit PCM
 */
class TalkbackManager(
    private val serverUrl: String,
    private val httpClient: OkHttpClient
) {
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        if (webSocket != null) return

        try {
            val wsUrl = serverUrl.replace(Regex("^https://"), "wss://")
                .replace(Regex("^http://"), "ws://")
                .trimEnd('/') + "/ws/talk"

            // Use a specific client with ping to keep connection alive
            val clientWithPing = httpClient.newBuilder()
                .pingInterval(java.time.Duration.ofSeconds(10))
                .build()

            val request = Request.Builder().url(wsUrl).build()

            webSocket = clientWithPing.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Timber.d("🎙 WebSocket talkback connected")
                    _isConnected.value = true
                    _errorMessage.value = null
                    startRecording()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Timber.d("💬 Talkback message: $text")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.e(t, "❌ WebSocket talkback failure")
                    handleConnectionLoss("Failure: ${t.message}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Timber.w("⚠ WebSocket talkback closed: $reason ($code)")
                    handleConnectionLoss(null)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            })
        } catch (e: Exception) {
            Timber.e(e, "Error connecting to talkback stream")
            _errorMessage.value = e.message
            _isConnected.value = false
        }
    }

    private fun handleConnectionLoss(error: String?) {
        if (_isConnected.value) {
            Timber.d("🎙 WebSocket talkback disconnected")
        }
        _isConnected.value = false
        _errorMessage.value = error
        stopRecording()
        webSocket = null
    }

    /**
     * Start recording from microphone and sending to server
     */
    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (_isRecording.value) return

        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize <= 0) {
                Timber.e("Invalid AudioRecord buffer size")
                return
            }

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize.coerceAtLeast(FRAME_SIZE * 4)
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Timber.e("AudioRecord failed to initialize")
                record.release()
                return
            }

            audioRecord = record
            record.startRecording()
            _isRecording.value = true

            recordingJob = recordingScope.launch {
                val buffer = ByteArray(FRAME_SIZE * 2)
                try {
                    while (isActive && _isRecording.value) {
                        val bytesRead = record.read(buffer, 0, buffer.size)
                        if (bytesRead > 0) {
                            val success = webSocket?.send(buffer.toByteString(0, bytesRead)) ?: false
                            if (!success) {
                                Timber.w("WebSocket send failed")
                                break
                            }
                        } else if (bytesRead < 0) {
                            Timber.e("AudioRecord read error: $bytesRead")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error in talkback recording loop")
                } finally {
                    withContext(NonCancellable) {
                        cleanUpAudioRecord(record)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error starting talkback recording")
            _isRecording.value = false
            _errorMessage.value = e.message
        }
    }

    private fun cleanUpAudioRecord(record: AudioRecord?) {
        try {
            if (record != null) {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error releasing AudioRecord")
        }
        if (audioRecord == record) {
            audioRecord = null
            _isRecording.value = false
        }
    }

    /**
     * Stop recording from microphone
     */
    private fun stopRecording() {
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null
    }

    /**
     * Disconnect talkback stream
     */
    fun disconnect() {
        if (webSocket == null && !_isRecording.value) return

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

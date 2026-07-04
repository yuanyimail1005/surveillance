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
 * Manages talkback audio connection - send microphone audio to server via WebSocket.
 * Audio format: 48kHz, mono, 16-bit PCM.
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
        private const val FRAME_SIZE = 2048 // ~42ms at 48kHz
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

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    handleConnectionLoss("Failure: ${t.message}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handleConnectionLoss(null)
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

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (_isRecording.value) return

        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize <= 0) return

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize.coerceAtLeast(FRAME_SIZE * 4)
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
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
                            val data = buffer.copyOfRange(0, bytesRead)
                            val success = webSocket?.send(data.toByteString()) ?: false
                            if (!success) break
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
        }
    }

    private fun cleanUpAudioRecord(record: AudioRecord?) {
        try {
            if (record != null) {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
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

    private fun stopRecording() {
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null
    }

    fun disconnect() {
        stopRecording()
        webSocket?.close(1000, "Client closing")
        webSocket = null
        _isConnected.value = false
    }

    fun isConnected(): Boolean = _isConnected.value || _isRecording.value
}

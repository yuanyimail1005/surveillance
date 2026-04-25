package com.example.pisurveillance.websocket

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import timber.log.Timber

/**
 * Manages audio stream WebSocket connection and playback
 * Audio format: 48kHz, mono, 16-bit PCM
 */
class AudioStreamManager(
    private val serverUrl: String
) {
    private var webSocket: WebSocket? = null
    private val httpClient = OkHttpClient()

    private var audioTrack: AudioTrack? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    /**
     * Connect to audio feed WebSocket and initialize playback
     */
    fun connect() {
        if (webSocket != null) {
            Timber.w("Audio WebSocket already connected")
            return
        }

        try {
            initializeAudioTrack()

            val wsUrl = serverUrl.replace(Regex("^https://"), "wss://")
                .replace(Regex("^http://"), "ws://")
                .trimEnd('/') + "/audio_feed"

            val request = Request.Builder().url(wsUrl).build()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Timber.d("Audio WebSocket connected")
                    _isConnected.value = true
                    _isPlaying.value = true
                    audioTrack?.play()
                    _errorMessage.value = null
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    playAudioFrame(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.e(t, "Audio WebSocket connection failed")
                    _isConnected.value = false
                    _isPlaying.value = false
                    _errorMessage.value = t.message ?: "Connection failed"
                    stopAudioPlayback()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Timber.d("Audio WebSocket closed: $reason")
                    _isConnected.value = false
                    _isPlaying.value = false
                    stopAudioPlayback()
                }
            })
        } catch (e: Exception) {
            Timber.e(e, "Error connecting to audio stream")
            _errorMessage.value = e.message
            _isConnected.value = false
        }
    }

    /**
     * Initialize AudioTrack for playback
     */
    private fun initializeAudioTrack() {
        if (audioTrack != null) {
            return
        }

        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    /**
     * Play incoming audio frame (PCM 16-bit)
     */
    private fun playAudioFrame(data: ByteArray) {
        try {
            audioTrack?.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            Timber.e(e, "Error playing audio frame")
            _errorMessage.value = "Playback error: ${e.message}"
        }
    }

    /**
     * Stop audio playback
     */
    private fun stopAudioPlayback() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            _isPlaying.value = false
        } catch (e: Exception) {
            Timber.e(e, "Error stopping audio playback")
        }
    }

    /**
     * Disconnect from audio feed
     */
    fun disconnect() {
        webSocket?.close(1000, "Client closing")
        webSocket = null
        _isConnected.value = false
        stopAudioPlayback()
    }

    /**
     * Get audio playback volume (0-15)
     */
    fun getVolume(): Float {
        return audioTrack?.getVolume() ?: 1.0f
    }

    /**
     * Set audio playback volume (0.0-1.0)
     */
    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0.0f, 1.0f)
        audioTrack?.setVolume(clampedVolume)
    }

    /**
     * Check if connected and playing
     */
    fun isConnected(): Boolean = _isConnected.value
}

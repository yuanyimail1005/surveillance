package com.example.pisurveillance.websocket

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import timber.log.Timber

/**
 * Manages audio stream WebSocket connection and playback
 * Audio format: 48kHz, mono, 16-bit PCM
 */
class AudioStreamManager(
    private val serverUrl: String,
    private val httpClient: OkHttpClient
) {
    private var webSocket: WebSocket? = null
    private var audioTrack: AudioTrack? = null
    
    private val playbackScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var playbackJob: Job? = null

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

    init {
        startPlaybackLoop()
    }

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = playbackScope.launch {
            for (data in audioChannel) {
                if (!isActive) break
                writeToAudioTrack(data)
            }
        }
    }

    /**
     * Connect to audio feed WebSocket and initialize playback
     */
    fun connect() {
        if (webSocket != null) return
        
        if (playbackJob == null || !playbackJob!!.isActive) {
            startPlaybackLoop()
        }

        try {
            val wsUrl = serverUrl.replace(Regex("^https://"), "wss://")
                .replace(Regex("^http://"), "ws://")
                .trimEnd('/') + "/audio_feed"

            val request = Request.Builder().url(wsUrl).build()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Timber.d("Audio WebSocket connected")
                    _isConnected.value = true
                    _errorMessage.value = null
                    ensureAudioTrackReady()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Non-blocking send to the playback loop
                    audioChannel.trySend(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.e(t, "Audio WebSocket failure")
                    handleConnectionLoss(t.message)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Timber.d("Audio WebSocket closed")
                    handleConnectionLoss(null)
                }
            })
        } catch (e: Exception) {
            Timber.e(e, "Error connecting to audio stream")
            _errorMessage.value = e.message
            _isConnected.value = false
        }
    }

    private fun handleConnectionLoss(error: String?) {
        _isConnected.value = false
        _errorMessage.value = error
        pauseAudioTrack()
        webSocket = null
    }

    private fun ensureAudioTrackReady() {
        try {
            if (audioTrack == null) {
                val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
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
                    bufferSize.coerceAtLeast(4096), // Larger buffer for stability
                    AudioTrack.MODE_STREAM,
                    android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
                )
            }
            
            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.play()
                _isPlaying.value = true
            }
        } catch (e: Exception) {
            Timber.e(e, "Error initializing audio track")
        }
    }

    private fun writeToAudioTrack(data: ByteArray) {
        val track = audioTrack ?: return
        try {
            if (track.state == AudioTrack.STATE_INITIALIZED && _isPlaying.value) {
                // Now safe to use blocking write because we are in a dedicated background job
                track.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error writing to audio track")
        }
    }

    private fun pauseAudioTrack() {
        _isPlaying.value = false
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            Timber.e(e, "Error pausing audio track")
        }
    }

    private fun releaseAudioTrack() {
        _isPlaying.value = false
        val track = audioTrack
        audioTrack = null
        try {
            if (track != null) {
                track.stop()
                track.release()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error releasing audio track")
        }
    }

    /**
     * Disconnect from audio feed
     */
    fun disconnect() {
        webSocket?.close(1000, "Client closing")
        webSocket = null
        _isConnected.value = false
        
        releaseAudioTrack()
        playbackJob?.cancel()
        playbackJob = null
    }

    /**
     * Set audio playback volume (0.0-1.0)
     */
    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0.0f, 1.0f))
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = _isConnected.value
}

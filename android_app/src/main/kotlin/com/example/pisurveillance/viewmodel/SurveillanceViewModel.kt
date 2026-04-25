package com.example.pisurveillance.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pisurveillance.api.ApiClient
import com.example.pisurveillance.api.SurveillanceService
import com.example.pisurveillance.models.*
import com.example.pisurveillance.utils.PreferencesManager
import com.example.pisurveillance.websocket.AudioStreamManager
import com.example.pisurveillance.websocket.TalkbackManager
import com.example.pisurveillance.websocket.VideoStreamManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Main ViewModel for surveillance system
 * Manages API calls, WebSocket connections, and shared state
 */
class SurveillanceViewModel(private val context: Context) : ViewModel() {

    private val preferencesManager = PreferencesManager(context)
    private lateinit var apiService: SurveillanceService

    // Server connection state
    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError

    // Server status
    private val _serverStatus = MutableStateFlow<ServerStatus?>(null)
    val serverStatus: StateFlow<ServerStatus?> = _serverStatus

    // Camera settings
    private val _cameraSettings = MutableStateFlow<CameraSettings?>(null)
    val cameraSettings: StateFlow<CameraSettings?> = _cameraSettings

    private val _selectedCameraDevice = MutableStateFlow<String?>(null)
    val selectedCameraDevice: StateFlow<String?> = _selectedCameraDevice

    // Audio settings
    private val _audioDevices = MutableStateFlow<AudioDevicesResponse?>(null)
    val audioDevices: StateFlow<AudioDevicesResponse?> = _audioDevices

    private val _speakerVolume = MutableStateFlow(50)
    val speakerVolume: StateFlow<Int> = _speakerVolume

    // Stream managers
    private lateinit var videoStreamManager: VideoStreamManager
    private lateinit var audioStreamManager: AudioStreamManager
    private lateinit var talkbackManager: TalkbackManager

    val videoFrames = videoStreamManager::frames.asStateFlow()
    val audioConnected = audioStreamManager::isConnected.asStateFlow()
    val talkbackConnected = talkbackManager::isConnected.asStateFlow()

    /**
     * Initialize ViewModel and load saved server address
     */
    init {
        viewModelScope.launch {
            val savedAddress = preferencesManager.getServerAddress()
            val savedPort = preferencesManager.getServerPort()
            val url = "https://$savedAddress:$savedPort"
            _serverUrl.value = url

            initializeStreamManagers()
            initializeApiService()
        }
    }

    /**
     * Initialize stream managers
     */
    private fun initializeStreamManagers() {
        videoStreamManager = VideoStreamManager(_serverUrl.value)
        audioStreamManager = AudioStreamManager(_serverUrl.value)
        talkbackManager = TalkbackManager(_serverUrl.value)
    }

    /**
     * Initialize API service
     */
    private fun initializeApiService() {
        apiService = ApiClient.createService(
            SurveillanceService::class.java,
            _serverUrl.value,
            context,
            certificatePinning = false  // TODO: implement certificate pinning UI
        )
    }

    /**
     * Connect to server and start all streams
     */
    fun connect() {
        viewModelScope.launch {
            try {
                _connectionError.value = null
                _isConnected.value = true

                // Fetch initial status and settings
                fetchServerStatus()
                fetchCameraSettings()
                fetchAudioDevices()
                fetchSpeakerVolume()

                // Start WebSocket streams
                videoStreamManager.connect()
                audioStreamManager.connect()
            } catch (e: Exception) {
                Timber.e(e, "Connection failed")
                _connectionError.value = e.message ?: "Connection failed"
                _isConnected.value = false
            }
        }
    }

    /**
     * Disconnect from server and stop all streams
     */
    fun disconnect() {
        videoStreamManager.disconnect()
        audioStreamManager.disconnect()
        talkbackManager.disconnect()
        _isConnected.value = false
    }

    /**
     * Fetch current server status
     */
    suspend fun fetchServerStatus() {
        try {
            val response = apiService.getStatus()
            if (response.isSuccessful) {
                _serverStatus.value = response.body()
                _connectionError.value = null
            } else {
                _connectionError.value = "Status: ${response.code()}"
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching server status")
            _connectionError.value = e.message
        }
    }

    /**
     * Fetch camera settings and available devices
     */
    suspend fun fetchCameraSettings() {
        try {
            val response = apiService.getCameraSettings()
            if (response.isSuccessful) {
                val settings = response.body()
                _cameraSettings.value = settings
                if (settings != null) {
                    _selectedCameraDevice.value = settings.selectedDevice
                }
                _connectionError.value = null
            } else {
                _connectionError.value = "Camera settings: ${response.code()}"
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching camera settings")
            _connectionError.value = e.message
        }
    }

    /**
     * Update camera settings
     */
    fun updateCameraSettings(
        width: Int? = null,
        height: Int? = null,
        fps: Int? = null,
        device: String? = null
    ) {
        viewModelScope.launch {
            try {
                val request = CameraSettingsRequest(
                    width = width,
                    height = height,
                    fps = fps,
                    camera_device = device
                )
                val response = apiService.updateCameraSettings(request)
                if (response.isSuccessful) {
                    fetchCameraSettings()
                } else {
                    _connectionError.value = "Update failed: ${response.code()}"
                }
            } catch (e: Exception) {
                Timber.e(e, "Error updating camera settings")
                _connectionError.value = e.message
            }
        }
    }

    /**
     * Fetch speaker volume
     */
    private suspend fun fetchSpeakerVolume() {
        try {
            val response = apiService.getSpeakerVolume()
            if (response.isSuccessful) {
                val volume = response.body()?.volumePercent ?: 50
                _speakerVolume.value = volume
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching speaker volume")
        }
    }

    /**
     * Set speaker volume
     */
    fun setSpeakerVolume(volumePercent: Int) {
        viewModelScope.launch {
            try {
                val clampedVolume = volumePercent.coerceIn(0, 100)
                val response = apiService.setSpeakerVolume(VolumeRequest(clampedVolume))
                if (response.isSuccessful) {
                    _speakerVolume.value = clampedVolume
                }
            } catch (e: Exception) {
                Timber.e(e, "Error setting speaker volume")
            }
        }
    }

    /**
     * Fetch available audio devices
     */
    private suspend fun fetchAudioDevices() {
        try {
            val response = apiService.getAudioDevices()
            if (response.isSuccessful) {
                _audioDevices.value = response.body()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching audio devices")
        }
    }

    /**
     * Select audio device
     */
    fun selectAudioDevice(captureDevice: String? = null, playbackDevice: String? = null) {
        viewModelScope.launch {
            try {
                val request = SelectAudioDeviceRequest(
                    capture_device = captureDevice,
                    playback_device = playbackDevice
                )
                val response = apiService.selectAudioDevice(request)
                if (response.isSuccessful) {
                    fetchAudioDevices()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error selecting audio device")
            }
        }
    }

    /**
     * Start talkback
     */
    fun startTalkback() {
        talkbackManager.connect()
    }

    /**
     * Stop talkback
     */
    fun stopTalkback() {
        talkbackManager.disconnect()
    }

    /**
     * Update server connection details
     */
    fun updateServerConnection(address: String, port: Int = 5000) {
        viewModelScope.launch {
            try {
                preferencesManager.setServerAddress(address)
                preferencesManager.setServerPort(port)
                val url = "https://$address:$port"
                _serverUrl.value = url
                ApiClient.clearCache()
                initializeStreamManagers()
                initializeApiService()
                disconnect()
            } catch (e: Exception) {
                Timber.e(e, "Error updating server connection")
                _connectionError.value = e.message
            }
        }
    }
}

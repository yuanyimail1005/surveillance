package com.example.pisurveillance.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.pisurveillance.api.ApiClient
import com.example.pisurveillance.api.SurveillanceService
import com.example.pisurveillance.models.*
import com.example.pisurveillance.utils.PreferencesManager
import com.example.pisurveillance.websocket.AudioStreamManager
import com.example.pisurveillance.websocket.TalkbackManager
import com.example.pisurveillance.websocket.VideoFrame
import com.example.pisurveillance.websocket.VideoStreamManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.FileOutputStream

/**
 * Main ViewModel for surveillance system
 * Manages API calls, WebSocket connections, and shared state
 */
class SurveillanceViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)
    private var apiService: SurveillanceService? = null

    // Recording state
    private val _isRecordingVideo = MutableLiveData(false)
    val isRecordingVideo: LiveData<Boolean> = _isRecordingVideo
    
    private var videoFileStream: FileOutputStream? = null
    private var currentVideoFileName: String? = null

    // Server connection state
    private val _serverUrl = MutableLiveData("")
    val serverUrl: LiveData<String> = _serverUrl

    private val _serverAddress = MutableLiveData("")
    val serverAddress: LiveData<String> = _serverAddress

    private val _serverPort = MutableLiveData(5000)
    val serverPort: LiveData<Int> = _serverPort

    private val _recentServers = MutableLiveData<List<String>>(emptyList())
    val recentServers: LiveData<List<String>> = _recentServers

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private val _connectionError = MutableLiveData<String?>(null)
    val connectionError: LiveData<String?> = _connectionError

    // Server status
    private val _serverStatus = MutableLiveData<ServerStatus?>(null)
    val serverStatus: LiveData<ServerStatus?> = _serverStatus

    // Camera settings
    private val _cameraSettings = MutableLiveData<CameraSettings?>(null)
    val cameraSettings: LiveData<CameraSettings?> = _cameraSettings

    private val _selectedCameraDevice = MutableLiveData<String?>(null)
    val selectedCameraDevice: LiveData<String?> = _selectedCameraDevice

    // Audio settings
    private val _audioDevices = MutableLiveData<AudioDevicesResponse?>(null)
    val audioDevices: LiveData<AudioDevicesResponse?> = _audioDevices

    private val _speakerVolume = MutableLiveData(50)
    val speakerVolume: LiveData<Int> = _speakerVolume

    // Stream managers
    private var videoStreamManager: VideoStreamManager? = null
    private var audioStreamManager: AudioStreamManager? = null
    private var talkbackManager: TalkbackManager? = null

    // Shared LiveData for UI
    private val _videoFrames = MutableLiveData<VideoFrame?>(null)
    val videoFrames: LiveData<VideoFrame?> = _videoFrames

    private val _audioConnected = MutableLiveData(false)
    val audioConnected: LiveData<Boolean> = _audioConnected

    private val _talkbackConnected = MutableLiveData(false)
    val talkbackConnected: LiveData<Boolean> = _talkbackConnected

    private val _faceData = MutableLiveData<FaceAiData?>(null)
    val faceData: LiveData<FaceAiData?> = _faceData

    private val _faceStatus = MutableLiveData<FaceStatusResponse?>(null)
    val faceStatus: LiveData<FaceStatusResponse?> = _faceStatus

    private var initializationJob: Job? = null
    private var observationJob: Job? = null

    /**
     * Initialize ViewModel and load saved server address
     */
    init {
        initializationJob = viewModelScope.launch {
            try {
                val savedAddress = preferencesManager.getServerAddress()
                val savedPort = preferencesManager.getServerPort()
                _serverAddress.value = savedAddress
                _serverPort.value = savedPort
                _recentServers.value = preferencesManager.getRecentServers()
                val url = cleanUrl(savedAddress, savedPort)
                _serverUrl.value = url

                initializeStreamManagers(url)
                initializeApiService(url)
                
                observeManagers()
            } catch (e: Exception) {
                Timber.e(e, "Initialization failed")
            }
        }
    }

    private fun cleanUrl(address: String, port: Int): String {
        var cleanAddress = address.replace(Regex("^https?://"), "").trimEnd('/')
        cleanAddress = cleanAddress.split(":")[0]
        return "https://$cleanAddress:$port"
    }

    private fun observeManagers() {
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            launch {
                videoStreamManager?.frames?.collect { frame ->
                    _videoFrames.postValue(frame)
                    // If recording, save the raw data to the stream
                    if (frame != null && _isRecordingVideo.value == true) {
                        saveFrameToRecording(frame.rawData)
                    }
                }
            }
            launch {
                videoStreamManager?.faceData?.collect { data ->
                    _faceData.postValue(data)
                    
                    // Update configuration status ONLY if the WebSocket message contains 
                    // actual configuration data (enabled, available, backend).
                    // Many WebSocket pushes only contain detection results to save bandwidth.
                    if (data != null && data.backend != null) {
                        val currentStatus = _faceStatus.value
                        _faceStatus.postValue(FaceStatusResponse(
                            enabled = data.enabled,
                            available = data.available,
                            backend = data.backend,
                            message = data.message ?: currentStatus?.message,
                            knownFacesCount = data.knownFacesCount ?: currentStatus?.knownFacesCount,
                            broadcastFrameSeq = data.broadcastFrameSeq,
                            result = data.result
                        ))
                    }
                }
            }
            launch {
                audioStreamManager?.isConnected?.collect { _audioConnected.postValue(it) }
            }
            launch {
                talkbackManager?.isConnected?.collect { _talkbackConnected.postValue(it) }
            }
        }
    }

    private fun initializeStreamManagers(url: String) {
        val context = getApplication<Application>()
        val client = ApiClient.getOkHttpClient(context, certificatePinning = false)
        
        videoStreamManager = VideoStreamManager(url, client)
        audioStreamManager = AudioStreamManager(url, client)
        talkbackManager = TalkbackManager(url, client)
    }

    private fun initializeApiService(url: String) {
        apiService = ApiClient.createService(
            SurveillanceService::class.java,
            url,
            getApplication(),
            certificatePinning = false
        )
    }

    /**
     * Connect to server and start all streams
     */
    fun connect() {
        viewModelScope.launch {
            initializationJob?.join()
            
            try {
                _connectionError.value = null
                _isConnected.value = true

                fetchServerStatus()
                fetchCameraSettings()
                fetchAudioDevices()
                fetchSpeakerVolume()
                fetchFaceStatus()

                videoStreamManager?.connect()
                audioStreamManager?.connect()
            } catch (e: Exception) {
                Timber.e(e, "Connection failed")
                _connectionError.postValue(e.message ?: "Connection failed")
                _isConnected.postValue(false)
            }
        }
    }

    /**
     * Disconnect from server and stop all streams
     */
    fun disconnect() {
        videoStreamManager?.disconnect()
        audioStreamManager?.disconnect()
        talkbackManager?.disconnect()
        _isConnected.value = false
    }

    /**
     * Fetch current server status
     */
    suspend fun fetchServerStatus() {
        try {
            val service = apiService ?: return
            val response = service.getStatus()
            if (response.isSuccessful) {
                _serverStatus.postValue(response.body())
                _connectionError.postValue(null)
            } else {
                _connectionError.postValue("Status: ${response.code()}")
            }
            
            // Also refresh face status during regular status updates
            fetchFaceStatus()
        } catch (e: Exception) {
            Timber.e(e, "Error fetching server status")
            _connectionError.postValue(e.message)
        }
    }

    /**
     * Fetch camera settings and available devices
     */
    suspend fun fetchCameraSettings() {
        try {
            val service = apiService ?: return
            val response = service.getCameraSettings()
            if (response.isSuccessful) {
                val settings = response.body()
                _cameraSettings.postValue(settings)
                if (settings != null) {
                    _selectedCameraDevice.postValue(settings.selectedDevice)
                }
                _connectionError.postValue(null)
            } else {
                _connectionError.postValue("Camera settings: ${response.code()}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching camera settings")
            _connectionError.postValue(e.message)
        }
    }

    /**
     * Update camera settings
     */
    fun updateCameraSettings(
        width: Int,
        height: Int,
        fps: Int,
        device: String? = null
    ) {
        viewModelScope.launch {
            try {
                val service = apiService ?: return@launch
                val request = CameraSettingsRequest(
                    width = width,
                    height = height,
                    fps = fps,
                    camera_device = device
                )
                val response = service.updateCameraSettings(request)
                if (response.isSuccessful) {
                    fetchCameraSettings()
                } else {
                    _connectionError.postValue("Update failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error updating camera settings")
                _connectionError.postValue(e.message)
            }
        }
    }

    /**
     * Fetch speaker volume
     */
    private suspend fun fetchSpeakerVolume() {
        try {
            val service = apiService ?: return
            val response = service.getSpeakerVolume()
            if (response.isSuccessful) {
                val volume = response.body()?.volumePercent ?: 50
                _speakerVolume.postValue(volume)
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
                val service = apiService ?: return@launch
                val clampedVolume = volumePercent.coerceIn(0, 100)
                val response = service.setSpeakerVolume(VolumeRequest(clampedVolume))
                if (response.isSuccessful) {
                    _speakerVolume.postValue(clampedVolume)
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
            val service = apiService ?: return
            val response = service.getAudioDevices()
            if (response.isSuccessful) {
                _audioDevices.postValue(response.body())
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
                val service = apiService ?: return@launch
                val request = SelectAudioDeviceRequest(
                    capture_device = captureDevice,
                    playback_device = playbackDevice
                )
                val response = service.selectAudioDevice(request)
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
        talkbackManager?.connect()
    }

    /**
     * Stop talkback
     */
    fun stopTalkback() {
        talkbackManager?.disconnect()
    }

    /**
     * Start video recording
     */
    fun startVideoRecording() {
        if (_isRecordingVideo.value == true) return
        
        try {
            val name = "recording_${System.currentTimeMillis()}.mjpeg"
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val file = java.io.File(downloadsDir, name)
            videoFileStream = FileOutputStream(file)
            currentVideoFileName = name
            _isRecordingVideo.postValue(true)
            Timber.d("Recording started: $name")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start recording")
        }
    }

    /**
     * Stop video recording
     */
    fun stopVideoRecording() {
        if (_isRecordingVideo.value == false) return
        
        _isRecordingVideo.postValue(false)
        try {
            videoFileStream?.flush()
            videoFileStream?.close()
            Timber.d("Recording stopped: $currentVideoFileName")
        } catch (e: Exception) {
            Timber.e(e, "Error closing video stream")
        }
        videoFileStream = null
    }

    private fun saveFrameToRecording(data: ByteArray) {
        try {
            videoFileStream?.write(data)
        } catch (e: Exception) {
            Timber.e(e, "Error writing frame to file")
            stopVideoRecording()
        }
    }

    /**
     * Fetch face recognition status
     */
    fun fetchFaceStatus() {
        viewModelScope.launch {
            try {
                val service = apiService ?: return@launch
                val response = service.getFaceStatus()
                if (response.isSuccessful) {
                    _faceStatus.postValue(response.body())
                }
            } catch (e: Exception) {
                Timber.e(e, "Error fetching face status")
            }
        }
    }

    /**
     * Update face recognition settings
     */
    fun updateFaceSettings(enabled: Boolean, backend: String = "auto") {
        viewModelScope.launch {
            try {
                val service = apiService ?: return@launch
                val request = FaceSettingsRequest(enabled, backend)
                val response = service.updateFaceSettings(request)
                if (response.isSuccessful) {
                    fetchFaceStatus()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error updating face settings")
            }
        }
    }

    /**
     * Update server connection details
     */
    fun updateServerConnection(address: String, port: Int = 5000) {
        val newJob = viewModelScope.launch {
            initializationJob?.join()
            try {
                val cleanAddress = address.replace(Regex("^https?://"), "").trimEnd('/').split(":")[0]
                preferencesManager.setServerAddress(cleanAddress)
                preferencesManager.setServerPort(port)
                preferencesManager.addRecentServer(cleanAddress)
                
                _serverAddress.postValue(cleanAddress)
                _serverPort.postValue(port)
                _recentServers.postValue(preferencesManager.getRecentServers())
                
                val url = "https://$cleanAddress:$port"
                _serverUrl.postValue(url)
                
                ApiClient.clearCache()
                initializeStreamManagers(url)
                initializeApiService(url)
                observeManagers()
                disconnect()
            } catch (e: Exception) {
                Timber.e(e, "Error updating server connection")
                _connectionError.postValue(e.message)
            }
        }
        initializationJob = newJob
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        observationJob?.cancel()
    }
}

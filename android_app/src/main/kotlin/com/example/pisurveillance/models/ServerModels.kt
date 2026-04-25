package com.example.pisurveillance.models

import com.google.gson.annotations.SerializedName

/**
 * Represents the current state of the surveillance server
 */
data class ServerStatus(
    @SerializedName("camera")
    val cameraRunning: Boolean,
    
    @SerializedName("audio")
    val audioRunning: Boolean,
    
    @SerializedName("queue_size")
    val queueSize: Int,
    
    @SerializedName("camera_device")
    val cameraDevice: String,
    
    @SerializedName("camera_source_type")
    val cameraSourceType: String,  // "V4L2", "CSI", or "Unknown"
    
    @SerializedName("camera_device_preference")
    val cameraDevicePreference: String,
    
    @SerializedName("camera_width")
    val cameraWidth: Int,
    
    @SerializedName("camera_height")
    val cameraHeight: Int,
    
    @SerializedName("camera_fps")
    val cameraFps: Int,
    
    @SerializedName("audio_player_nice")
    val audioPlayerNice: Int,
    
    @SerializedName("speaker_volume")
    val speakerVolume: Int,
    
    @SerializedName("speaker_control")
    val speakerControl: String?
) {
    fun isHealthy(): Boolean = cameraRunning && audioRunning
}

/**
 * Camera settings and available camera devices
 */
data class CameraSettings(
    @SerializedName("width")
    val width: Int,
    
    @SerializedName("height")
    val height: Int,
    
    @SerializedName("fps")
    val fps: Int,
    
    @SerializedName("available_devices")
    val availableDevices: List<CameraDevice>,
    
    @SerializedName("camera_device")
    val selectedDevice: String?,
    
    @SerializedName("camera_source_type")
    val sourceType: String?
)

/**
 * Individual camera device information
 */
data class CameraDevice(
    @SerializedName("path")
    val path: String,
    
    @SerializedName("name")
    val name: String,
    
    @SerializedName("type")
    val type: String  // "V4L2" or "CSI"
)

/**
 * Audio device information
 */
data class AudioDevice(
    @SerializedName("id")
    val id: String,
    
    @SerializedName("name")
    val name: String,
    
    @SerializedName("kind")
    val kind: String  // "source" (input), "sink" (output), or "default"
)

/**
 * Audio devices response
 */
data class AudioDevicesResponse(
    @SerializedName("capture_devices")
    val captureDevices: List<AudioDevice>,
    
    @SerializedName("playback_devices")
    val playbackDevices: List<AudioDevice>,
    
    @SerializedName("selected_capture_device")
    val selectedCaptureDevice: String?,
    
    @SerializedName("selected_playback_device")
    val selectedPlaybackDevice: String?
)

/**
 * Request to update camera settings
 */
data class CameraSettingsRequest(
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val camera_device: String? = null
)

/**
 * Request to update speaker volume
 */
data class VolumeRequest(
    val volume_percent: Int
)

/**
 * Response for speaker volume
 */
data class VolumeResponse(
    @SerializedName("volume_percent")
    val volumePercent: Int,
    
    @SerializedName("speaker_control")
    val speakerControl: String?
)

/**
 * Request to select audio device
 */
data class SelectAudioDeviceRequest(
    val capture_device: String? = null,
    val playback_device: String? = null
)

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
    
    @SerializedName("available_camera_devices")
    val availableDevices: List<CameraDevice>?,

    @SerializedName("allowed_resolutions")
    val allowedResolutions: List<Resolution>?,
    
    @SerializedName("camera_device")
    val selectedDevice: String?,
    
    @SerializedName("camera_source_type")
    val sourceType: String?
)

/**
 * Resolution information
 */
data class Resolution(
    @SerializedName("width")
    val width: Int,
    @SerializedName("height")
    val height: Int
) {
    override fun toString(): String = "${width}x${height}"
}

/**
 * Individual camera device information
 */
data class CameraDevice(
    @SerializedName("path")
    val path: String,
    
    @SerializedName("name")
    val name: String,
    
    @SerializedName("supported")
    val supported: Boolean = true,
    
    @SerializedName("reason")
    val reason: String? = null
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
    @SerializedName("microphones")
    val captureDevices: List<AudioDevice>?,
    
    @SerializedName("speakers")
    val playbackDevices: List<AudioDevice>?,
    
    @SerializedName("selected_microphone")
    val selectedCaptureDevice: String?,
    
    @SerializedName("selected_speaker")
    val selectedPlaybackDevice: String?
)

/**
 * Request to update camera settings
 */
data class CameraSettingsRequest(
    val width: Int,
    val height: Int,
    val fps: Int,
    val camera_device: String? = null
)

/**
 * Request to update speaker volume
 */
data class VolumeRequest(
    val volume: Int
)

/**
 * Response for speaker volume
 */
data class VolumeResponse(
    @SerializedName("volume")
    val volumePercent: Int,
    
    @SerializedName("control")
    val speakerControl: String?
)

/**
 * Request to select audio device
 */
data class SelectAudioDeviceRequest(
    val capture_device: String? = null,
    val playback_device: String? = null
)

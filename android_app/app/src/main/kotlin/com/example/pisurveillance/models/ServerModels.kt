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
 * Metadata for upcoming video frame
 */
data class FrameMeta(
    @SerializedName("type")
    val type: String = "frame_meta",
    @SerializedName("broadcast_frame_seq")
    val broadcastFrameSeq: Long
)

/**
 * Individual face detection result
 */
data class FaceDetection(
    @SerializedName("left")
    val left: Int,
    @SerializedName("top")
    val top: Int,
    @SerializedName("right")
    val right: Int,
    @SerializedName("bottom")
    val bottom: Int,
    @SerializedName("name")
    val name: String?,
    @SerializedName("confidence")
    val confidence: Double?
)

/**
 * Result of face detection on a frame
 */
data class FaceDetectionResult(
    @SerializedName("image_width")
    val imageWidth: Int,
    @SerializedName("image_height")
    val imageHeight: Int,
    @SerializedName("frame_index")
    val frameIndex: Long?,
    @SerializedName("broadcast_frame_seq")
    val broadcastFrameSeq: Long?,
    @SerializedName("faces")
    val faces: List<FaceDetection>
)

/**
 * Combined face AI data pushed from server
 */
data class FaceAiData(
    @SerializedName("type")
    val type: String = "face_data",
    @SerializedName("enabled")
    val enabled: Boolean,
    @SerializedName("available")
    val available: Boolean,
    @SerializedName("backend")
    val backend: String?,
    @SerializedName("requested_backend")
    val requestedBackend: String?,
    @SerializedName("supported_backends")
    val supportedBackends: List<FaceBackend>?,
    @SerializedName("message")
    val message: String?,
    @SerializedName("known_faces_count")
    val knownFacesCount: Int?,
    @SerializedName("broadcast_frame_seq")
    val broadcastFrameSeq: Long?,
    @SerializedName("result")
    val result: FaceDetectionResult?
)

/**
 * Face recognition backend option
 */
data class FaceBackend(
    @SerializedName("id")
    val id: String,
    @SerializedName("label")
    val label: String
)

/**
 * Face recognition settings
 */
data class FaceStatusResponse(
    @SerializedName("enabled")
    val enabled: Boolean,
    @SerializedName("available")
    val available: Boolean,
    @SerializedName("backend")
    val backend: String?,
    @SerializedName("requested_backend")
    val requestedBackend: String?,
    @SerializedName("supported_backends")
    val supportedBackends: List<FaceBackend>?,
    @SerializedName("message")
    val message: String?,
    @SerializedName("known_faces_count")
    val knownFacesCount: Int?,
    @SerializedName("broadcast_frame_seq")
    val broadcastFrameSeq: Long?,
    @SerializedName("result")
    val result: FaceDetectionResult?
)

/**
 * Request to update face settings
 */
data class FaceSettingsRequest(
    val enabled: Boolean,
    val backend: String? = "auto"
)

/**
 * Response from face settings update
 */
data class FaceSettingsResponse(
    @SerializedName("status")
    val status: String,
    @SerializedName("enabled")
    val enabled: Boolean,
    @SerializedName("backend")
    val backend: String?,
    @SerializedName("requested_backend")
    val requestedBackend: String?,
    @SerializedName("supported_backends")
    val supportedBackends: List<FaceBackend>?,
    @SerializedName("available")
    val available: Boolean,
    @SerializedName("message")
    val message: String?
)

/**
 * Request to select audio device
 */
data class SelectAudioDeviceRequest(
    val capture_device: String? = null,
    val playback_device: String? = null
)

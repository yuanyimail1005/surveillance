package com.example.pisurveillance.api

import com.example.pisurveillance.models.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit service interface for surveillance server API
 */
interface SurveillanceService {

    /**
     * Get current server status
     */
    @GET("/status")
    suspend fun getStatus(): Response<ServerStatus>

    /**
     * Get camera settings and available devices
     */
    @GET("/camera_settings")
    suspend fun getCameraSettings(): Response<CameraSettings>

    /**
     * Update camera settings
     */
    @POST("/camera_settings")
    suspend fun updateCameraSettings(
        @Body request: CameraSettingsRequest
    ): Response<CameraSettings>

    /**
     * Get current speaker volume
     */
    @GET("/speaker_volume")
    suspend fun getSpeakerVolume(): Response<VolumeResponse>

    /**
     * Set speaker volume
     */
    @POST("/speaker_volume")
    suspend fun setSpeakerVolume(
        @Body request: VolumeRequest
    ): Response<VolumeResponse>

    /**
     * Get available audio devices (capture and playback)
     */
    @GET("/server_audio_devices")
    suspend fun getAudioDevices(): Response<AudioDevicesResponse>

    /**
     * Select audio input/output device
     */
    @POST("/server_audio_devices/select")
    suspend fun selectAudioDevice(
        @Body request: SelectAudioDeviceRequest
    ): Response<AudioDevicesResponse>

    /**
     * Get face recognition status and settings
     */
    @GET("/face_settings")
    suspend fun getFaceStatus(): Response<FaceStatusResponse>

    /**
     * Update face recognition settings
     */
    @POST("/face_settings")
    suspend fun updateFaceSettings(
        @Body request: FaceSettingsRequest
    ): Response<FaceSettingsResponse>
}

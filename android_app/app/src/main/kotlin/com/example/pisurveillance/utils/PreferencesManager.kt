package com.example.pisurveillance.utils

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Manager for app preferences using Android DataStore
 */
class PreferencesManager(private val context: Context) {

    companion object {
        private val Context.dataStore by preferencesDataStore(name = "surveillance_prefs")

        // Preference keys
        private val SERVER_ADDRESS = stringPreferencesKey("server_address")
        private val SERVER_PORT = intPreferencesKey("server_port")
        private val CERTIFICATE_PEM = stringPreferencesKey("certificate_pem")
        private val CERTIFICATE_FINGERPRINT = stringPreferencesKey("certificate_fingerprint")
        private val CERTIFICATE_PINNING_ENABLED = booleanPreferencesKey("certificate_pinning_enabled")
        private val LAST_CAMERA_DEVICE = stringPreferencesKey("last_camera_device")
        private val LAST_CAMERA_WIDTH = intPreferencesKey("last_camera_width")
        private val LAST_CAMERA_HEIGHT = intPreferencesKey("last_camera_height")
        private val LAST_CAMERA_FPS = intPreferencesKey("last_camera_fps")
        private val LAST_SPEAKER_VOLUME = intPreferencesKey("last_speaker_volume")
        private val LAST_CAPTURE_DEVICE = stringPreferencesKey("last_capture_device")
        private val LAST_PLAYBACK_DEVICE = stringPreferencesKey("last_playback_device")
        private val TALKBACK_ENABLED = booleanPreferencesKey("talkback_enabled")
        private val RECENT_SERVERS = stringPreferencesKey("recent_servers")
    }

    // Recent Servers
    suspend fun getRecentServers(): List<String> {
        val serversString = context.dataStore.data.map { prefs ->
            prefs[RECENT_SERVERS] ?: ""
        }.first()
        return if (serversString.isEmpty()) emptyList() else serversString.split(",")
    }

    suspend fun addRecentServer(address: String) {
        if (address.isEmpty()) return
        val currentServers = getRecentServers().toMutableList()
        currentServers.remove(address) // Remove if already exists to move to top
        currentServers.add(0, address)
        val limitedServers = currentServers.take(5)
        context.dataStore.edit { prefs ->
            prefs[RECENT_SERVERS] = limitedServers.joinToString(",")
        }
    }

    // Server configuration
    suspend fun setServerAddress(address: String) {
        context.dataStore.edit { prefs ->
            prefs[SERVER_ADDRESS] = address
        }
    }

    suspend fun getServerAddress(): String {
        return context.dataStore.data.map { prefs ->
            prefs[SERVER_ADDRESS] ?: "192.168.1.100"
        }.first()
    }

    fun getServerAddressFlow(): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[SERVER_ADDRESS] ?: "192.168.1.100"
        }
    }

    suspend fun setServerPort(port: Int) {
        context.dataStore.edit { prefs ->
            prefs[SERVER_PORT] = port
        }
    }

    suspend fun getServerPort(): Int {
        return context.dataStore.data.map { prefs ->
            prefs[SERVER_PORT] ?: 5000
        }.first()
    }

    fun getServerPortFlow(): Flow<Int> {
        return context.dataStore.data.map { prefs ->
            prefs[SERVER_PORT] ?: 5000
        }
    }

    // Certificate management
    suspend fun setCertificatePem(pem: String) {
        context.dataStore.edit { prefs ->
            prefs[CERTIFICATE_PEM] = pem
        }
    }

    suspend fun getCertificatePem(): String {
        return context.dataStore.data.map { prefs ->
            prefs[CERTIFICATE_PEM] ?: ""
        }.first()
    }

    fun getCertificatePemFlow(): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[CERTIFICATE_PEM] ?: ""
        }
    }

    suspend fun setCertificateFingerprint(fingerprint: String) {
        context.dataStore.edit { prefs ->
            prefs[CERTIFICATE_FINGERPRINT] = fingerprint
        }
    }

    suspend fun getCertificateFingerprint(): String {
        return context.dataStore.data.map { prefs ->
            prefs[CERTIFICATE_FINGERPRINT] ?: ""
        }.first()
    }

    fun getCertificateFingerprintFlow(): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[CERTIFICATE_FINGERPRINT] ?: ""
        }
    }

    suspend fun setCertificatePinningEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[CERTIFICATE_PINNING_ENABLED] = enabled
        }
    }

    suspend fun isCertificatePinningEnabled(): Boolean {
        return context.dataStore.data.map { prefs ->
            prefs[CERTIFICATE_PINNING_ENABLED] ?: false
        }.first()
    }

    fun isCertificatePinningEnabledFlow(): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[CERTIFICATE_PINNING_ENABLED] ?: false
        }
    }

    // Camera settings
    suspend fun setLastCameraDevice(device: String) {
        context.dataStore.edit { prefs ->
            prefs[LAST_CAMERA_DEVICE] = device
        }
    }

    fun getLastCameraDeviceFlow(): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[LAST_CAMERA_DEVICE] ?: ""
        }
    }

    suspend fun setLastCameraResolution(width: Int, height: Int) {
        context.dataStore.edit { prefs ->
            prefs[LAST_CAMERA_WIDTH] = width
            prefs[LAST_CAMERA_HEIGHT] = height
        }
    }

    fun getLastCameraResolutionFlow(): Flow<Pair<Int, Int>> {
        return context.dataStore.data.map { prefs ->
            val width = prefs[LAST_CAMERA_WIDTH] ?: 1920
            val height = prefs[LAST_CAMERA_HEIGHT] ?: 1080
            Pair(width, height)
        }
    }

    suspend fun setLastCameraFps(fps: Int) {
        context.dataStore.edit { prefs ->
            prefs[LAST_CAMERA_FPS] = fps
        }
    }

    fun getLastCameraFpsFlow(): Flow<Int> {
        return context.dataStore.data.map { prefs ->
            prefs[LAST_CAMERA_FPS] ?: 25
        }
    }

    // Audio settings
    suspend fun setLastSpeakerVolume(volume: Int) {
        context.dataStore.edit { prefs ->
            prefs[LAST_SPEAKER_VOLUME] = volume
        }
    }

    fun getLastSpeakerVolumeFlow(): Flow<Int> {
        return context.dataStore.data.map { prefs ->
            prefs[LAST_SPEAKER_VOLUME] ?: 50
        }
    }

    suspend fun setLastCaptureDevice(device: String) {
        context.dataStore.edit { prefs ->
            prefs[LAST_CAPTURE_DEVICE] = device
        }
    }

    suspend fun setLastPlaybackDevice(device: String) {
        context.dataStore.edit { prefs ->
            prefs[LAST_PLAYBACK_DEVICE] = device
        }
    }

    fun getLastCaptureDeviceFlow(): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[LAST_CAPTURE_DEVICE] ?: "@DEFAULT_SOURCE@"
        }
    }

    fun getLastPlaybackDeviceFlow(): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[LAST_PLAYBACK_DEVICE] ?: "@DEFAULT_SINK@"
        }
    }

    // Talkback
    suspend fun setTalkbackEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[TALKBACK_ENABLED] = enabled
        }
    }

    fun isTalkbackEnabledFlow(): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[TALKBACK_ENABLED] ?: false
        }
    }
}

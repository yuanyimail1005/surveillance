# Architecture & Implementation Guide

## Overview

The Pi Surveillance Android client follows a clean **MVVM (Model-View-ViewModel)** architecture with clear separation of concerns:

```
UI Layer (Fragments)
    ↓
ViewModel (SurveillanceViewModel)
    ↓
Repository Pattern
    ├→ API Service (Retrofit)
    └→ WebSocket Managers (OkHttp)
        ├→ VideoStreamManager
        ├→ AudioStreamManager
        └→ TalkbackManager
```

## Component Responsibilities

### UI Layer (Fragments)
Located in `ui/fragments/`:

- **VideoFragment**: Displays live video and handles talkback button
- **SettingsFragment**: Camera, audio, and server configuration UI
- **StatusFragment**: System status monitoring dashboard

**Key Pattern**: Fragments observe ViewModel StateFlow values and react to changes reactively.

### ViewModel (`SurveillanceViewModel`)
Central orchestrator that:
- Manages API service lifecycle
- Orchestrates WebSocket connections
- Exposes StateFlow observables for UI
- Handles user interactions

**Key Pattern**: All network operations run in `viewModelScope` coroutines to survive configuration changes.

### API Service (`SurveillanceService`)
Retrofit interface defining:
- REST endpoints (GET /status, POST /camera_settings, etc.)
- Request/response models (data classes)
- Suspend functions for async execution

**Key Pattern**: All API calls are suspend functions returning `Response<T>` for proper error handling.

### WebSocket Managers
Three dedicated managers handle persistent connections:

#### VideoStreamManager
```kotlin
// Connects to /video_feed WebSocket
// Receives binary MJPEG frames
// Decodes frames to Bitmap
// Emits via _frames StateFlow
```

**Flow**:
1. Connect → WebSocket opens
2. Receive → Binary MJPEG frame arrives
3. Decode → BitmapFactory.decodeByteArray()
4. Emit → _frames.value = bitmap
5. UI observes → ImageView updates

#### AudioStreamManager
```kotlin
// Connects to /audio_feed WebSocket
// Receives binary PCM 16-bit audio
// Plays via AndroidAudioTrack
```

**Flow**:
1. Connect → WebSocket opens, AudioTrack created
2. Receive → Binary PCM audio arrives
3. Write → audioTrack.write(data)
4. Play → Automatic once stream starts
5. Status → _isPlaying StateFlow reflects state

#### TalkbackManager
```kotlin
// Connects to /ws/talk WebSocket
// Records from device microphone
// Sends PCM frames to server
```

**Flow**:
1. Connect → WebSocket opens, AudioRecord starts
2. Read → AudioRecord reads microphone data
3. Send → webSocket.send(ByteString)
4. Server receives → Plays through server speakers
5. Disconnect → Stops recording

### Data Models (`models/`)
Data classes for JSON serialization:

```kotlin
// Server responses
data class ServerStatus(...)  // /status response
data class CameraSettings(...) // /camera_settings response
data class AudioDevice(...) // Audio device info

// Requests
data class CameraSettingsRequest(...)
data class VolumeRequest(...)
```

**Key Pattern**: `@SerializedName` annotations map JSON fields to Kotlin properties.

### Utils

#### `PreferencesManager`
Uses Android DataStore for persistent storage:
- Server address/port
- Certificate PEM
- Camera device preferences
- Audio device selections

**Key Pattern**: All preferences exposed as Flow for reactive updates.

#### `SslUtils`
Handles SSL/TLS certificate pinning:
- Load custom CA certificates
- Create SSLSocketFactory with pinning
- Store/validate certificate fingerprints

## Data Flow Patterns

### Connecting to Server
```
User enters server address
    ↓
SettingsFragment.connectButton.onClick()
    ↓
viewModel.updateServerConnection(address, port)
    ↓
PreferencesManager saves address
    ↓
ApiClient.clearCache() resets Retrofit
    ↓
viewModel.connect()
    ↓
videoStreamManager.connect() // WebSocket connection
audioStreamManager.connect()
talkbackManager ready
    ↓
fetchServerStatus(), fetchCameraSettings(), etc.
    ↓
UI updates reactively via StateFlow
```

### Video Streaming
```
Server sends MJPEG frame over /video_feed
    ↓
VideoStreamManager.onMessage(bytes)
    ↓
BitmapFactory.decodeByteArray()
    ↓
_frames.value = bitmap (StateFlow emit)
    ↓
VideoFragment observes _frames
    ↓
binding.videoDisplay.setImageBitmap(frame)
```

### Camera Settings Update
```
User selects camera device and clicks Apply
    ↓
SettingsFragment.applyCameraSettingsButton.onClick()
    ↓
viewModel.updateCameraSettings(device=selectedPath)
    ↓
apiService.updateCameraSettings(request)
    ↓
POST /camera_settings → Server processes
    ↓
Response received
    ↓
fetchCameraSettings() refreshes from server
    ↓
_cameraSettings StateFlow updates
    ↓
StatusFragment observes and displays
```

### Talkback Audio
```
User presses Talkback button (press-to-talk)
    ↓
VideoFragment.talkbackButton.onTouchListener.ACTION_DOWN
    ↓
viewModel.startTalkback()
    ↓
talkbackManager.connect()
    ↓
AudioRecord starts recording from microphone
    ↓
Recording loop reads frames continuously
    ↓
webSocket.send(ByteString) to /ws/talk
    ↓
Server receives and plays via speakers
    ↓
User releases button (ACTION_UP)
    ↓
viewModel.stopTalkback()
    ↓
AudioRecord stops, WebSocket closes
```

## Coroutine Flow

All async operations use Kotlin Coroutines for clean, readable async code:

```kotlin
// In ViewModel
fun connect() {
    viewModelScope.launch {  // Cancels when ViewModel destroyed
        try {
            fetchServerStatus()      // Suspend function
            fetchCameraSettings()    // Suspend function
            videoStreamManager.connect() // Doesn't block
        } catch (e: Exception) {
            _connectionError.value = e.message
        }
    }
}

// In Fragment
lifecycleScope.launch {     // Cancels when Fragment destroyed
    viewModel.serverStatus.collect { status ->
        updateUI(status)    // Reactive update
    }
}
```

## State Management

### Observable State (StateFlow)
Replaces LiveData with Flow for better reactive programming:

```kotlin
private val _frames = MutableStateFlow<Bitmap?>(null)
val frames: StateFlow<Bitmap?> = _frames

// Emit value
_frames.value = bitmap

// Collect in UI
viewModel.frames.observe(lifecycleOwner) { frame ->
    imageView.setImageBitmap(frame)
}

// Or with collect
lifecycleScope.launch {
    viewModel.frames.collect { frame ->
        imageView.setImageBitmap(frame)
    }
}
```

### Error Handling
Centralized error propagation:

```kotlin
private val _errorMessage = MutableStateFlow<String?>(null)
val errorMessage: StateFlow<String?> = _errorMessage

// API call with error handling
suspend fun fetchStatus() {
    try {
        val response = apiService.getStatus()
        if (response.isSuccessful) {
            _serverStatus.value = response.body()
            _errorMessage.value = null  // Clear error
        } else {
            _errorMessage.value = "HTTP ${response.code()}"
        }
    } catch (e: Exception) {
        _errorMessage.value = e.message
    }
}

// UI observes errors
viewModel.errorMessage.observe(lifecycleOwner) { error ->
    if (error != null) {
        showErrorSnackbar(error)
    }
}
```

## Permission Handling

Runtime permissions required:
- `INTERNET` - Network access
- `RECORD_AUDIO` - Microphone for talkback
- `CAMERA` - Not used yet, but include for UI camera control

Implemented in fragments with proper checks:

```kotlin
private fun checkMicrophonePermission(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        if (ContextCompat.checkSelfPermission(...) != PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(...)
            return false
        }
    }
    return true
}

override fun onRequestPermissionsResult(...) {
    if (grantResults[0] == PERMISSION_GRANTED) {
        startTalkback()
    }
}
```

## Threading Model

- **Main Thread**: UI updates only
- **IO Thread**: Network calls via OkHttp/Retrofit
- **Custom Thread**: AudioRecord reading (in TalkbackManager)

Coroutines automatically dispatch to correct thread:

```kotlin
// On Main thread
viewModelScope.launch {          // Inherits Main dispatcher
    val response = apiService.getStatus()  // Switches to IO internally
    _status.value = response.body()        // Back to Main automatically
}
```

## Configuration Changes

Activity configuration changes (rotation) are handled cleanly:

- ViewModel survives rotation
- StateFlows survive rotation
- WebSocket managers survive rotation
- ViewBinding survives rotation
- Coroutines cancellation handled by lifecycle

## Testing Strategy

### Unit Tests
- ViewModel logic (without actual network)
- Data model serialization
- Preference storage

### Integration Tests
- API mock responses
- WebSocket frame processing
- Audio playback simulation

### UI Tests (Espresso)
- Fragment rendering
- Button interactions
- State display updates

## Performance Considerations

### Memory
- Video frames (Bitmaps) can be large - implement recycling
- Audio buffers held in RAM - tune buffer sizes
- WebSocket binary frames cached - monitor heap

### Network
- MJPEG can be bandwidth-heavy - consider compression
- Audio latency affected by buffer sizes - tune for use case
- Talkback bandwidth increases with frame rate

### Battery
- Continuous audio recording drains battery
- WebSocket keepalive signals needed
- Screen on/off affects power consumption

## Security

### HTTPS/WSS
- All connections use TLS
- Certificate pinning optional via SslUtils
- Self-signed certs supported

### Data Protection
- No credentials stored in app
- Preferences encrypted by DataStore
- Sensitive strings in BuildConfig

### Code Obfuscation
- ProGuard rules configured
- Release builds minified
- Sensitive logic protected


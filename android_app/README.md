# Pi Surveillance Android Client

A native Android app for connecting to the Raspberry Pi surveillance server and receiving live video/audio feeds with two-way talkback capability.

## Features

- **Live Video Streaming** - View real-time MJPEG video from the server
- **Live Audio Feed** - Receive audio from the server's microphone
- **Two-Way Talkback** - Send audio back to the server speakers
- **Camera Controls** - Adjust resolution, FPS, and select camera device
- **Audio Settings** - Control speaker volume and select audio devices
- **System Status** - Monitor server and stream status in real-time
- **SSL/TLS Support** - Secure HTTPS/WSS connections with certificate pinning

## Architecture

### API Endpoints (Server)

- `GET /status` - System status (camera/audio running, settings)
- `GET /camera_settings` - Get current camera configuration and available devices
- `POST /camera_settings` - Update camera settings or select camera device
- `GET /speaker_volume` - Get speaker volume percentage
- `POST /speaker_volume` - Set speaker volume
- `GET /server_audio_devices` - List available PulseAudio capture/playback devices
- `POST /server_audio_devices/select` - Select audio input/output device
- `WebSocket /video_feed` - MJPEG video stream
- `WebSocket /audio_feed` - PCM audio stream (48kHz, mono, 16-bit)
- `WebSocket /ws/talk` - Talkback audio stream (send audio to server)

### Project Structure

```
android_app/
├── README.md
├── build.gradle (app level)
├── AndroidManifest.xml
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── com/example/pisurveillance/
│   │   │       ├── MainActivity.kt
│   │   │       ├── ui/
│   │   │       │   ├── fragments/
│   │   │       │   │   ├── VideoFragment.kt
│   │   │       │   │   ├── SettingsFragment.kt
│   │   │       │   │   └── StatusFragment.kt
│   │   │       │   └── adapter/
│   │   │       │       ├── CameraDeviceAdapter.kt
│   │   │       │       └── AudioDeviceAdapter.kt
│   │   │       ├── api/
│   │   │       │   ├── SurveillanceService.kt
│   │   │       │   └── ApiClient.kt
│   │   │       ├── websocket/
│   │   │       │   ├── VideoStreamManager.kt
│   │   │       │   ├── AudioStreamManager.kt
│   │   │       │   └── TalkbackManager.kt
│   │   │       ├── models/
│   │   │       │   ├── ServerStatus.kt
│   │   │       │   ├── CameraSettings.kt
│   │   │       │   └── AudioDevice.kt
│   │   │       ├── utils/
│   │   │       │   ├── SslUtils.kt
│   │   │       │   └── PreferencesManager.kt
│   │   │       └── viewmodel/
│   │   │           ├── SurveillanceViewModel.kt
│   │   │           └── SettingsViewModel.kt
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── activity_main.xml
│   │   │   │   ├── fragment_video.xml
│   │   │   │   ├── fragment_settings.xml
│   │   │   │   └── fragment_status.xml
│   │   │   ├── values/
│   │   │   │   ├── strings.xml
│   │   │   │   ├── colors.xml
│   │   │   │   └── dimens.xml
│   │   │   └── drawable/
│   │   └── AndroidManifest.xml
│   └── test/
│       └── java/com/example/pisurveillance/
├── build.gradle
└── proguard-rules.pro
```

## Dependencies

- **Retrofit 2** - REST API client
- **OkHttp** - HTTP client with SSL support
- **Kotlin Coroutines** - Async/concurrent operations
- **Jetpack Compose or Material Design** - UI framework
- **Jetpack ViewModel & LiveData** - MVVM architecture
- **WebSocket (OkHttp)** - Real-time streaming
- **ExoPlayer** - Video playback (optional, for MJPEG rendering)

## Building & Running

### Prerequisites
- Android Studio (latest)
- Android SDK 28+
- Kotlin 1.8+
- Gradle 8.0+

### Build Steps

```bash
cd android_app
./gradlew build
./gradlew installDebug  # Install on connected device
```

### Configuration

Before running, configure the server connection in preferences:
1. Open app settings
2. Enter Server Address (e.g., `https://192.168.1.100:5000`)
3. Optional: Enable certificate pinning for security
4. Save and connect

## Usage

1. **Connect to Server** - Enter server IP/hostname and port in settings
2. **View Live Feed** - Main screen displays video and audio streams
3. **Adjust Settings** - Use settings tab to change camera/audio parameters
4. **Two-Way Talk** - Press and hold microphone button to send audio
5. **Monitor Status** - Check system status and stream health

## Security

- SSL/TLS encryption for all connections
- Optional certificate pinning against self-signed certificates
- No credentials stored in app (configured server-side)
- Network security config to enforce HTTPS

## Known Limitations

- MJPEG video rendering may have latency on slower networks
- Audio latency depends on network conditions and buffer sizes
- Two-way talkback requires low-latency network connection
- Self-signed certificates require manual pinning setup

## Future Enhancements

- [ ] Snapshot capture functionality
- [ ] Multi-camera support
- [ ] Video recording to device storage
- [ ] Remote pan/tilt/zoom controls (if hardware available)
- [ ] Motion detection alerts
- [ ] Camera preset profiles
- [ ] Offline mode with buffering

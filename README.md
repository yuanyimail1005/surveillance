# surveillance
a surveillance system with camera, speaker and microphone on raspberry pi

## Docker

### Build

```bash
sudo docker build -t surveillance:latest .
```

### Run

The app expects camera/audio devices from the host and HTTPS cert files.

```bash
sudo docker run --rm -it \
	--name surveillance \
	--network host \
	--device /dev/video0 \
	-v /dev/snd:/dev/snd \
	-v "$(pwd)/cert.pem:/app/cert.pem:ro" \
	-v "$(pwd)/key.pem:/app/key.pem:ro" \
	surveillance:latest
```

Notes:
- If your camera is not `/dev/video0`, pass the correct device node (for example `/dev/video8`).
- CSI camera support via `rpicam-vid` requires a Raspberry Pi host environment with `rpicam-apps` support.

### Docker Compose

Copy the example environment file and adjust it if needed:

```bash
cp .env.example .env
```

Build and start with Compose:

```bash
sudo docker compose up --build
```

Run detached:

```bash
sudo docker compose up -d --build
```

Stop the service:

```bash
sudo docker compose down
```

## Android App

A native Android client is available in the `android_app/` directory. It connects to the Raspberry Pi surveillance server and provides:

- **Live Video Streaming** - Real-time MJPEG video feed
- **Live Audio Feed** - Audio from the server's microphone
- **Two-Way Talkback** - Send audio back to the server speakers
- **Camera Controls** - Adjust resolution, FPS, and select camera device
- **Audio Settings** - Control speaker volume and select audio devices
- **System Status** - Monitor server and stream status in real-time
- **SSL/TLS Support** - Secure HTTPS/WSS connections with certificate pinning

### Requirements

- Android Studio (latest)
- Android SDK 28+
- Kotlin 1.8+
- Gradle 8.0+

### Build

```bash
cd android_app
./gradlew build
./gradlew installDebug  # Install on connected device
```

### Configuration

1. Open the app and go to **Settings**
2. Enter the server address (e.g., `https://192.168.1.100:5000`)
3. Optionally enable certificate pinning for self-signed certificates
4. Save and connect

See [android_app/README.md](android_app/README.md) for full documentation.

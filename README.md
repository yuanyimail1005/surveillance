# surveillance
A surveillance system with camera, speaker, microphone, and AI face recognition on Raspberry Pi. Supports real-time face detection and identification, live video/audio streaming, two-way talkback, snapshot capture, and video recording — accessible from a browser or the companion Android app.

## Docker

The recommended way to run the app is with Docker Compose. The image is built on
`dtcooper/raspberrypi-os:bookworm` (arm64 Raspberry Pi OS Bookworm), which includes `rpicam-apps`
for CSI camera support alongside V4L2/USB cameras.

### Prerequisites

- Docker and Docker Compose installed on the Raspberry Pi host.
- An HTTPS certificate and key at `cert.pem` / `key.pem` in the project root.
  Generate a self-signed pair if needed:
  ```bash
  openssl req -x509 -newkey rsa:4096 -keyout key.pem -out cert.pem -days 3650 -nodes -subj '/CN=localhost'
  ```

### Configuration

Copy the example environment file and edit values to match your hardware:

```bash
cp .env.example .env
```

Key variables in `.env`:

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `5000` | HTTPS port the server listens on |
| `CAMERA_DEVICE` | `/dev/video0` | Default V4L2 device (app also auto-detects) |
| `CAMERA_WIDTH` / `CAMERA_HEIGHT` | `1920` / `1080` | Default capture resolution |
| `CAMERA_FPS` | `25` | Default capture frame rate |
| `PULSE_ECHO_CANCEL_ENABLED` | `true` | Enable WebRTC echo cancellation |
| `TALKBACK_PLAYBACK_GAIN` | `5.0` | Talkback audio gain |
| `FACE_RECOGNITION_ENABLED` | `true` | Enable face recognition |
| `FACE_RECOGNITION_KNOWN_FACES_DIR` | `./known_faces` | Host directory of known faces (one subdirectory per person, containing JPEG/PNG images). Mounted read-only into the container as `/known_faces`. |
| `FACE_RECOGNITION_DETECT_EVERY_N_FRAMES` | `(empty = auto)` | Leave empty to detect every ~0.5s based on current FPS, or set N to force a fixed interval |
| `FACE_RECOGNITION_MATCH_THRESHOLD` | `0.45` | Maximum face distance to count as a match (lower = stricter) |
| `FACE_RECOGNITION_MAX_FACES` | `8` | Maximum number of faces to detect per frame |
| `FACE_RECOGNITION_BACKEND` | `auto` | Backend preference: `auto`, `opencv`, or `dlib` |
| `FACE_RECOGNITION_YUNET_MODEL_PATH` | `./models/face_detection_yunet_2023mar.onnx` | Path to OpenCV YuNet detector model |
| `FACE_RECOGNITION_SFACE_MODEL_PATH` | `./models/face_recognition_sface_2021dec.onnx` | Path to OpenCV SFace embedding model |

When `FACE_RECOGNITION_BACKEND=auto`, the app tries OpenCV YuNet/SFace first and falls back to dlib (`face-recognition`) if OpenCV models are unavailable.

For Docker Compose, avoid `~` in `FACE_RECOGNITION_KNOWN_FACES_DIR` when running with `sudo`, because it can resolve to `/root`. Prefer `./known_faces` or an absolute host path like `/home/eric/known_faces`.

### Build

Build the image (required once and after any code changes):

```bash
sudo docker compose build surveillance
```

### Start

Run the container in the background:

```bash
sudo docker compose up -d surveillance
```

### Stop

```bash
sudo docker compose down
```

### Restart

```bash
sudo docker compose restart surveillance
```

### Logs

Stream live logs from the running container:

```bash
sudo docker compose logs -f surveillance
```

### Notes

- The container uses `network_mode: host`, so the app binds directly to port `5000` on the host — ensure no other process is using that port before starting.
- CSI camera (`rpicam://0`) is auto-detected when `rpicam-vid` is available inside the container. USB/V4L2 cameras are also auto-detected via `v4l2-ctl`.
- Only one Gunicorn worker is used to prevent duplicate camera/audio pipelines being opened at module import time.

## Android App

A native Android client is available in the `android_app/` directory. It connects to the Raspberry Pi surveillance server and provides:

- **Live Video Streaming** - Real-time MJPEG video feed
- **Live Audio Feed** - Audio from the server's microphone
- **Two-Way Talkback** - Send audio back to the server speakers
- **Camera Controls** - Adjust resolution, FPS, and select camera device
- **Audio Settings** - Control speaker volume and select audio devices
- **AI Face Recognition** - Real-time face detection and identification overlay on the video feed
- **Snapshot Capture** - Save a photo of the current frame (face overlay included)
- **Video Recording** - Record video clips directly from the app
- **System Status** - Monitor server, stream, and Face AI backend status in real-time
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

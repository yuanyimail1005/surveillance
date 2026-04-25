# Surveillance

A self-hosted surveillance system for Raspberry Pi (and compatible Linux machines) that streams live video and audio through a browser-based interface. Features include real-time MJPEG video streaming, live microphone audio, two-way talkback, speaker volume control, and configurable camera settings — all served over HTTPS.

## Features

- **Live video streaming** via WebSocket (MJPEG) from a V4L2 USB camera or a CSI camera (Raspberry Pi Camera Module via `rpicam-vid`)
- **Live audio streaming** — mono microphone audio sent to the browser in real time
- **Two-way talkback** — speak from the browser and hear it through the Raspberry Pi's speaker
- **Echo cancellation** — PulseAudio `module-echo-cancel` (WebRTC AEC by default) prevents talkback audio from feeding back into the microphone
- **Speaker volume control** — read and adjust the output volume from the web UI
- **Camera settings** — choose resolution (640×480, 1280×720, 1920×1080, 2560×1440), frame rate (1–60 fps), and switch between attached cameras without restarting the server
- **Device selection** — switch between available microphone sources and speaker sinks at runtime
- **HTTPS** — served with TLS so the browser grants microphone access

## Requirements

### Hardware
- Raspberry Pi (any model with a camera and audio) or a Linux PC
- USB webcam with MJPEG support **or** Raspberry Pi Camera Module (CSI)
- USB microphone or sound card
- Speaker or headphone output

### System packages

| Package | Purpose |
|---|---|
| `ffmpeg` | V4L2 MJPEG capture pipeline |
| `rpicam-vid` / `rpicam-hello` | CSI camera support (Raspberry Pi only) |
| `v4l2-ctl` | Camera capability detection |
| `pulseaudio`, `pactl`, `parec`, `pacat` | Audio capture and playback |
| `openssl` | Generating a self-signed TLS certificate |

Install on Raspberry Pi OS / Debian:

```bash
sudo apt update
sudo apt install ffmpeg v4l2-utils pulseaudio pulseaudio-utils openssl
```

### Python packages

```bash
pip install flask flask-cors flask-sock
```

## Setup

### 1. Generate a self-signed TLS certificate

The browser requires HTTPS to allow microphone access. Generate a certificate in the project directory:

```bash
openssl req -x509 -newkey rsa:4096 -keyout key.pem -out cert.pem \
    -days 365 -nodes -subj "/CN=localhost"
```

### 2. Start PulseAudio (if not already running)

```bash
pulseaudio --start --daemonize=true --exit-idle-time=-1
```

The application will also attempt to start PulseAudio automatically if it is not running.

### 3. Run the server

```bash
python app.py
```

The server starts on `https://0.0.0.0:5000` by default. Open `https://<raspberry-pi-ip>:5000` in a browser. Accept the self-signed certificate warning, then allow microphone access when prompted.

## Configuration

All settings can be overridden with environment variables before starting the server.

### Video

| Variable | Default | Description |
|---|---|---|
| `CAMERA_DEVICE` | `/dev/video0` | Camera device path. Use `rpicam://0` for the first CSI camera. |
| `CAMERA_WIDTH` | `1920` | Capture width in pixels (must be an allowed resolution). |
| `CAMERA_HEIGHT` | `1080` | Capture height in pixels (must be an allowed resolution). |
| `CAMERA_FPS` | `25` | Target frame rate (1–60). |

Allowed resolutions: `640×480`, `1280×720`, `1920×1080`, `2560×1440`.

### Audio

| Variable | Default | Description |
|---|---|---|
| `SPEAKER_DEVICE` | `default` | ALSA speaker device name (legacy fallback). |
| `PULSE_SINK_NAME` | `@DEFAULT_SINK@` | PulseAudio sink used for talkback playback. |
| `PULSE_CAPTURE_SOURCE_NAME` | `@DEFAULT_SOURCE@` | PulseAudio source used for microphone capture. |
| `TALKBACK_PLAYBACK_GAIN` | `5.0` | Gain applied to incoming talkback audio (0.1–12.0). |
| `AUDIO_PLAYER_NICE` | `0` | `nice` priority for the audio playback process. |

### Echo cancellation

| Variable | Default | Description |
|---|---|---|
| `PULSE_ECHO_CANCEL_ENABLED` | `true` | Set to `false` to disable PulseAudio echo cancellation. |
| `PULSE_ECHO_CANCEL_AEC_METHOD` | `webrtc` | AEC method passed to `module-echo-cancel` (e.g. `webrtc`, `speex`). |
| `PULSE_ECHO_CANCEL_SOURCE_NAME` | `surveillance_ec_source` | Name of the virtual echo-cancel source. |
| `PULSE_ECHO_CANCEL_SINK_NAME` | `surveillance_ec_sink` | Name of the virtual echo-cancel sink. |

### Server

| Variable | Default | Description |
|---|---|---|
| `SERVER_HOST` | `0.0.0.0` | Listening address. |
| `SERVER_PORT` | `5000` | Listening port. |
| `SSL_CERT_PATH` | `cert.pem` | Path to TLS certificate file. |
| `SSL_KEY_PATH` | `key.pem` | Path to TLS private key file. |

**Example — custom port and CSI camera:**

```bash
SERVER_PORT=8443 CAMERA_DEVICE=rpicam://0 python app.py
```

## Finding audio device names

Use the included helper script to list available audio devices:

```bash
python find_device.py
```

Or query PulseAudio directly:

```bash
pactl list short sources   # microphone sources
pactl list short sinks     # speaker sinks
```

## API reference

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Web UI |
| `WS` | `/video_feed` | MJPEG frame stream over WebSocket |
| `WS` | `/audio_feed` | Raw PCM audio stream (16-bit LE, 48 kHz, mono) over WebSocket |
| `WS` | `/ws/talk` | Talkback audio upload from the browser |
| `GET` | `/camera_settings` | Current camera settings and available devices |
| `POST` | `/camera_settings` | Update camera settings (`width`, `height`, `fps`, `camera_device`) |
| `GET` | `/speaker_volume` | Read current speaker volume (0–100) |
| `POST` | `/speaker_volume` | Set speaker volume (`{"volume": 75}`) |
| `GET` | `/server_audio_devices` | List available microphone sources and speaker sinks |
| `POST` | `/server_audio_devices/select` | Switch active microphone source or speaker sink |
| `GET` | `/status` | JSON status: camera/audio process state, active device, settings |

## Project structure

```
surveillance/
├── app.py              # Flask application — routes, device management, streaming
├── config.py           # Default settings and environment variable overrides
├── find_device.py      # Utility: list available PyAudio devices
├── static/
│   └── talk-capture-processor.js   # AudioWorklet processor for talkback capture
└── templates/
    └── index.html      # Browser UI
```

## Troubleshooting

**No video** — Check that the camera device exists (`ls /dev/video*`) and supports MJPEG (`v4l2-ctl -d /dev/video0 --list-formats-ext`). For CSI cameras ensure `rpicam-vid` is installed and the camera is enabled in `raspi-config`.

**No audio / microphone not detected** — Verify PulseAudio is running (`pactl info`) and the correct source is selected. Run `python find_device.py` to list available devices.

**Browser microphone access denied** — The page must be served over HTTPS. Ensure `cert.pem` and `key.pem` are present and that you have accepted the certificate in the browser.

**Echo / feedback during talkback** — Echo cancellation is enabled by default. If it fails to load, check the server log for `⚠ PulseAudio echo cancel setup failed`. You can try a different AEC method: `PULSE_ECHO_CANCEL_AEC_METHOD=speex python app.py`.

**Self-signed certificate warning** — This is expected. In Chrome/Edge, click *Advanced → Proceed*. In Firefox, click *Accept the Risk and Continue*.

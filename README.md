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

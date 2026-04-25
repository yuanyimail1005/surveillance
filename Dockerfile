FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

# Runtime tools used by the app:
# - ffmpeg for V4L2 MJPEG capture
# - pulseaudio-utils for pactl/parec/pacat
# - v4l-utils for v4l2-ctl probing
# If available on the base distro (for Raspberry Pi), install rpicam-apps as well.
RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ffmpeg \
        pulseaudio-utils \
        v4l-utils \
        ca-certificates; \
    if apt-cache show rpicam-apps >/dev/null 2>&1; then \
        DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends rpicam-apps; \
    fi; \
    rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 5000

CMD ["python", "app.py"]

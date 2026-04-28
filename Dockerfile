FROM vascoguita/raspios:latest

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    VIRTUAL_ENV=/opt/venv \
    PATH="/opt/venv/bin:${PATH}"

WORKDIR /app

# Runtime tools used by the app:
# - python3/pip for app runtime
# - ffmpeg for V4L2 MJPEG capture
# - pulseaudio-utils for pactl/parec/pacat
# - v4l-utils for v4l2-ctl probing
# - rpicam-apps for CSI camera discovery and streaming
RUN set -eux; \
    apt-get update; \
    # `adduser` in this base image expects a lock file at /run/adduser.
    mkdir -p /run; \
    rm -rf /run/adduser; \
    touch /run/adduser; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
        python3-dev \
        build-essential \
        ffmpeg \
        pulseaudio \
        pulseaudio-utils \
        v4l-utils \
        rpicam-apps \
        ca-certificates; \
    python3 -m venv "$VIRTUAL_ENV"; \
    "$VIRTUAL_ENV/bin/pip" install --upgrade pip setuptools wheel; \
    rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 5000

CMD ["sh", "-c", "exec gunicorn --bind ${SERVER_HOST:-0.0.0.0}:${SERVER_PORT:-5000} --workers 1 --threads 100 --timeout 0 --access-logfile - --error-logfile - --certfile ${SSL_CERT_PATH:-/app/cert.pem} --keyfile ${SSL_KEY_PATH:-/app/key.pem} app:app"]

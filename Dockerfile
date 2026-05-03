FROM dtcooper/raspberrypi-os:bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    FACE_RECOGNITION_DETECT_EVERY_N_FRAMES= \
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
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
        python3-dev \
        build-essential \
        cmake \
        libopenblas-dev \
        liblapack-dev \
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

# Patch face_recognition_models to work without pkg_resources (dropped in modern setuptools/Python 3.13)
RUN set -eux; \
    FRM_INIT="$(python -c "import importlib.util; print(importlib.util.find_spec('face_recognition_models').origin)")" ; \
    printf '%s\n' \
        'import os as _os' \
        '' \
        'def _model_path(f):' \
        '    return _os.path.join(_os.path.dirname(_os.path.abspath(__file__)), "models", f)' \
        '' \
        'def pose_predictor_model_location():' \
        '    return _model_path("shape_predictor_68_face_landmarks.dat")' \
        '' \
        'def pose_predictor_five_point_model_location():' \
        '    return _model_path("shape_predictor_5_face_landmarks.dat")' \
        '' \
        'def face_recognition_model_location():' \
        '    return _model_path("dlib_face_recognition_resnet_model_v1.dat")' \
        '' \
        'def cnn_face_detector_model_location():' \
        '    return _model_path("mmod_human_face_detector.dat")' \
    > "$FRM_INIT"

COPY . .

EXPOSE 5000

CMD ["sh", "-c", "exec gunicorn --bind ${SERVER_HOST:-0.0.0.0}:${SERVER_PORT:-5000} --workers 1 --threads 100 --timeout 0 --access-logfile - --error-logfile - --certfile ${SSL_CERT_PATH:-/app/cert.pem} --keyfile ${SSL_KEY_PATH:-/app/key.pem} app:app"]

import os

# Ensure ALSA defaults are initialized before audio processes start.
os.environ.setdefault('ALSA_CARD', 'default')

VIDEO_ALLOWED_RESOLUTIONS = [
    (640, 480),
    (1280, 720),
    (1920, 1080),
    (2560, 1440),
]
VIDEO_MIN_FPS = 1
VIDEO_MAX_FPS = 60

DEFAULT_CAMERA_WIDTH = int(os.environ.get('CAMERA_WIDTH', '1920'))
DEFAULT_CAMERA_HEIGHT = int(os.environ.get('CAMERA_HEIGHT', '1080'))
DEFAULT_CAMERA_FPS = int(os.environ.get('CAMERA_FPS', '25'))
CAMERA_DEVICE = os.environ.get('CAMERA_DEVICE', '/dev/video0')

if (DEFAULT_CAMERA_WIDTH, DEFAULT_CAMERA_HEIGHT) not in VIDEO_ALLOWED_RESOLUTIONS:
    DEFAULT_CAMERA_WIDTH, DEFAULT_CAMERA_HEIGHT = 1920, 1080
DEFAULT_CAMERA_FPS = max(VIDEO_MIN_FPS, min(VIDEO_MAX_FPS, DEFAULT_CAMERA_FPS))

SPEAKER_DEVICE = os.environ.get('SPEAKER_DEVICE', 'default')
PULSE_SINK_NAME = os.environ.get('PULSE_SINK_NAME', '@DEFAULT_SINK@')
PULSE_CAPTURE_SOURCE_NAME = os.environ.get('PULSE_CAPTURE_SOURCE_NAME', '@DEFAULT_SOURCE@')

SAMPLE_RATE = 48000
MIC_CHANNELS = 1
SPEAKER_CHANNELS = 2

AUDIO_PLAYER_NICE = int(os.environ.get('AUDIO_PLAYER_NICE', '0'))

TALKBACK_HIGHPASS_HZ = 80
TALKBACK_LOWPASS_HZ = 7000
TALKBACK_WORKLET_CHUNK_SAMPLES = 8192
TALKBACK_ECHO_CANCELLATION = True
TALKBACK_NOISE_SUPPRESSION = True
TALKBACK_AUTO_GAIN_CONTROL = False
TALKBACK_LATENCY_SECONDS = 0.02

PULSE_ECHO_CANCEL_ENABLED = os.environ.get('PULSE_ECHO_CANCEL_ENABLED', 'true').lower() != 'false'
PULSE_ECHO_CANCEL_AEC_METHOD = os.environ.get('PULSE_ECHO_CANCEL_AEC_METHOD', 'webrtc')
PULSE_ECHO_CANCEL_SOURCE_NAME = os.environ.get('PULSE_ECHO_CANCEL_SOURCE_NAME', 'surveillance_ec_source')
PULSE_ECHO_CANCEL_SINK_NAME = os.environ.get('PULSE_ECHO_CANCEL_SINK_NAME', 'surveillance_ec_sink')

try:
    _talkback_playback_gain = float(os.environ.get('TALKBACK_PLAYBACK_GAIN', '5.0'))
except ValueError:
    _talkback_playback_gain = 5.0
TALKBACK_PLAYBACK_GAIN = max(0.1, min(12.0, _talkback_playback_gain))

SPEAKER_VOLUME_CONTROLS = ('Speaker', 'PCM', 'Master', 'Headphone')

SERVER_HOST = os.environ.get('SERVER_HOST', '0.0.0.0')
SERVER_PORT = int(os.environ.get('SERVER_PORT', '5000'))
_user_home = os.path.expanduser('~')
SSL_CERT_PATH = os.environ.get('SSL_CERT_PATH', os.path.join(_user_home, 'certs', 'cert.pem'))
SSL_KEY_PATH = os.environ.get('SSL_KEY_PATH', os.path.join(_user_home, 'certs', 'key.pem'))

WEBRTC_ICE_SERVERS = [
    value.strip()
    for value in os.environ.get('WEBRTC_ICE_SERVERS', 'stun:stun.l.google.com:19302').split(',')
    if value.strip()
]
WEBRTC_TURN_USERNAME = os.environ.get('WEBRTC_TURN_USERNAME', '')
WEBRTC_TURN_PASSWORD = os.environ.get('WEBRTC_TURN_PASSWORD', '')

try:
    _webrtc_media_port_min = int(os.environ.get('WEBRTC_MEDIA_PORT_MIN', '10000'))
except ValueError:
    _webrtc_media_port_min = 10000

try:
    _webrtc_media_port_max = int(os.environ.get('WEBRTC_MEDIA_PORT_MAX', '15000'))
except ValueError:
    _webrtc_media_port_max = 15000

_webrtc_media_port_min = max(1, min(65535, _webrtc_media_port_min))
_webrtc_media_port_max = max(1, min(65535, _webrtc_media_port_max))
if _webrtc_media_port_min > _webrtc_media_port_max:
    _webrtc_media_port_min, _webrtc_media_port_max = _webrtc_media_port_max, _webrtc_media_port_min

WEBRTC_MEDIA_PORT_MIN = _webrtc_media_port_min
WEBRTC_MEDIA_PORT_MAX = _webrtc_media_port_max

FACE_RECOGNITION_ENABLED = os.environ.get('FACE_RECOGNITION_ENABLED', 'false').lower() == 'true'
FACE_RECOGNITION_KNOWN_FACES_DIR = os.path.abspath(os.path.expanduser(
    os.environ.get('FACE_RECOGNITION_KNOWN_FACES_DIR', os.path.join(_user_home, 'known_faces'))
))
_detect_n_env = os.environ.get('FACE_RECOGNITION_DETECT_EVERY_N_FRAMES')
FACE_RECOGNITION_DETECT_EVERY_N_FRAMES = max(1, int(_detect_n_env)) if _detect_n_env else None  # None = auto (fps // 2)
FACE_RECOGNITION_MATCH_THRESHOLD = float(os.environ.get('FACE_RECOGNITION_MATCH_THRESHOLD', '0.45'))
FACE_RECOGNITION_MAX_FACES = max(1, int(os.environ.get('FACE_RECOGNITION_MAX_FACES', '3')))
FACE_RECOGNITION_BACKEND = os.environ.get('FACE_RECOGNITION_BACKEND', 'auto').lower()
FACE_RECOGNITION_YUNET_MODEL_PATH = os.path.expanduser(
    os.environ.get('FACE_RECOGNITION_YUNET_MODEL_PATH', './models/face_detection_yunet_2023mar.onnx')
)
FACE_RECOGNITION_SFACE_MODEL_PATH = os.path.expanduser(
    os.environ.get('FACE_RECOGNITION_SFACE_MODEL_PATH', './models/face_recognition_sface_2021dec.onnx')
)

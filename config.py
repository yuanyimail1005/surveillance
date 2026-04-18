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
SSL_CERT_PATH = os.environ.get('SSL_CERT_PATH', 'cert.pem')
SSL_KEY_PATH = os.environ.get('SSL_KEY_PATH', 'key.pem')

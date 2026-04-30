from flask import Flask, render_template, request, jsonify
from flask_cors import CORS
from flask_sock import Sock
import os
import subprocess
import time
import logging
import ssl
import glob
import shutil
import threading
import struct
import re
import queue
import json
import multiprocessing
import werkzeug.serving
import importlib

try:
    np = importlib.import_module('numpy')
except Exception:
    np = None

try:
    cv2 = importlib.import_module('cv2')
except Exception:
    cv2 = None

try:
    face_recognition = importlib.import_module('face_recognition')
except Exception:
    face_recognition = None

from config import (
    VIDEO_ALLOWED_RESOLUTIONS,
    VIDEO_MIN_FPS,
    VIDEO_MAX_FPS,
    DEFAULT_CAMERA_WIDTH,
    DEFAULT_CAMERA_HEIGHT,
    DEFAULT_CAMERA_FPS,
    CAMERA_DEVICE,
    SPEAKER_DEVICE,
    PULSE_SINK_NAME,
    PULSE_CAPTURE_SOURCE_NAME,
    SAMPLE_RATE,
    MIC_CHANNELS,
    SPEAKER_CHANNELS,
    AUDIO_PLAYER_NICE,
    TALKBACK_HIGHPASS_HZ,
    TALKBACK_LOWPASS_HZ,
    TALKBACK_WORKLET_CHUNK_SAMPLES,
    TALKBACK_ECHO_CANCELLATION,
    TALKBACK_NOISE_SUPPRESSION,
    TALKBACK_AUTO_GAIN_CONTROL,
    TALKBACK_LATENCY_SECONDS,
    TALKBACK_PLAYBACK_GAIN,
    PULSE_ECHO_CANCEL_ENABLED,
    PULSE_ECHO_CANCEL_AEC_METHOD,
    PULSE_ECHO_CANCEL_SOURCE_NAME,
    PULSE_ECHO_CANCEL_SINK_NAME,
    SPEAKER_VOLUME_CONTROLS,
    FACE_RECOGNITION_ENABLED,
    FACE_RECOGNITION_KNOWN_FACES_DIR,
    FACE_RECOGNITION_DETECT_EVERY_N_FRAMES,
    FACE_RECOGNITION_MATCH_THRESHOLD,
    FACE_RECOGNITION_MAX_FACES,
    FACE_RECOGNITION_BACKEND,
    FACE_RECOGNITION_YUNET_MODEL_PATH,
    FACE_RECOGNITION_SFACE_MODEL_PATH,
    SERVER_HOST,
    SERVER_PORT,
    SSL_CERT_PATH,
    SSL_KEY_PATH,
)

logging.getLogger('werkzeug').setLevel(logging.ERROR)

# ssl.SSLError (UNEXPECTED_EOF_WHILE_READING) is raised in run_wsgi's finally
# block when the browser closes an HTTPS streaming connection.  Add it to the
# module-level tuple so werkzeug treats it as a silent client disconnect rather
# than logging a full traceback.
werkzeug.serving.connection_dropped_errors = (
    werkzeug.serving.connection_dropped_errors + (ssl.SSLError,)
)

app = Flask(__name__)
CORS(app)
sock = Sock(app)

camera_settings = {
    'width': DEFAULT_CAMERA_WIDTH,
    'height': DEFAULT_CAMERA_HEIGHT,
    'fps': DEFAULT_CAMERA_FPS,
}
active_camera_device = None
camera_device_preference = CAMERA_DEVICE
camera_proc_lock = threading.Lock()

print("\n" + "="*60)
print("Surveillance System - Starting")
print("="*60 + "\n")

pulseaudio_started_by_app = False
echo_cancel_module_id = None
device_switch_lock = threading.Lock()
audio_proc_lock = threading.Lock()

def list_capture_devices():
    """Return available PulseAudio capture sources plus default."""
    devices = [{'id': '@DEFAULT_SOURCE@', 'name': 'Default microphone', 'kind': 'default'}]
    try:
        short_result = subprocess.run(['pactl', 'list', 'short', 'sources'], capture_output=True, text=True)
        if short_result.returncode != 0:
            return devices

        description_map = {}
        long_result = subprocess.run(['pactl', 'list', 'sources'], capture_output=True, text=True)
        if long_result.returncode == 0:
            current_name = None
            for raw_line in long_result.stdout.splitlines():
                line = raw_line.strip()
                if line.startswith('Name:'):
                    current_name = line.split(':', 1)[1].strip()
                elif line.startswith('Description:') and current_name:
                    description_map[current_name] = line.split(':', 1)[1].strip()
                    current_name = None

        seen = set(['@DEFAULT_SOURCE@'])
        for raw_line in short_result.stdout.splitlines():
            parts = raw_line.split('\t')
            if len(parts) < 2:
                continue
            source_name = parts[1].strip()
            if not source_name or source_name in seen or source_name.endswith('.monitor'):
                continue
            seen.add(source_name)
            description = description_map.get(source_name, source_name)
            devices.append({'id': source_name, 'name': description, 'kind': 'pulseaudio-source'})
    except Exception as e:
        print(f'Error listing capture devices: {e}')
    return devices

def _resolve_default_sink():
    """Return the actual PulseAudio sink name currently set as the default, or None."""
    try:
        result = subprocess.run(['pactl', 'info'], capture_output=True, text=True)
        if result.returncode != 0:
            return None
        for line in result.stdout.splitlines():
            if line.startswith('Default Sink:'):
                return line.split(':', 1)[1].strip() or None
    except Exception:
        pass
    return None

def _resolve_default_source():
    """Return the actual PulseAudio source name currently set as the default, or None."""
    try:
        result = subprocess.run(['pactl', 'info'], capture_output=True, text=True)
        if result.returncode != 0:
            return None
        for line in result.stdout.splitlines():
            if line.startswith('Default Source:'):
                return line.split(':', 1)[1].strip() or None
    except Exception:
        pass
    return None

def _setup_pulseaudio_echo_cancel():
    """Create PulseAudio echo-cancel source/sink, optionally chaining LADSPA highpass/lowpass filter."""
    global echo_cancel_module_id, PULSE_CAPTURE_SOURCE_NAME, PULSE_SINK_NAME

    if not PULSE_ECHO_CANCEL_ENABLED:
        return False

    if echo_cancel_module_id is not None:
        return True

    # Always use the real hardware devices as masters, never the echo-cancel virtual devices
    sink_master = PULSE_SINK_NAME
    if not sink_master or sink_master == '@DEFAULT_SINK@' or sink_master == PULSE_ECHO_CANCEL_SINK_NAME:
        sink_master = _resolve_default_sink()
        if sink_master == PULSE_ECHO_CANCEL_SINK_NAME:
            sinks = list_output_sinks()
            for s in sinks:
                if s['id'] not in ('@DEFAULT_SINK@', PULSE_ECHO_CANCEL_SINK_NAME):
                    sink_master = s['id']
                    break

    source_master = PULSE_CAPTURE_SOURCE_NAME
    if not source_master or source_master == '@DEFAULT_SOURCE@' or source_master == PULSE_ECHO_CANCEL_SOURCE_NAME:
        source_master = _resolve_default_source()
        if source_master == PULSE_ECHO_CANCEL_SOURCE_NAME:
            sources = list_capture_devices()
            for s in sources:
                if s['id'] not in ('@DEFAULT_SOURCE@', PULSE_ECHO_CANCEL_SOURCE_NAME):
                    source_master = s['id']
                    break

    if not sink_master or not source_master or \
       sink_master == PULSE_ECHO_CANCEL_SINK_NAME or \
       source_master == PULSE_ECHO_CANCEL_SOURCE_NAME:
        print('⚠ PulseAudio echo cancel: selected/default sink/source not available or would chain echo-cancel')
        return False

    # Debug: print source_master and sink_master before LADSPA filter chaining
    print(f"[DEBUG] LADSPA chaining: source_master={source_master}, sink_master={sink_master}")

    cmd = [
        'pactl',
        'load-module',
        'module-echo-cancel',
        f'aec_method={PULSE_ECHO_CANCEL_AEC_METHOD}',
        f'source_master={source_master}',
        f'sink_master={sink_master}',
        f'source_name={PULSE_ECHO_CANCEL_SOURCE_NAME}',
        f'sink_name={PULSE_ECHO_CANCEL_SINK_NAME}',
        f'source_properties=device.description=SurveillanceEchoCancelSource',
        f'sink_properties=device.description=SurveillanceEchoCancelSink',
        f'rate={SAMPLE_RATE}',
        'channels=1'
    ]

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        stderr = (result.stderr or '').strip()
        print(f'⚠ PulseAudio echo cancel setup failed: {stderr or "unknown error"}')
        return False

    try:
        echo_cancel_module_id = int((result.stdout or '').strip())
    except ValueError:
        echo_cancel_module_id = None

    # Route app capture/playback through the echo-cancel virtual devices.
    PULSE_CAPTURE_SOURCE_NAME = PULSE_ECHO_CANCEL_SOURCE_NAME
    PULSE_SINK_NAME = PULSE_ECHO_CANCEL_SINK_NAME
    print(
        f'✓ PulseAudio echo cancel enabled '
        f'(source={PULSE_CAPTURE_SOURCE_NAME}, sink={PULSE_SINK_NAME}, method={PULSE_ECHO_CANCEL_AEC_METHOD})'
    )
    return True

def _teardown_pulseaudio_echo_cancel():
    """Unload PulseAudio echo-cancel module if this app loaded it."""
    global echo_cancel_module_id
    if echo_cancel_module_id is None:
        return

    result = subprocess.run(
        ['pactl', 'unload-module', str(echo_cancel_module_id)],
        capture_output=True,
        text=True
    )
    if result.returncode == 0:
        print('✓ PulseAudio echo cancel disabled')
    echo_cancel_module_id = None

def list_output_sinks():
    """Return available PulseAudio sinks plus default."""
    sinks = [{'id': '@DEFAULT_SINK@', 'name': 'Default speaker', 'kind': 'default'}]
    try:
        short_result = subprocess.run(['pactl', 'list', 'short', 'sinks'], capture_output=True, text=True)
        if short_result.returncode != 0:
            return sinks

        # Build a sink-name -> human description map from `pactl list sinks`.
        description_map = {}
        long_result = subprocess.run(['pactl', 'list', 'sinks'], capture_output=True, text=True)
        if long_result.returncode == 0:
            current_name = None
            for raw_line in long_result.stdout.splitlines():
                line = raw_line.strip()
                if line.startswith('Name:'):
                    current_name = line.split(':', 1)[1].strip()
                elif line.startswith('Description:') and current_name:
                    description_map[current_name] = line.split(':', 1)[1].strip()
                    current_name = None

        seen = set(['@DEFAULT_SINK@'])
        for raw_line in short_result.stdout.splitlines():
            parts = raw_line.split('\t')
            if len(parts) < 2:
                continue
            sink_name = parts[1].strip()
            if not sink_name or sink_name in seen:
                continue
            seen.add(sink_name)
            description = description_map.get(sink_name, sink_name)
            sinks.append({'id': sink_name, 'name': description, 'kind': 'pulseaudio-sink'})
    except Exception as e:
        print(f'Error listing output sinks: {e}')
    return sinks

def restart_audio_capture_process():
    """Restart audio capture process with the current capture source."""
    global audio_proc
    with audio_proc_lock:
        if audio_proc is not None:
            try:
                audio_proc.terminate()
                audio_proc.wait(timeout=2)
            except Exception:
                try:
                    audio_proc.kill()
                    audio_proc.wait(timeout=1)
                except Exception:
                    pass
            audio_proc = None
        audio_proc = start_audio()
        audio_broadcaster.set_proc(audio_proc)
        return audio_proc is not None

def ensure_audio_capture_process_running():
    """Ensure audio capture process exists and is alive."""
    global audio_proc

    with audio_proc_lock:
        if audio_proc is not None and audio_proc.poll() is None:
            return True
        print('⚙ Audio capture process not running, restarting...')
        audio_proc = start_audio()
        audio_broadcaster.set_proc(audio_proc)
        return audio_proc is not None

def ensure_pulseaudio_daemon_running():
    """Start PulseAudio daemon if it is not already running."""
    global pulseaudio_started_by_app

    result = subprocess.run(['pactl', 'info'], capture_output=True)
    if result.returncode == 0:
        pulseaudio_started_by_app = False
        print("✓ PulseAudio daemon already running")
        return True
    print("⚙ PulseAudio not running, starting daemon...")
    start = subprocess.run(
        ['pulseaudio', '--start', '--daemonize=true', '--exit-idle-time=-1'],
        capture_output=True, text=True
    )
    if start.returncode == 0:
        time.sleep(1)  # give it a moment to fully initialise
        pulseaudio_started_by_app = True
        print("✓ PulseAudio daemon started")
        return True
    print(f"✗ Failed to start PulseAudio: {start.stderr.strip()}")
    return False

def stop_pulseaudio_daemon_if_started_by_app():
    """Stop PulseAudio daemon only if this app started it."""
    if not pulseaudio_started_by_app:
        return

    stop = subprocess.run(['pulseaudio', '--kill'], capture_output=True, text=True)
    if stop.returncode == 0:
        print("✓ PulseAudio daemon stopped")
    else:
        error_text = (stop.stderr or stop.stdout or 'unknown error').strip()
        print(f"⚠ Failed to stop PulseAudio daemon: {error_text}")

# Auto-detect devices at startup
print("\n=== Detecting Audio Devices ===\n")

ensure_pulseaudio_daemon_running()

if PULSE_ECHO_CANCEL_ENABLED:
    _setup_pulseaudio_echo_cancel()

    print(f"Using microphone source: {PULSE_CAPTURE_SOURCE_NAME or '@DEFAULT_SOURCE@'}")
print(f"Using speaker sink: {PULSE_SINK_NAME}")

print()

def _get_speaker_volume_percent():
    """Read current speaker volume percent using pactl on PulseAudio sink."""
    sink = PULSE_SINK_NAME
    if not sink or sink in ('@DEFAULT_SINK@', 'default'):
        sink = _resolve_default_sink()
    if not sink:
        return None, None

    # pactl returns e.g. 'Volume: front-left: 65536 / 100% / 0.00 dB, ...'
    result = subprocess.run(
        ['pactl', 'list', 'sinks'],
        capture_output=True,
        text=True
    )
    if result.returncode != 0:
        return None, None

    in_sink = False
    for line in result.stdout.splitlines():
        if line.strip().startswith('Name:'):
            in_sink = (line.split(':', 1)[1].strip() == sink)
        if in_sink and 'Volume:' in line:
            # Find the first percentage in the line
            match = re.search(r'(\d{1,3})%', line)
            if match:
                return int(match.group(1)), 'pactl'
    return None, None

def _set_speaker_volume_percent(volume_percent):
    """Set speaker volume percent using pactl on PulseAudio sink."""
    sink = PULSE_SINK_NAME
    if not sink or sink in ('@DEFAULT_SINK@', 'default'):
        sink = _resolve_default_sink()
    if not sink:
        return None

    volume_percent = max(0, min(100, int(volume_percent)))
    result = subprocess.run(
        ['pactl', 'set-sink-volume', sink, f'{volume_percent}%'],
        capture_output=True,
        text=True
    )
    if result.returncode == 0:
        return 'pactl'
    return None

# Audio playback pipeline
pulse_proc = None
pulse_write_lock = threading.Lock()

def start_pulse_process():
    """Start one PulseAudio playback process and feed it through stdin."""
    global pulse_proc

    try:
        if pulse_proc is None or pulse_proc.poll() is not None:
            pulse_proc = subprocess.Popen(
                [
                    'pacat',
                    '--playback',
                    '--raw',
                    '--format=s16le',
                    '--rate', str(SAMPLE_RATE),
                    '--channels', str(SPEAKER_CHANNELS),
                    '--device', PULSE_SINK_NAME,
                    '--stream-name', 'surveillance-speaker'
                ],
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                bufsize=0
            )

        if pulse_proc.stdin is None:
            raise RuntimeError('pacat stdin pipe is unavailable')

        print(f"✓ PulseAudio stdin pipeline started (PID: {pulse_proc.pid}, sink: {PULSE_SINK_NAME})")
        return True
    except Exception as e:
        print(f"✗ Failed to start PulseAudio pipeline: {e}")
        return False

def _restart_pulse_pipeline():
    """Best-effort restart when PulseAudio playback exits or pipe breaks."""
    stop_pulse_pipeline()
    return start_pulse_process()

def write_to_pulse_stdin(audio_bytes):
    """Write all bytes to PulseAudio stdin."""

    if not audio_bytes:
        return True

    if pulse_proc is None or pulse_proc.poll() is not None or pulse_proc.stdin is None:
        if not _restart_pulse_pipeline():
            return False

    # Flask can handle concurrent requests; serialize writes to keep audio frames ordered.
    with pulse_write_lock:
        view = memoryview(audio_bytes)
        offset = 0
        while offset < len(view):
            try:
                written = pulse_proc.stdin.write(view[offset:])
                if written <= 0:
                    return False
                print(f"↳ PulseAudio stdin wrote {written} bytes")
                offset += written
            except BrokenPipeError:
                print("⚠ PulseAudio stdin pipe broke, restarting pipeline")
                if not _restart_pulse_pipeline():
                    return False
            except OSError as e:
                print(f"⚠ PulseAudio stdin write error: {e}")
                return False

    return True

def stop_pulse_pipeline():
    """Stop stdin writer and terminate the PulseAudio playback process."""
    global pulse_proc

    if pulse_proc is not None and pulse_proc.stdin is not None:
        try:
            pulse_proc.stdin.close()
        except OSError:
            pass

    if pulse_proc is not None:
        try:
            pulse_proc.terminate()
            pulse_proc.wait(timeout=2)
        except Exception:
            try:
                pulse_proc.kill()
                pulse_proc.wait(timeout=1)
            except Exception:
                pass
        pulse_proc = None

if start_pulse_process():
    print("🎵 Direct audio write mode enabled (no intermediate queue)")
else:
    print("⚠ Audio playback pipeline will retry on first write")

def _video_device_sort_key(device_path):
    match = re.search(r'(\d+)$', device_path)
    if match:
        return int(match.group(1))
    return float('inf')

def _iter_camera_device_candidates(preferred_device=None):
    candidates = []
    if preferred_device:
        candidates.append(preferred_device)
    candidates.extend(sorted(glob.glob('/dev/video*'), key=_video_device_sort_key))

    seen = set()
    for device_path in candidates:
        if not device_path or device_path in seen:
            continue
        seen.add(device_path)
        yield device_path

def _parse_rpicam_path(camera_path):
    match = re.match(r'^rpicam://(\d+)$', str(camera_path or '').strip())
    if not match:
        return None
    return match.group(1)

def get_camera_source_type(camera_path):
    if _parse_rpicam_path(camera_path) is not None:
        return 'CSI'
    if str(camera_path or '').startswith('/dev/video'):
        return 'V4L2'
    return 'Unknown'

def _is_supported_v4l2_camera(device_path):
    if not os.path.exists(device_path):
        return False, 'device node missing'

    if shutil.which('v4l2-ctl') is None:
        return True, 'v4l2-ctl unavailable; using existing device node'

    caps_result = subprocess.run(
        ['v4l2-ctl', '-d', device_path, '--all'],
        capture_output=True,
        text=True
    )
    if caps_result.returncode != 0:
        return False, 'v4l2 query failed'

    caps_text = caps_result.stdout.lower()
    if 'metadata capture' in caps_text and 'video capture' not in caps_text:
        return False, 'metadata-only endpoint'
    if 'video capture' not in caps_text:
        return False, 'not a video capture endpoint'

    formats_result = subprocess.run(
        ['v4l2-ctl', '-d', device_path, '--list-formats-ext'],
        capture_output=True,
        text=True
    )
    if formats_result.returncode != 0:
        return False, 'format enumeration failed'

    formats_text = formats_result.stdout.lower()
    if 'mjpg' not in formats_text and 'motion-jpeg' not in formats_text:
        return False, 'mjpeg not supported'

    return True, 'video capture with mjpeg support'

def _discover_rpicam_cameras():
    if shutil.which('rpicam-hello') is None:
        return []

    try:
        result = subprocess.run(
            ['rpicam-hello', '--list-cameras'],
            capture_output=True,
            text=True,
            timeout=4
        )
        if result.returncode != 0:
            return []

        cameras = []
        for raw_line in result.stdout.splitlines():
            line = raw_line.strip()
            match = re.match(r'^(\d+)\s*:\s*(.+)$', line)
            if not match:
                continue
            cameras.append({
                'index': match.group(1),
                'descriptor': match.group(2),
            })
        return cameras
    except Exception:
        return []

def _is_supported_rpicam_camera(camera_path):
    camera_index = _parse_rpicam_path(camera_path)
    if camera_index is None:
        return False, 'invalid rpicam path'

    if shutil.which('rpicam-vid') is None:
        return False, 'rpicam-vid command not found'

    cameras = _discover_rpicam_cameras()
    if not cameras:
        return False, 'no CSI cameras reported by rpicam-hello'

    available_indexes = {camera['index'] for camera in cameras}
    if camera_index not in available_indexes:
        return False, f'CSI camera index {camera_index} not found'

    return True, 'CSI camera via rpicam-vid mjpeg stream'

def resolve_camera_device(preferred_device=None):
    """Resolve preferred camera path, defaulting to V4L2 MJPEG auto-detection."""
    preferred = preferred_device or CAMERA_DEVICE

    if _parse_rpicam_path(preferred) is not None:
        supported, reason = _is_supported_rpicam_camera(preferred)
        if supported:
            print(f'✓ Selected camera device {preferred} ({reason})')
            return preferred
        print(f'⚙ Skipping camera device {preferred} ({reason})')
    elif preferred:
        supported, reason = _is_supported_v4l2_camera(preferred)
        if supported:
            print(f'✓ Selected camera device {preferred} ({reason})')
            return preferred
        print(f'⚙ Skipping camera device {preferred} ({reason})')

    for device_path in _iter_camera_device_candidates(None):
        supported, reason = _is_supported_v4l2_camera(device_path)
        if supported:
            print(f'✓ Selected camera device {device_path} ({reason})')
            return device_path
        print(f'⚙ Skipping camera device {device_path} ({reason})')

    for csi_camera in list_rpicam_camera_options():
        if csi_camera['supported']:
            print(f"✓ Selected camera device {csi_camera['path']} ({csi_camera['reason']})")
            return csi_camera['path']

    print('✗ No usable camera source found (V4L2 MJPEG or CSI rpicam)')
    return None

def list_rpicam_camera_options():
    """List CSI cameras discovered via rpicam-hello for UI selection."""
    options = []
    for camera in _discover_rpicam_cameras():
        path = f"rpicam://{camera['index']}"
        supported, reason = _is_supported_rpicam_camera(path)
        options.append({
            'path': path,
            'name': f"CSI Camera {camera['index']}: {camera['descriptor']}",
            'supported': supported,
            'reason': reason,
        })
    return options

def list_camera_device_options(selected_device=None):
    """List selectable camera paths supported by current stream pipeline."""
    selected = selected_device or camera_device_preference or active_camera_device or CAMERA_DEVICE
    options = []
    for device_path in _iter_camera_device_candidates(selected):
        supported, reason = _is_supported_v4l2_camera(device_path)
        if supported:
            options.append({
                'path': device_path,
                'name': device_path,
                'supported': True,
                'reason': reason,
            })

    options.extend([option for option in list_rpicam_camera_options() if option['supported']])
    return options

def start_camera(width, height, fps):
    """Start camera pipeline and output MJPEG bytes to stdout."""
    global active_camera_device

    try:
        resolved_camera_device = resolve_camera_device(camera_device_preference or active_camera_device or CAMERA_DEVICE)
        if resolved_camera_device is None:
            print('✗ Camera failed: no usable camera device found\n')
            return None

        camera_index = _parse_rpicam_path(resolved_camera_device)
        if camera_index is not None:
            print(
                f"Starting camera pipeline (rpicam-vid mjpeg, camera {camera_index}, "
                f"{width}x{height} @ {fps}fps)..."
            )
            proc = subprocess.Popen(
                [
                    'rpicam-vid',
                    '--camera', str(camera_index),
                    '--codec', 'mjpeg',
                    '--width', str(width),
                    '--height', str(height),
                    '--framerate', str(fps),
                    '--timeout', '0',
                    '--nopreview',
                    '--output', '-'
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                bufsize=10**6
            )
        else:
            print(
                f"Starting camera pipeline (ffmpeg v4l2 mjpeg, {resolved_camera_device}, "
                f"{width}x{height} @ {fps}fps)..."
            )
            proc = subprocess.Popen(
                [
                    'ffmpeg', '-loglevel', 'error',
                    '-f', 'v4l2',
                    '-input_format', 'mjpeg',
                    '-video_size', f'{width}x{height}',
                    '-framerate', str(fps),
                    '-i', resolved_camera_device,
                    '-vcodec', 'copy',
                    '-f', 'mjpeg',
                    'pipe:1'
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                bufsize=10**6
            )
        active_camera_device = resolved_camera_device
        print(f"✓ Camera pipeline started (PID: {proc.pid})\n")
        return proc
    except Exception as e:
        print(f"✗ Camera failed: {e}\n")
        return None

def _normalize_camera_settings(width, height, fps):
    try:
        w = int(width)
        h = int(height)
        frames = int(fps)
    except (TypeError, ValueError):
        return None, 'width, height and fps must be integers'

    if (w, h) not in VIDEO_ALLOWED_RESOLUTIONS:
        choices = ', '.join(f'{rw}x{rh}' for rw, rh in VIDEO_ALLOWED_RESOLUTIONS)
        return None, f'Unsupported resolution {w}x{h}. Allowed: {choices}'

    if frames < VIDEO_MIN_FPS or frames > VIDEO_MAX_FPS:
        return None, f'fps must be between {VIDEO_MIN_FPS} and {VIDEO_MAX_FPS}'

    return {'width': w, 'height': h, 'fps': frames}, None

def restart_camera_process(new_settings):
    """Restart camera process with new settings."""
    global camera_proc, camera_device_preference

    with camera_proc_lock:
        previous_settings = dict(camera_settings)
        previous_device_preference = camera_device_preference

        requested_device_preference = new_settings.pop('camera_device', None)
        if requested_device_preference is not None:
            camera_device_preference = requested_device_preference

        if camera_proc is not None:
            try:
                camera_proc.terminate()
                camera_proc.wait(timeout=2)
            except Exception:
                try:
                    camera_proc.kill()
                    camera_proc.wait(timeout=1)
                except Exception:
                    pass
            camera_proc = None

        camera_settings.update(new_settings)
        new_proc = start_camera(
            camera_settings['width'],
            camera_settings['height'],
            camera_settings['fps']
        )

        if new_proc is None:
            camera_settings.update(previous_settings)
            camera_device_preference = previous_device_preference
            return False

        camera_proc = new_proc
        video_broadcaster.set_proc(camera_proc)

    return True

def ensure_camera_process_running():
    """Ensure camera process exists and is alive."""
    global camera_proc

    with camera_proc_lock:
        if camera_proc is not None and camera_proc.poll() is None and camera_proc.stdout is not None:
            return True
        print('⚙ Camera process not running, restarting...')
        camera_proc = start_camera(
            camera_settings['width'],
            camera_settings['height'],
            camera_settings['fps']
        )
        video_broadcaster.set_proc(camera_proc)
        return camera_proc is not None

def start_audio():
    """Start audio recording from selected PulseAudio source."""
    try:
        capture_source = PULSE_CAPTURE_SOURCE_NAME
        if not capture_source or capture_source in ('@DEFAULT_SOURCE@', 'default'):
            capture_source = _resolve_default_source()

        if not capture_source:
            print('⚠ Audio failed: PulseAudio default source not available\n')
            return None

        print(f"Starting audio recording from PulseAudio source ({capture_source})...")
        cmd = [
            'parec',
            '--device', capture_source,
            '--format=s16le',
            '--rate', str(SAMPLE_RATE),
            '--channels', str(MIC_CHANNELS),
            '--raw'
        ]

        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=4096
        )

        # Give parec a brief moment so immediate open failures are caught.
        time.sleep(0.2)
        if proc.poll() is not None:
            stderr = proc.stderr.read().decode('utf-8', errors='ignore')
            print(f"⚠ PulseAudio capture process died: {stderr}\n")
            return None

        mode = 'PulseAudio echo-cancel source' if capture_source == PULSE_ECHO_CANCEL_SOURCE_NAME else 'PulseAudio source'
        print(f"✓ Audio recording started ({mode}, Mono, {SAMPLE_RATE}Hz)\n")
        return proc
    except Exception as e:
        print(f"⚠ Audio failed: {e}\n")
        return None


class FaceRecognitionService:
    def __init__(
        self,
        enabled,
        known_faces_dir,
        detect_every_n_frames,
        match_threshold,
        max_faces,
    ):
        self._enabled_requested = bool(enabled)
        self._known_faces_dir = known_faces_dir
        self._detect_every_n_frames = max(1, int(detect_every_n_frames))
        self._match_threshold = float(match_threshold)
        self._max_faces = max(1, int(max_faces))
        self._lock = threading.Lock()
        self._frame_counter = 0
        self._known_encodings = []
        self._known_names = []
        self._available = False
        self._backend = 'none'
        self._face_detector = None
        self._face_recognizer = None
        self._status_message = 'disabled by configuration'
        self._last_result = {
            'updated_at': None,
            'frame_index': 0,
            'image_width': 0,
            'image_height': 0,
            'faces': []
        }

        if not self._enabled_requested:
            return

        if np is None or cv2 is None:
            self._status_message = 'numpy/cv2 dependencies unavailable'
            return

        backend = FACE_RECOGNITION_BACKEND
        if backend not in ('auto', 'opencv', 'dlib'):
            backend = 'auto'

        if backend in ('auto', 'opencv') and self._try_init_opencv_backend():
            return

        if backend in ('auto', 'dlib') and self._try_init_dlib_backend():
            return

        self._status_message = 'no available face-recognition backend (opencv or dlib)'

    def _try_init_opencv_backend(self):
        if not hasattr(cv2, 'FaceDetectorYN_create') or not hasattr(cv2, 'FaceRecognizerSF_create'):
            return False

        if not os.path.isfile(FACE_RECOGNITION_YUNET_MODEL_PATH):
            self._status_message = f'YuNet model not found: {FACE_RECOGNITION_YUNET_MODEL_PATH}'
            return False
        if not os.path.isfile(FACE_RECOGNITION_SFACE_MODEL_PATH):
            self._status_message = f'SFace model not found: {FACE_RECOGNITION_SFACE_MODEL_PATH}'
            return False

        try:
            self._face_detector = cv2.FaceDetectorYN_create(
                FACE_RECOGNITION_YUNET_MODEL_PATH,
                '',
                (320, 320),
                0.8,
                0.3,
                5000,
            )
            self._face_recognizer = cv2.FaceRecognizerSF_create(
                FACE_RECOGNITION_SFACE_MODEL_PATH,
                '',
            )
        except Exception as e:
            self._status_message = f'failed to initialize OpenCV face models: {e}'
            return False

        self._backend = 'opencv'
        self._load_known_faces_opencv()
        self._available = True
        self._status_message = 'ready (opencv-yunet-sface)'
        return True

    def _try_init_dlib_backend(self):
        if face_recognition is None:
            self._status_message = 'dlib face_recognition dependency unavailable'
            return False

        self._backend = 'dlib'
        self._load_known_faces_dlib()
        self._available = True
        self._status_message = 'ready (dlib)'
        return True

    def _load_known_faces_dlib(self):
        root = self._known_faces_dir
        if not os.path.isdir(root):
            return

        image_patterns = ('*.jpg', '*.jpeg', '*.png')
        for person_name in sorted(os.listdir(root)):
            person_dir = os.path.join(root, person_name)
            if not os.path.isdir(person_dir):
                continue

            image_paths = []
            for pattern in image_patterns:
                image_paths.extend(glob.glob(os.path.join(person_dir, pattern)))

            for image_path in sorted(image_paths):
                try:
                    image = face_recognition.load_image_file(image_path)
                    encodings = face_recognition.face_encodings(image)
                    if not encodings:
                        continue
                    self._known_encodings.append(encodings[0])
                    self._known_names.append(person_name)
                except Exception:
                    continue

    def _load_known_faces_opencv(self):
        root = self._known_faces_dir
        if not os.path.isdir(root):
            return

        image_patterns = ('*.jpg', '*.jpeg', '*.png')
        for person_name in sorted(os.listdir(root)):
            person_dir = os.path.join(root, person_name)
            if not os.path.isdir(person_dir):
                continue

            image_paths = []
            for pattern in image_patterns:
                image_paths.extend(glob.glob(os.path.join(person_dir, pattern)))

            for image_path in sorted(image_paths):
                try:
                    image_bgr = cv2.imread(image_path)
                    if image_bgr is None:
                        continue

                    h, w = image_bgr.shape[:2]
                    self._face_detector.setInputSize((w, h))
                    _, detections = self._face_detector.detect(image_bgr)
                    if detections is None or len(detections) == 0:
                        continue

                    # Use the highest confidence detection to build one reference embedding.
                    best = max(detections, key=lambda d: float(d[14]) if len(d) > 14 else 0.0)
                    aligned = self._face_recognizer.alignCrop(image_bgr, best)
                    feature = self._face_recognizer.feature(aligned)
                    if feature is None:
                        continue

                    vec = np.asarray(feature, dtype=np.float32).flatten()
                    norm = float(np.linalg.norm(vec))
                    if norm <= 1e-8:
                        continue
                    vec = vec / norm

                    self._known_encodings.append(vec)
                    self._known_names.append(person_name)
                except Exception:
                    continue

    def process_frame(self, frame_bytes):
        if not self._available or np is None or cv2 is None:
            return

        self._frame_counter += 1
        if self._frame_counter % self._detect_every_n_frames != 0:
            return

        np_buf = np.frombuffer(frame_bytes, dtype=np.uint8)
        frame_bgr = cv2.imdecode(np_buf, cv2.IMREAD_COLOR)
        if frame_bgr is None:
            return

        if self._backend == 'opencv':
            self._process_frame_opencv(frame_bgr)
            return

        frame_rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)

        try:
            locations = face_recognition.face_locations(frame_rgb, model='hog')
        except Exception:
            return

        if self._max_faces:
            locations = locations[:self._max_faces]

        try:
            encodings = face_recognition.face_encodings(frame_rgb, locations, model='small')
        except Exception:
            encodings = []

        faces = []
        for index, location in enumerate(locations):
            top, right, bottom, left = location
            name = 'Unknown'
            confidence = 0.0

            if index < len(encodings) and self._known_encodings:
                distances = face_recognition.face_distance(self._known_encodings, encodings[index])
                if len(distances) > 0:
                    best_idx = int(np.argmin(distances))
                    best_distance = float(distances[best_idx])
                    if best_distance <= self._match_threshold:
                        name = self._known_names[best_idx]
                        confidence = max(0.0, min(1.0, 1.0 - best_distance))

            faces.append({
                'name': name,
                'confidence': round(confidence, 3),
                'left': int(left),
                'top': int(top),
                'right': int(right),
                'bottom': int(bottom),
            })

        with self._lock:
            self._last_result = {
                'updated_at': time.time(),
                'frame_index': self._frame_counter,
                'image_width': int(frame_bgr.shape[1]),
                'image_height': int(frame_bgr.shape[0]),
                'faces': faces,
            }

    def _process_frame_opencv(self, frame_bgr):
        h, w = frame_bgr.shape[:2]

        try:
            self._face_detector.setInputSize((w, h))
            _, detections = self._face_detector.detect(frame_bgr)
        except Exception:
            return

        if detections is None:
            detections = []
        if self._max_faces:
            detections = detections[:self._max_faces]

        faces = []
        known_matrix = None
        if self._known_encodings:
            known_matrix = np.vstack(self._known_encodings)

        for detection in detections:
            x, y, bw, bh = detection[:4]
            left = max(0, int(round(x)))
            top = max(0, int(round(y)))
            right = min(w - 1, int(round(x + bw)))
            bottom = min(h - 1, int(round(y + bh)))

            name = 'Unknown'
            confidence = 0.0

            try:
                aligned = self._face_recognizer.alignCrop(frame_bgr, detection)
                feature = self._face_recognizer.feature(aligned)
            except Exception:
                feature = None

            if feature is not None and known_matrix is not None and len(known_matrix) > 0:
                vec = np.asarray(feature, dtype=np.float32).flatten()
                norm = float(np.linalg.norm(vec))
                if norm > 1e-8:
                    vec = vec / norm
                    similarities = known_matrix.dot(vec)
                    best_idx = int(np.argmax(similarities))
                    best_similarity = float(similarities[best_idx])
                    distance = max(0.0, 1.0 - best_similarity)
                    if distance <= self._match_threshold:
                        name = self._known_names[best_idx]
                        confidence = max(0.0, min(1.0, best_similarity))

            faces.append({
                'name': name,
                'confidence': round(confidence, 3),
                'left': left,
                'top': top,
                'right': right,
                'bottom': bottom,
            })

        with self._lock:
            self._last_result = {
                'updated_at': time.time(),
                'frame_index': self._frame_counter,
                'image_width': int(w),
                'image_height': int(h),
                'faces': faces,
            }

    def is_processing_enabled(self):
        return self._enabled_requested and self._available

    def get_push_payload_if_new(self, last_frame_index):
        """Return a face-data push payload if a new detection result is available, else None."""
        with self._lock:
            current_idx = self._last_result.get('frame_index', -1)
        if current_idx == last_frame_index:
            return None
        return self.get_status()

    def get_status(self):
        with self._lock:
            latest = dict(self._last_result)

        return {
            'enabled': self._enabled_requested,
            'available': self._available,
            'backend': self._backend,
            'message': self._status_message,
            'known_faces_count': len(self._known_names),
            'detect_every_n_frames': self._detect_every_n_frames,
            'match_threshold': self._match_threshold,
            'max_faces': self._max_faces,
            'result': latest,
        }


def _queue_replace_latest(q, item):
    """Put item into a bounded queue, dropping one stale entry when full."""
    try:
        q.put_nowait(item)
        return
    except queue.Full:
        pass

    try:
        q.get_nowait()
    except queue.Empty:
        pass

    try:
        q.put_nowait(item)
    except queue.Full:
        pass


def _face_recognition_worker(
    frame_queue,
    result_queue,
    stop_event,
    enabled,
    known_faces_dir,
    detect_every_n_frames,
    match_threshold,
    max_faces,
):
    service = FaceRecognitionService(
        enabled=enabled,
        known_faces_dir=known_faces_dir,
        detect_every_n_frames=detect_every_n_frames,
        match_threshold=match_threshold,
        max_faces=max_faces,
    )

    _queue_replace_latest(result_queue, service.get_status())
    last_sent_idx = -1

    while not stop_event.is_set():
        try:
            frame = frame_queue.get(timeout=0.2)
        except queue.Empty:
            continue

        if frame is None:
            break

        service.process_frame(frame)
        payload = service.get_push_payload_if_new(last_sent_idx)
        if payload is None:
            continue
        last_sent_idx = payload['result'].get('frame_index', last_sent_idx)
        _queue_replace_latest(result_queue, payload)


class FaceRecognitionProcessBridge:
    def __init__(
        self,
        enabled,
        known_faces_dir,
        detect_every_n_frames,
        match_threshold,
        max_faces,
    ):
        self._enabled_requested = bool(enabled)
        self._lock = threading.Lock()
        self._stop_collector = threading.Event()

        self._frame_queue = None
        self._result_queue = None
        self._stop_event = None
        self._worker = None
        self._collector = None

        self._last_status = {
            'enabled': self._enabled_requested,
            'available': False,
            'message': 'disabled by configuration',
            'known_faces_count': 0,
            'detect_every_n_frames': max(1, int(detect_every_n_frames)),
            'match_threshold': float(match_threshold),
            'max_faces': max(1, int(max_faces)),
            'result': {
                'updated_at': None,
                'frame_index': 0,
                'image_width': 0,
                'image_height': 0,
                'faces': [],
            },
        }

        if not self._enabled_requested:
            return

        try:
            ctx = multiprocessing.get_context('fork')
        except ValueError:
            with self._lock:
                self._last_status['message'] = 'multiprocessing fork context unavailable'
            return

        self._frame_queue = ctx.Queue(maxsize=1)
        self._result_queue = ctx.Queue(maxsize=4)
        self._stop_event = ctx.Event()

        self._worker = ctx.Process(
            target=_face_recognition_worker,
            args=(
                self._frame_queue,
                self._result_queue,
                self._stop_event,
                enabled,
                known_faces_dir,
                detect_every_n_frames,
                match_threshold,
                max_faces,
            ),
            daemon=True,
            name='face-recognition-worker',
        )
        self._worker.start()

        self._collector = threading.Thread(
            target=self._collect_results,
            daemon=True,
            name='face-recognition-results',
        )
        self._collector.start()

    def _collect_results(self):
        while not self._stop_collector.is_set():
            if self._result_queue is None:
                return
            try:
                status = self._result_queue.get(timeout=0.2)
            except queue.Empty:
                continue

            if status is None:
                return

            with self._lock:
                self._last_status = status

    def accepts_frames(self):
        return self._frame_queue is not None and self._worker is not None and self._worker.is_alive()

    def process_frame(self, frame_bytes):
        if not self.accepts_frames():
            return
        _queue_replace_latest(self._frame_queue, frame_bytes)

    def is_processing_enabled(self):
        with self._lock:
            status = dict(self._last_status)
        return bool(status.get('enabled') and status.get('available'))

    def get_push_payload_if_new(self, last_frame_index):
        """Return a face-data push payload if a new detection result is available, else None."""
        with self._lock:
            current_idx = self._last_status.get('result', {}).get('frame_index', -1)
        if current_idx == last_frame_index:
            return None
        return self.get_status()

    def get_status(self):
        with self._lock:
            status = dict(self._last_status)
            status['result'] = dict(self._last_status.get('result', {}))
        return status

    def stop(self):
        self._stop_collector.set()

        if self._stop_event is not None:
            self._stop_event.set()

        if self._frame_queue is not None:
            _queue_replace_latest(self._frame_queue, None)

        if self._worker is not None and self._worker.is_alive():
            self._worker.join(timeout=2)
            if self._worker.is_alive():
                self._worker.terminate()

        if self._collector is not None and self._collector.is_alive():
            self._collector.join(timeout=1)


class FrameBroadcaster:
    """
    One background thread continuously drains a subprocess stdout and
    distributes frames to all subscribed WebSocket handlers via per-client
    queues.  Keeps the pipe drained even when no clients are connected so
    the producer process never blocks.
    """

    def __init__(self, name, read_frames_fn, on_frame_fn=None):
        self.name = name
        self._read_frames = read_frames_fn
        self._on_frame = on_frame_fn
        self._lock = threading.Lock()
        self._subscribers = {}  # token_id -> queue.Queue
        self._proc = None
        t = threading.Thread(target=self._run, daemon=True, name=f'broadcaster-{name}')
        t.start()

    def set_proc(self, proc):
        """Notify the broadcaster that a new subprocess is now the source."""
        with self._lock:
            self._proc = proc

    def subscribe(self):
        """Return (token, queue). Caller must call unsubscribe(token) when done."""
        token = object()
        q = queue.Queue(maxsize=30)
        with self._lock:
            self._subscribers[id(token)] = q
        return token, q

    def unsubscribe(self, token):
        with self._lock:
            self._subscribers.pop(id(token), None)

    def _broadcast(self, frame):
        with self._lock:
            queues = list(self._subscribers.values())
        for q in queues:
            try:
                q.put_nowait(frame)
            except queue.Full:
                pass  # slow client: drop frame rather than block

    def _run(self):
        while True:
            with self._lock:
                proc = self._proc
            if proc is None or proc.poll() is not None or proc.stdout is None:
                time.sleep(0.05)
                continue
            try:
                for frame in self._read_frames(proc):
                    with self._lock:
                        current_proc = self._proc
                    if current_proc is not proc:
                        break  # proc was replaced; restart outer loop
                    if self._on_frame is not None:
                        self._on_frame(frame)
                    self._broadcast(frame)
            except Exception as e:
                print(f'[{self.name}] reader error: {e}')
                time.sleep(0.05)


def _video_frames_gen(proc):
    """Yield complete MJPEG frames from a subprocess stdout."""
    buf = b''
    while True:
        try:
            chunk = proc.stdout.read(4096)
        except (OSError, ValueError):
            return
        if not chunk:
            return
        buf += chunk
        while True:
            start = buf.find(b'\xff\xd8')
            if start == -1:
                buf = b''
                break
            end = buf.find(b'\xff\xd9', start + 2)
            if end == -1:
                buf = buf[start:]
                break
            yield buf[start:end + 2]
            buf = buf[end + 2:]


def _audio_chunks_gen(proc):
    """Yield raw PCM chunks from a subprocess stdout."""
    while True:
        try:
            chunk = proc.stdout.read(4096)
        except (OSError, ValueError):
            return
        if not chunk:
            return
        yield chunk


# Start processes
camera_proc = start_camera(
    camera_settings['width'],
    camera_settings['height'],
    camera_settings['fps']
)
audio_proc = start_audio()

face_recognition_service = FaceRecognitionProcessBridge(
    enabled=FACE_RECOGNITION_ENABLED,
    known_faces_dir=FACE_RECOGNITION_KNOWN_FACES_DIR,
    detect_every_n_frames=FACE_RECOGNITION_DETECT_EVERY_N_FRAMES,
    match_threshold=FACE_RECOGNITION_MATCH_THRESHOLD,
    max_faces=FACE_RECOGNITION_MAX_FACES,
)

video_broadcaster = FrameBroadcaster(
    'video',
    _video_frames_gen,
    on_frame_fn=face_recognition_service.process_frame if face_recognition_service.accepts_frames() else None,
)
audio_broadcaster = FrameBroadcaster('audio', _audio_chunks_gen)
video_broadcaster.set_proc(camera_proc)
audio_broadcaster.set_proc(audio_proc)

@app.route('/')
def index():
    selected_camera_device = active_camera_device or camera_device_preference or CAMERA_DEVICE
    return render_template(
        'index.html',
        talkback_highpass_hz=TALKBACK_HIGHPASS_HZ,
        talkback_lowpass_hz=TALKBACK_LOWPASS_HZ,
        talkback_chunk_samples=TALKBACK_WORKLET_CHUNK_SAMPLES,
        talkback_echo_cancellation=TALKBACK_ECHO_CANCELLATION,
        talkback_noise_suppression=TALKBACK_NOISE_SUPPRESSION,
        talkback_auto_gain_control=TALKBACK_AUTO_GAIN_CONTROL,
        talkback_latency_seconds=TALKBACK_LATENCY_SECONDS,
        camera_width=camera_settings['width'],
        camera_height=camera_settings['height'],
        camera_fps=camera_settings['fps'],
        camera_device=active_camera_device or CAMERA_DEVICE,
        camera_device_preference=camera_device_preference,
        camera_source_type=get_camera_source_type(selected_camera_device),
    )

@sock.route('/video_feed')
def video_feed_socket(ws):
    """Stream JPEG frames over WebSocket from the shared camera broadcaster."""
    if not ensure_camera_process_running():
        print('📹 Video socket: camera not available')
        return

    print('📹 Video socket connected')
    token, q = video_broadcaster.subscribe()
    last_sent_face_idx = -1
    try:
        while True:
            try:
                frame = q.get(timeout=5.0)
            except queue.Empty:
                if camera_proc is None or camera_proc.poll() is not None:
                    if not ensure_camera_process_running():
                        print('📹 Video socket: camera unavailable after restart attempt')
                        break
                continue
            ws.send(frame)
            payload = face_recognition_service.get_push_payload_if_new(last_sent_face_idx)
            if payload is not None:
                last_sent_face_idx = payload['result'].get('frame_index', last_sent_face_idx)
                ws.send(json.dumps({'type': 'face_data', **payload}))
    except Exception as e:
        print(f'📹 Video socket closed: {e}')
    finally:
        video_broadcaster.unsubscribe(token)
        print('📹 Video socket disconnected')


@sock.route('/audio_feed')
def audio_feed_socket(ws):
    """Stream mono microphone PCM over WebSocket from the shared audio broadcaster."""
    if not ensure_audio_capture_process_running():
        print('🎤 Audio socket: audio process not available after restart attempt')
        return

    print('🎤 Audio socket connected')
    token, q = audio_broadcaster.subscribe()
    chunk_count = 0
    try:
        while True:
            try:
                chunk = q.get(timeout=5.0)
            except queue.Empty:
                if audio_proc is None or audio_proc.poll() is not None:
                    if not ensure_audio_capture_process_running():
                        print('🎤 Audio socket: audio unavailable after restart attempt')
                        break
                continue
            chunk_count += 1
            if chunk_count % 50 == 0:
                print(f'🎤 Audio socket: sent chunk {chunk_count} ({len(chunk)} bytes)')
            ws.send(chunk)
    except Exception as e:
        print(f'🎤 Audio socket closed: {e}')
    finally:
        audio_broadcaster.unsubscribe(token)
        print('🎤 Audio socket disconnected')

def convert_mono_to_stereo(mono_data):
    """Convert mono audio to stereo and apply configurable playback gain."""

    sample_count = len(mono_data) // 2
    if sample_count == 0:
        return b''

    mono_data = mono_data[:sample_count * 2]
    mono_samples = struct.unpack(f'<{sample_count}h', mono_data)

    stereo_buffer = bytearray(sample_count * 4)
    offset = 0
    gain = TALKBACK_PLAYBACK_GAIN
    for sample in mono_samples:
        boosted = int(sample * gain)
        if boosted > 32767:
            boosted = 32767
        elif boosted < -32768:
            boosted = -32768
        struct.pack_into('<hh', stereo_buffer, offset, boosted, boosted)
        offset += 4

    return bytes(stereo_buffer)


@sock.route('/ws/talk')
def talk_audio_socket(ws):
    """Receive mono PCM from the browser over WebSocket and play it immediately."""
    print('🎙 WebSocket talkback connected')
    message_count = 0
    received_bytes = 0
    try:
        while True:
            message = ws.receive()
            if message is None:
                print('🎙 Talkback socket closed by client')
                break

            if isinstance(message, str):
                print(f'🎙 Talkback text frame ignored: {message[:80]}')
                continue

            if not message:
                continue

            message_count += 1
            received_bytes += len(message)
            if message_count <= 3 or message_count % 25 == 0:
                print(f'🎙 Talkback received chunk {message_count} ({len(message)} bytes, total {received_bytes} bytes)')

            stereo_data = convert_mono_to_stereo(message)
            if stereo_data and not write_to_pulse_stdin(stereo_data):
                print('⚠ WebSocket talkback write failed')
                break
    except Exception as e:
        print(f'⚠ WebSocket talkback closed: {e}')
    finally:
        print('🎙 WebSocket talkback disconnected')


@app.route('/speaker_volume', methods=['GET'])
def get_speaker_volume():
    """Get current Raspberry Pi speaker volume."""
    try:
        volume_percent, control = _get_speaker_volume_percent()
        if volume_percent is None:
            return jsonify({
                'status': 'error',
                'available': False,
                'message': 'Could not read speaker volume via amixer'
            }), 500

        return jsonify({
            'status': 'ok',
            'available': True,
            'volume': volume_percent,
            'control': control
        })
    except Exception as e:
        return jsonify({'status': 'error', 'message': str(e), 'available': False}), 500


@app.route('/speaker_volume', methods=['POST'])
def set_speaker_volume():
    """Set Raspberry Pi speaker volume."""
    try:
        payload = request.get_json(silent=True) or {}
        if 'volume' not in payload:
            return jsonify({'status': 'error', 'message': 'Missing volume'}), 400

        requested_volume = int(payload['volume'])
        requested_volume = max(0, min(100, requested_volume))

        used_control = _set_speaker_volume_percent(requested_volume)
        if used_control is None:
            return jsonify({
                'status': 'error',
                'message': 'Could not set speaker volume via amixer'
            }), 500

        current_volume, _ = _get_speaker_volume_percent()
        if current_volume is None:
            current_volume = requested_volume

        return jsonify({
            'status': 'ok',
            'volume': current_volume,
            'control': used_control
        })
    except ValueError:
        return jsonify({'status': 'error', 'message': 'Volume must be an integer'}), 400
    except Exception as e:
        return jsonify({'status': 'error', 'message': str(e)}), 500

@app.route('/camera_settings', methods=['GET'])
def get_camera_settings():
    selected_camera_device = active_camera_device or camera_device_preference or CAMERA_DEVICE
    allowed = [
        {'width': width, 'height': height}
        for width, height in VIDEO_ALLOWED_RESOLUTIONS
    ]
    return jsonify({
        'status': 'ok',
        'width': camera_settings['width'],
        'height': camera_settings['height'],
        'fps': camera_settings['fps'],
        'camera_device': active_camera_device,
        'selected_camera_device': selected_camera_device,
        'camera_source_type': get_camera_source_type(selected_camera_device),
        'available_camera_devices': list_camera_device_options(selected_camera_device),
        'allowed_resolutions': allowed,
        'fps_range': {
            'min': VIDEO_MIN_FPS,
            'max': VIDEO_MAX_FPS
        }
    })

@app.route('/camera_settings', methods=['POST'])
def set_camera_settings():
    payload = request.get_json(silent=True) or {}
    requested_camera_device = payload.get('camera_device')

    if requested_camera_device is not None:
        requested_camera_device = str(requested_camera_device).strip()
        if not requested_camera_device:
            return jsonify({'status': 'error', 'message': 'camera_device cannot be empty'}), 400

        available_camera_options = {
            option['path']: option for option in list_camera_device_options(requested_camera_device)
        }
        selected_option = available_camera_options.get(requested_camera_device)
        if selected_option is None:
            return jsonify({
                'status': 'error',
                'message': f'Invalid camera device path: {requested_camera_device}'
            }), 400
        if not selected_option.get('supported', False):
            return jsonify({
                'status': 'error',
                'message': f'Selected camera path is unsupported: {requested_camera_device}'
            }), 400

    normalized, error = _normalize_camera_settings(
        payload.get('width'),
        payload.get('height'),
        payload.get('fps')
    )
    if error:
        return jsonify({'status': 'error', 'message': error}), 400

    if requested_camera_device is not None:
        normalized['camera_device'] = requested_camera_device

    current_selected_camera = active_camera_device or camera_device_preference or CAMERA_DEVICE
    camera_device_changed = (
        requested_camera_device is not None and
        requested_camera_device != current_selected_camera
    )

    if normalized == camera_settings and not camera_device_changed:
        resolved_camera_device = active_camera_device or current_selected_camera
        return jsonify({
            'status': 'ok',
            'width': camera_settings['width'],
            'height': camera_settings['height'],
            'fps': camera_settings['fps'],
            'camera_device': resolved_camera_device,
            'camera_source_type': get_camera_source_type(resolved_camera_device),
            'message': 'Camera settings unchanged'
        })

    if not restart_camera_process(normalized):
        return jsonify({
            'status': 'error',
            'message': 'Failed to restart camera process with the requested settings'
        }), 500

    return jsonify({
        'status': 'ok',
        'width': camera_settings['width'],
        'height': camera_settings['height'],
        'fps': camera_settings['fps'],
        'camera_device': active_camera_device,
        'camera_source_type': get_camera_source_type(active_camera_device)
    })

@app.route('/status')
def status():
    speaker_volume, speaker_control = _get_speaker_volume_percent()
    selected_camera_device = active_camera_device or camera_device_preference or CAMERA_DEVICE
    return jsonify({
        'camera': camera_proc is not None and camera_proc.poll() is None,
        'audio': audio_proc is not None and audio_proc.poll() is None,
        'queue_size': 0,
        'camera_device': selected_camera_device,
        'camera_source_type': get_camera_source_type(selected_camera_device),
        'camera_device_preference': camera_device_preference,
        'camera_width': camera_settings['width'],
        'camera_height': camera_settings['height'],
        'camera_fps': camera_settings['fps'],
        'audio_player_nice': AUDIO_PLAYER_NICE,
        'face_recognition_enabled': FACE_RECOGNITION_ENABLED,
        'speaker_volume': speaker_volume,
        'speaker_control': speaker_control
    })

@app.route('/face_status', methods=['GET'])
def face_status():
    return jsonify(face_recognition_service.get_status())

@app.route('/server_audio_devices', methods=['GET'])
def server_audio_devices():
    """List server-side capture devices and output sinks."""
    selected_microphone = PULSE_CAPTURE_SOURCE_NAME
    resolved_default_source = _resolve_default_source()
    if (
        not selected_microphone
        or selected_microphone == resolved_default_source
        or selected_microphone == PULSE_ECHO_CANCEL_SOURCE_NAME
    ):
        selected_microphone = '@DEFAULT_SOURCE@'

    selected_speaker = PULSE_SINK_NAME
    # Keep initial UI selection on "Default speaker" when the active sink maps to default.
    resolved_default_sink = _resolve_default_sink()
    if selected_speaker == resolved_default_sink or selected_speaker == PULSE_ECHO_CANCEL_SINK_NAME:
        selected_speaker = '@DEFAULT_SINK@'

    # Filter out echo-cancel virtual devices from user selection
    microphones = [d for d in list_capture_devices() if d['id'] != PULSE_ECHO_CANCEL_SOURCE_NAME]
    speakers = [d for d in list_output_sinks() if d['id'] != PULSE_ECHO_CANCEL_SINK_NAME]

    return jsonify({
        'status': 'ok',
        'microphones': microphones,
        'speakers': speakers,
        'selected_microphone': selected_microphone,
        'selected_speaker': selected_speaker
    })

@app.route('/server_audio_devices/select', methods=['POST'])
def select_server_audio_devices():
    """Switch server-side microphone and/or speaker sink at runtime."""
    global PULSE_CAPTURE_SOURCE_NAME, PULSE_SINK_NAME
    payload = request.get_json(silent=True) or {}
    microphone = payload.get('microphone')
    speaker = payload.get('speaker')

    if microphone is None and speaker is None:
        return jsonify({'status': 'error', 'message': 'No microphone or speaker provided'}), 400

    with device_switch_lock:
        previous_microphone = PULSE_CAPTURE_SOURCE_NAME
        previous_speaker = PULSE_SINK_NAME
        microphone_changed = False
        speaker_changed = False

        if microphone is not None:
            valid_mics = {item['id'] for item in list_capture_devices()}
            if microphone not in valid_mics:
                return jsonify({'status': 'error', 'message': f'Invalid microphone: {microphone}'}), 400
            if microphone != PULSE_CAPTURE_SOURCE_NAME:
                PULSE_CAPTURE_SOURCE_NAME = microphone
                microphone_changed = True

        if speaker is not None:
            valid_speakers = {item['id'] for item in list_output_sinks()}
            if speaker not in valid_speakers:
                return jsonify({'status': 'error', 'message': f'Invalid speaker: {speaker}'}), 400
            if speaker != PULSE_SINK_NAME:
                PULSE_SINK_NAME = speaker
                speaker_changed = True

        if not microphone_changed and not speaker_changed:
            return jsonify({
                'status': 'ok',
                'selected_microphone': PULSE_CAPTURE_SOURCE_NAME,
                'selected_speaker': PULSE_SINK_NAME
            })

        if PULSE_ECHO_CANCEL_ENABLED:
            _teardown_pulseaudio_echo_cancel()
            if not _setup_pulseaudio_echo_cancel():
                PULSE_CAPTURE_SOURCE_NAME = previous_microphone
                PULSE_SINK_NAME = previous_speaker
                _teardown_pulseaudio_echo_cancel()
                _setup_pulseaudio_echo_cancel()
                restart_audio_capture_process()
                stop_pulse_pipeline()
                start_pulse_process()
                return jsonify({
                    'status': 'error',
                    'message': 'Failed to rebuild PulseAudio echo-cancel with selected devices'
                }), 500

            if not restart_audio_capture_process():
                return jsonify({
                    'status': 'error',
                    'message': 'Echo-cancel rebuilt, but failed to restart audio capture'
                }), 500
            stop_pulse_pipeline()
            start_pulse_process()
            print('✓ Rebuilt PulseAudio echo-cancel with updated source/sink selection')
        else:
            if microphone_changed:
                if not restart_audio_capture_process():
                    PULSE_CAPTURE_SOURCE_NAME = previous_microphone
                    restart_audio_capture_process()
                    return jsonify({
                        'status': 'error',
                        'message': f'Failed to switch microphone to {microphone}. Reverted to {previous_microphone}.'
                    }), 500
                print(f'✓ Switched microphone source to {PULSE_CAPTURE_SOURCE_NAME}')

            if speaker_changed:
                stop_pulse_pipeline()
                start_pulse_process()
                print(f'✓ Switched speaker sink to {PULSE_SINK_NAME}')

    return jsonify({
        'status': 'ok',
        'selected_microphone': PULSE_CAPTURE_SOURCE_NAME,
        'selected_speaker': PULSE_SINK_NAME
    })

if __name__ == '__main__':
    print(f"Server: https://{SERVER_HOST}:{SERVER_PORT}\n")
    try:
        app.run(
            host=SERVER_HOST,
            port=SERVER_PORT,
            debug=False,
            threaded=True,
            ssl_context=(SSL_CERT_PATH, SSL_KEY_PATH)
        )
    except KeyboardInterrupt:
        print("\nShutting down...")
    finally:
        face_recognition_service.stop()
        stop_pulse_pipeline()
        _teardown_pulseaudio_echo_cancel()
        stop_pulseaudio_daemon_if_started_by_app()
        if camera_proc:
            camera_proc.terminate()
        if audio_proc:
            audio_proc.terminate()
from flask import Flask, render_template, request, jsonify
from flask_cors import CORS
from flask_sock import Sock
import subprocess
import time
import logging
import ssl
import glob
import shutil
import threading
import struct
import re
import werkzeug.serving

from config import (
    VIDEO_ALLOWED_RESOLUTIONS,
    VIDEO_MIN_FPS,
    VIDEO_MAX_FPS,
    DEFAULT_CAMERA_WIDTH,
    DEFAULT_CAMERA_HEIGHT,
    DEFAULT_CAMERA_FPS,
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
camera_proc_lock = threading.Lock()

print("\n" + "="*60)
print("Raspberry Pi Surveillance System - Starting")
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
        return audio_proc is not None

def ensure_audio_capture_process_running():
    """Ensure audio capture process exists and is alive."""
    global audio_proc

    with audio_proc_lock:
        if audio_proc is not None and audio_proc.poll() is None:
            return True
        print('⚙ Audio capture process not running, restarting...')
        audio_proc = start_audio()
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

def start_camera(width, height, fps):
    """Start rpicam-vid outputting MJPEG directly to stdout"""
    try:
        print(f"Starting camera pipeline (rpicam-vid mjpeg, {width}x{height} @ {fps}fps)...")
        proc = subprocess.Popen(
            [
                'rpicam-vid', '-o', '-', '-t', '0',
                '--width', str(width), '--height', str(height),
                '--framerate', str(fps), '--codec', 'mjpeg'
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            bufsize=10**6
        )
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
    global camera_proc

    with camera_proc_lock:
        previous_settings = dict(camera_settings)

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
            return False

        camera_proc = new_proc

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

# Start processes
camera_proc = start_camera(
    camera_settings['width'],
    camera_settings['height'],
    camera_settings['fps']
)
audio_proc = start_audio()

@app.route('/')
def index():
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
    )

@sock.route('/video_feed')
def video_feed_socket(ws):
    """Stream JPEG frames over WebSocket from camera stdout."""
    global camera_proc
    if not ensure_camera_process_running():
        print('📹 Video socket: camera not available')
        return

    print('📹 Video socket connected')
    buffer = b''
    try:
        while True:
            proc = camera_proc
            if proc is None or proc.poll() is not None or proc.stdout is None:
                if not ensure_camera_process_running():
                    print('📹 Video socket: camera unavailable after restart attempt')
                    break
                time.sleep(0.02)
                continue

            try:
                chunk = proc.stdout.read(4096)
            except (OSError, ValueError) as read_error:
                print(f'📹 Video socket: camera stream read interrupted ({read_error}), retrying...')
                time.sleep(0.02)
                continue
            if not chunk:
                time.sleep(0.01)
                continue
            buffer += chunk

            while True:
                start = buffer.find(b'\xff\xd8')
                if start == -1:
                    break
                end = buffer.find(b'\xff\xd9', start + 2)
                if end == -1:
                    break

                jpg = buffer[start:end + 2]
                buffer = buffer[end + 2:]
                ws.send(jpg)
    except Exception as e:
        print(f'📹 Video socket closed: {e}')
    finally:
        print('📹 Video socket disconnected')


@sock.route('/audio_feed')
def audio_feed_socket(ws):
    """Stream mono microphone PCM over WebSocket."""
    global audio_proc
    if not ensure_audio_capture_process_running():
        print('🎤 Audio socket: audio process not available after restart attempt')
        return

    print('🎤 Audio socket connected')
    try:
        chunk_count = 0
        empty_reads = 0
        while True:
            chunk = audio_proc.stdout.read(4096)
            if not chunk:
                empty_reads += 1
                if ensure_audio_capture_process_running():
                    if empty_reads % 20 == 0:
                        print('🎤 Audio socket: waiting for capture data after restart...')
                    time.sleep(0.02)
                    continue
                print('🎤 Audio socket: End of stream')
                break

            empty_reads = 0

            chunk_count += 1
            if chunk_count % 50 == 0:
                print(f'🎤 Audio socket: sent chunk {chunk_count} ({len(chunk)} bytes)')

            ws.send(chunk)
    except Exception as e:
        print(f'🎤 Audio socket closed: {e}')
    finally:
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
    allowed = [
        {'width': width, 'height': height}
        for width, height in VIDEO_ALLOWED_RESOLUTIONS
    ]
    return jsonify({
        'status': 'ok',
        'width': camera_settings['width'],
        'height': camera_settings['height'],
        'fps': camera_settings['fps'],
        'allowed_resolutions': allowed,
        'fps_range': {
            'min': VIDEO_MIN_FPS,
            'max': VIDEO_MAX_FPS
        }
    })

@app.route('/camera_settings', methods=['POST'])
def set_camera_settings():
    payload = request.get_json(silent=True) or {}
    normalized, error = _normalize_camera_settings(
        payload.get('width'),
        payload.get('height'),
        payload.get('fps')
    )
    if error:
        return jsonify({'status': 'error', 'message': error}), 400

    if normalized == camera_settings:
        return jsonify({
            'status': 'ok',
            'width': camera_settings['width'],
            'height': camera_settings['height'],
            'fps': camera_settings['fps'],
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
        'fps': camera_settings['fps']
    })

@app.route('/status')
def status():
    speaker_volume, speaker_control = _get_speaker_volume_percent()
    return jsonify({
        'camera': camera_proc is not None and camera_proc.poll() is None,
        'audio': audio_proc is not None and audio_proc.poll() is None,
        'queue_size': 0,
        'camera_width': camera_settings['width'],
        'camera_height': camera_settings['height'],
        'camera_fps': camera_settings['fps'],
        'audio_player_nice': AUDIO_PLAYER_NICE,
        'speaker_volume': speaker_volume,
        'speaker_control': speaker_control
    })

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
        stop_pulse_pipeline()
        _teardown_pulseaudio_echo_cancel()
        stop_pulseaudio_daemon_if_started_by_app()
        if camera_proc:
            camera_proc.terminate()
        if audio_proc:
            audio_proc.terminate()
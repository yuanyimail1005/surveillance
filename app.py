import os
os.environ['ALSA_CARD'] = 'default'

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

print("\n" + "="*60)
print("Raspberry Pi Surveillance System - Starting")
print("="*60 + "\n")

# Audio device configuration
MICROPHONE_DEVICE = os.environ.get('MICROPHONE_DEVICE', 'default')
SPEAKER_DEVICE = os.environ.get('SPEAKER_DEVICE', 'default')
PULSE_SINK_NAME = os.environ.get('PULSE_SINK_NAME', '@DEFAULT_SINK@')
SAMPLE_RATE = 48000               # Matches successful test
MIC_CHANNELS = 1
SPEAKER_CHANNELS = 2
AUDIO_PLAYER_NICE = int(os.environ.get('AUDIO_PLAYER_NICE', '0'))
TALKBACK_HIGHPASS_HZ = 100
TALKBACK_LOWPASS_HZ = 5000
TALKBACK_WORKLET_CHUNK_SAMPLES = 8192
TALKBACK_ECHO_CANCELLATION = True
TALKBACK_NOISE_SUPPRESSION = True
TALKBACK_AUTO_GAIN_CONTROL = False
TALKBACK_LATENCY_SECONDS = 0.02
try:
    TALKBACK_PLAYBACK_GAIN = float(os.environ.get('TALKBACK_PLAYBACK_GAIN', '5.0'))
except ValueError:
    TALKBACK_PLAYBACK_GAIN = 5.0
TALKBACK_PLAYBACK_GAIN = max(0.1, min(12.0, TALKBACK_PLAYBACK_GAIN))
pulseaudio_started_by_app = False
device_switch_lock = threading.Lock()
audio_proc_lock = threading.Lock()

def list_capture_devices():
    """Return available ALSA capture devices plus default."""
    devices = [{'id': 'default', 'name': 'Default microphone', 'kind': 'default'}]
    try:
        result = subprocess.run(['arecord', '-l'], capture_output=True, text=True)
        if result.returncode != 0:
            return devices

        pattern = re.compile(r'card\s+(\d+):\s*([^\[]+)\[([^\]]+)\],\s*device\s+(\d+):\s*([^\[]+)\[([^\]]*)\]')
        seen = set(['default'])
        for raw_line in result.stdout.splitlines():
            line = raw_line.strip()
            match = pattern.search(line)
            if not match:
                continue
            card = match.group(1)
            card_desc = match.group(3).strip()
            device = match.group(4)
            device_desc = match.group(6).strip()
            device_id = f'plughw:{card},{device}'
            if device_id in seen:
                continue
            seen.add(device_id)
            label = card_desc if not device_desc else f'{card_desc} / {device_desc}'
            devices.append({'id': device_id, 'name': label, 'kind': 'alsa-capture'})
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

def list_output_sinks():
    """Return available PulseAudio sinks plus default."""
    sinks = [{'id': '@DEFAULT_SINK@', 'name': 'Default speaker', 'kind': 'default'}]
    try:
        result = subprocess.run(['pactl', 'list', 'short', 'sinks'], capture_output=True, text=True)
        if result.returncode != 0:
            return sinks

        seen = set(['@DEFAULT_SINK@'])
        for raw_line in result.stdout.splitlines():
            parts = raw_line.split('\t')
            if len(parts) < 2:
                continue
            sink_name = parts[1].strip()
            if not sink_name or sink_name in seen:
                continue
            seen.add(sink_name)
            description = parts[1].strip()
            if len(parts) >= 5 and parts[4].strip():
                description = parts[4].strip()
            sinks.append({'id': sink_name, 'name': description, 'kind': 'pulseaudio-sink'})
    except Exception as e:
        print(f'Error listing output sinks: {e}')
    return sinks

def restart_audio_capture_process():
    """Restart arecord process with the current microphone device."""
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
    """Ensure arecord process exists and is alive."""
    global audio_proc

    with audio_proc_lock:
        if audio_proc is not None and audio_proc.poll() is None:
            return True
        print('⚙ Audio capture process not running, restarting...')
        audio_proc = start_audio()
        return audio_proc is not None

def _probe_capture_device_busy(device_id):
    """Best-effort check whether opening a capture device reports busy."""
    try:
        result = subprocess.run(
            [
                'arecord',
                '-D', device_id,
                '-f', 'S16_LE',
                '-r', str(SAMPLE_RATE),
                '-c', str(MIC_CHANNELS),
                '-d', '1',
                '/dev/null'
            ],
            capture_output=True,
            text=True,
            timeout=3
        )
        if result.returncode == 0:
            return False
        combined = f"{result.stdout}\n{result.stderr}".lower()
        return 'device or resource busy' in combined
    except Exception:
        return False

def find_pulse_sink(search_vendor='1908'):
    """Find PulseAudio sink name matching the USB speaker vendor ID."""
    try:
        result = subprocess.run(['pactl', 'list', 'short', 'sinks'],
                                capture_output=True, text=True)
        if result.returncode != 0:
            return None
        for line in result.stdout.splitlines():
            parts = line.split()
            if len(parts) >= 2 and search_vendor in parts[1]:
                return parts[1]
    except Exception as e:
        print(f"Error finding PulseAudio sink: {e}")
    return None

def find_usb_device(device_type, search_name=None):
    """Find USB device card number"""
    try:
        if device_type == "playback":
            cmd = ["aplay", "-l"]
        else:
            cmd = ["arecord", "-l"]

        result = subprocess.run(cmd, capture_output=True, text=True)

        for line in result.stdout.split('\n'):
            if 'card' in line and ':' in line:
                # Extract card number
                parts = line.split('card ')
                if len(parts) > 1:
                    card_num = parts[1].split(':')[0].strip()

                    # Match by search name if provided
                    if search_name:
                        if search_name.lower() in line.lower():
                            return card_num
                    else:
                        # Return first USB device
                        if 'USB' in line or 'usb' in line.lower():
                            return card_num

        return None
    except Exception as e:
        print(f"Error finding {device_type} device: {e}")
        return None

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

speaker_card = find_usb_device("playback")
microphone_card = find_usb_device("capture")

if speaker_card:
    print(f"✓ Speaker card detected: {speaker_card}")
else:
    print("⚠ Speaker card not detected")

pulse_sink = find_pulse_sink()
if pulse_sink:
    print(f"✓ PulseAudio sink detected: {pulse_sink}")
else:
    print("⚠ PulseAudio sink not auto-detected")

if microphone_card:
    print(f"✓ Microphone card detected: {microphone_card}")
else:
    print("⚠ Microphone card not detected")

print(f"Using microphone device: {MICROPHONE_DEVICE}")
print(f"Using speaker sink: {PULSE_SINK_NAME}")

print()

SPEAKER_VOLUME_CONTROLS = ('Speaker', 'PCM', 'Master', 'Headphone')

def _get_speaker_card_number():
    """Resolve ALSA card number from detected speaker settings."""
    if speaker_card:
        return str(speaker_card)

    if SPEAKER_DEVICE.startswith('hw:'):
        # SPEAKER_DEVICE is like hw:<card>,<device>
        device_part = SPEAKER_DEVICE[3:]
        card_part = device_part.split(',')[0].strip()
        if card_part:
            return card_part

    return None

def _get_speaker_volume_percent():
    """Read current speaker volume percent using amixer."""
    card = _get_speaker_card_number()
    if card is None:
        return None, None

    for control in SPEAKER_VOLUME_CONTROLS:
        result = subprocess.run(
            ['amixer', '-c', card, 'sget', control],
            capture_output=True,
            text=True
        )
        if result.returncode != 0:
            continue

        match = re.search(r'\[(\d{1,3})%\]', result.stdout)
        if match:
            return int(match.group(1)), control

    return None, None

def _set_speaker_volume_percent(volume_percent):
    """Set speaker volume percent using amixer, trying common controls."""
    card = _get_speaker_card_number()
    if card is None:
        return None

    volume_percent = max(0, min(100, int(volume_percent)))
    for control in SPEAKER_VOLUME_CONTROLS:
        result = subprocess.run(
            ['amixer', '-c', card, 'sset', control, f'{volume_percent}%'],
            capture_output=True,
            text=True
        )
        if result.returncode == 0:
            return control

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

def start_camera():
    """Start rpicam-vid outputting MJPEG directly to stdout"""
    try:
        print("Starting camera pipeline (rpicam-vid mjpeg)...")
        proc = subprocess.Popen(
            [
                'rpicam-vid', '-o', '-', '-t', '0',
                '--width', '1920', '--height', '1080',
                '--framerate', '25', '--codec', 'mjpeg'
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

def start_audio():
    """Start audio recording from selected microphone device."""
    try:
        print(f"Starting audio recording from microphone ({MICROPHONE_DEVICE})...")
        proc = subprocess.Popen(
            [
                'arecord',
                '-D', MICROPHONE_DEVICE,
                '-f', 'S16_LE',
                '-r', str(SAMPLE_RATE),
                '-c', str(MIC_CHANNELS),
                '-'
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=4096
        )

        # Give arecord a brief moment so immediate open failures are caught.
        time.sleep(0.2)
        if proc.poll() is not None:
            stderr = proc.stderr.read().decode('utf-8', errors='ignore')
            print(f"⚠ Audio process died: {stderr}\n")
            return None

        print(f"✓ Audio recording started (Mono, {SAMPLE_RATE}Hz)\n")
        return proc
    except Exception as e:
        print(f"⚠ Audio failed: {e}\n")
        return None

# Start processes
camera_proc = start_camera()
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
    )

@sock.route('/video_feed')
def video_feed_socket(ws):
    """Stream JPEG frames over WebSocket from camera stdout."""
    global camera_proc
    if not camera_proc or camera_proc.poll() is not None:
        print('📹 Video socket: camera not available')
        return

    print('📹 Video socket connected')
    buffer = b''
    try:
        while True:
            chunk = camera_proc.stdout.read(4096)
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

@app.route('/status')
def status():
    speaker_volume, speaker_control = _get_speaker_volume_percent()
    return jsonify({
        'camera': camera_proc is not None and camera_proc.poll() is None,
        'audio': audio_proc is not None and audio_proc.poll() is None,
        'queue_size': 0,
        'audio_player_nice': AUDIO_PLAYER_NICE,
        'speaker_volume': speaker_volume,
        'speaker_control': speaker_control
    })

@app.route('/server_audio_devices', methods=['GET'])
def server_audio_devices():
    """List server-side capture devices and output sinks."""
    return jsonify({
        'status': 'ok',
        'microphones': list_capture_devices(),
        'speakers': list_output_sinks(),
        'selected_microphone': MICROPHONE_DEVICE,
        'selected_speaker': PULSE_SINK_NAME
    })

@app.route('/server_audio_devices/select', methods=['POST'])
def select_server_audio_devices():
    """Switch server-side microphone and/or speaker sink at runtime."""
    global MICROPHONE_DEVICE, PULSE_SINK_NAME
    payload = request.get_json(silent=True) or {}
    microphone = payload.get('microphone')
    speaker = payload.get('speaker')

    if microphone is None and speaker is None:
        return jsonify({'status': 'error', 'message': 'No microphone or speaker provided'}), 400

    with device_switch_lock:
        if microphone is not None:
            valid_mics = {item['id'] for item in list_capture_devices()}
            if microphone not in valid_mics:
                return jsonify({'status': 'error', 'message': f'Invalid microphone: {microphone}'}), 400
            if microphone != MICROPHONE_DEVICE:
                previous_microphone = MICROPHONE_DEVICE
                # If default capture is already active and the requested hw/plughw
                # endpoint reports busy, treat it as the same underlying device alias
                # and avoid a disruptive restart.
                if (
                    previous_microphone == 'default'
                    and (microphone.startswith('hw:') or microphone.startswith('plughw:'))
                    and audio_proc is not None
                    and audio_proc.poll() is None
                    and _probe_capture_device_busy(microphone)
                ):
                    MICROPHONE_DEVICE = microphone
                    print(
                        f'✓ Microphone selection {microphone} appears to alias active default device; '
                        'keeping current capture stream.'
                    )
                else:
                    MICROPHONE_DEVICE = microphone
                    if not restart_audio_capture_process():
                        MICROPHONE_DEVICE = previous_microphone
                        restart_audio_capture_process()
                        return jsonify({
                            'status': 'error',
                            'message': f'Failed to switch microphone to {microphone}. Reverted to {previous_microphone}.'
                        }), 500
                    print(f'✓ Switched microphone to {MICROPHONE_DEVICE}')

        if speaker is not None:
            valid_speakers = {item['id'] for item in list_output_sinks()}
            if speaker not in valid_speakers:
                return jsonify({'status': 'error', 'message': f'Invalid speaker: {speaker}'}), 400
            if speaker != PULSE_SINK_NAME:
                previous_speaker = PULSE_SINK_NAME
                PULSE_SINK_NAME = speaker
                # If the previous selection was @DEFAULT_SINK@ and the new named sink
                # resolves to the same underlying sink, skip the disruptive restart.
                if (
                    previous_speaker == '@DEFAULT_SINK@'
                    and _resolve_default_sink() == speaker
                    and pulse_proc is not None
                    and pulse_proc.poll() is None
                ):
                    print(
                        f'✓ Speaker selection {speaker} aliases the active default sink; '
                        'keeping current playback stream.'
                    )
                else:
                    stop_pulse_pipeline()
                    start_pulse_process()
                    print(f'✓ Switched speaker sink to {PULSE_SINK_NAME}')

    return jsonify({
        'status': 'ok',
        'selected_microphone': MICROPHONE_DEVICE,
        'selected_speaker': PULSE_SINK_NAME
    })

if __name__ == '__main__':
    print("Server: https://0.0.0.0:5000\n")
    try:
        app.run(
            host='0.0.0.0',
            port=5000,
            debug=False,
            threaded=True,
            ssl_context=('cert.pem', 'key.pem')
        )
    except KeyboardInterrupt:
        print("\nShutting down...")
    finally:
        stop_pulse_pipeline()
        stop_pulseaudio_daemon_if_started_by_app()
        if camera_proc:
            camera_proc.terminate()
        if audio_proc:
            audio_proc.terminate()
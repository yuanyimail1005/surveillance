import os
os.environ['ALSA_CARD'] = 'default'

from flask import Flask, render_template, Response, request, jsonify
from flask_cors import CORS
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
import stat

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

print("\n" + "="*60)
print("Raspberry Pi Surveillance System - Starting")
print("="*60 + "\n")

# Audio device configuration
MICROPHONE_DEVICE = 'plughw:2,0'  # USB Microphone (card 3)
SPEAKER_DEVICE = 'plughw:3,0'       # USB Speaker (card 2)
PULSE_SINK_NAME = os.environ.get('PULSE_SINK_NAME', '@DEFAULT_SINK@')
SAMPLE_RATE = 48000               # Matches successful test
MIC_CHANNELS = 1
SPEAKER_CHANNELS = 2
AUDIO_PLAYER_NICE = int(os.environ.get('AUDIO_PLAYER_NICE', '0'))
pulseaudio_started_by_app = False

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

speaker_card = find_usb_device("playback", "0x1908")
microphone_card = find_usb_device("capture", "PnP")

if speaker_card:
    SPEAKER_DEVICE = f'plughw:{speaker_card},0'
    print(f"✓ Speaker found: card {speaker_card}")
else:
    print(f"⚠ Speaker not found, using default: {SPEAKER_DEVICE}")

pulse_sink = find_pulse_sink()
if pulse_sink:
    PULSE_SINK_NAME = pulse_sink
    print(f"✓ PulseAudio sink found: {PULSE_SINK_NAME}")
else:
    print(f"⚠ PulseAudio sink not found, using default: {PULSE_SINK_NAME}")

if microphone_card:
    MICROPHONE_DEVICE = f'plughw:{microphone_card},0'
    print(f"✓ Microphone found: card {microphone_card}")
else:
    MICROPHONE_DEVICE = MICROPHONE_DEVICE
    print(f"⚠ Microphone not found, using default: {MICROPHONE_DEVICE}")

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
PULSE_FIFO_PATH = '/tmp/surveillance_pulse_fifo'
pulse_proc = None
pulse_fifo_fd = None
pulse_write_lock = threading.Lock()

def start_pulse_pipeline():
    """Start one PulseAudio playback process and feed it through a named FIFO."""
    global pulse_proc, pulse_fifo_fd

    try:
        if os.path.exists(PULSE_FIFO_PATH):
            mode = os.stat(PULSE_FIFO_PATH).st_mode
            if not stat.S_ISFIFO(mode):
                try:
                    os.remove(PULSE_FIFO_PATH)
                except OSError:
                    pass
        if not os.path.exists(PULSE_FIFO_PATH):
            os.mkfifo(PULSE_FIFO_PATH, 0o600)

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
                    '--stream-name', 'surveillance-speaker',
                    PULSE_FIFO_PATH
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                bufsize=0
            )

        if pulse_fifo_fd is None:
            # Blocking open/write ensures each queued chunk is fully delivered.
            pulse_fifo_fd = os.open(PULSE_FIFO_PATH, os.O_WRONLY)

        print(f"✓ PulseAudio FIFO pipeline started (PID: {pulse_proc.pid}, sink: {PULSE_SINK_NAME})")
        return True
    except Exception as e:
        print(f"✗ Failed to start PulseAudio pipeline: {e}")
        return False


def _restart_pulse_pipeline():
    """Best-effort restart when PulseAudio playback exits or pipe breaks."""
    stop_pulse_pipeline(remove_fifo=False)
    return start_pulse_pipeline()


def write_to_pulse_fifo(audio_bytes):
    """Write all bytes to PulseAudio FIFO."""
    global pulse_fifo_fd

    if not audio_bytes:
        return True

    if pulse_proc is None or pulse_proc.poll() is not None or pulse_fifo_fd is None:
        if not _restart_pulse_pipeline():
            return False

    # Flask can handle concurrent requests; serialize FIFO writes to keep audio frames ordered.
    with pulse_write_lock:
        view = memoryview(audio_bytes)
        offset = 0
        while offset < len(view):
            try:
                written = os.write(pulse_fifo_fd, view[offset:])
                if written <= 0:
                    return False
                print(f"   ↳ PulseAudio FIFO write wrote {written} bytes")
                offset += written
            except BrokenPipeError:
                print("⚠ PulseAudio FIFO broke, restarting pipeline")
                if not _restart_pulse_pipeline():
                    return False
            except OSError as e:
                print(f"⚠ PulseAudio FIFO write error: {e}")
                return False

    return True


def stop_pulse_pipeline(remove_fifo=True):
    """Stop FIFO writer and terminate the PulseAudio playback process."""
    global pulse_proc, pulse_fifo_fd

    if pulse_fifo_fd is not None:
        try:
            os.close(pulse_fifo_fd)
        except OSError:
            pass
        pulse_fifo_fd = None

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

    if remove_fifo:
        try:
            if os.path.exists(PULSE_FIFO_PATH):
                os.remove(PULSE_FIFO_PATH)
        except OSError:
            pass

if start_pulse_pipeline():
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
    """Start audio recording from USB microphone"""
    try:
        print(f"Starting audio recording from USB microphone ({MICROPHONE_DEVICE})...")
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
        
        time.sleep(0.)
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
    return render_template('index.html')

@app.route('/video_feed')
def video_feed():
    """Stream video frames by reading MJPEG from camera process stdout"""
    global camera_proc
    if not camera_proc or camera_proc.poll() is not None:
        print("📹 Camera: process not available")
        return Response("Camera not available", status=500)

    def generate():
        buffer = b''
        boundary = b'--frame\r\n'
        try:
            while True:
                chunk = camera_proc.stdout.read(4096)
                if not chunk:
                    time.sleep(0.01)
                    continue
                buffer += chunk

                # Extract complete JPEGs from the stream using SOI/EOI markers
                while True:
                    start = buffer.find(b'\xff\xd8')
                    end = buffer.find(b'\xff\xd9', start + 2)
                    if start != -1 and end != -1:
                        jpg = buffer[start:end + 2]
                        buffer = buffer[end + 2:]
                        yield (boundary +
                               b'Content-Type: image/jpeg\r\n\r\n' +
                               jpg + b'\r\n')
                    else:
                        break
        except GeneratorExit:
            return
        except Exception as e:
            print(f"📹 Video stream error: {e}")
            time.sleep(0.1)

    return Response(generate(), mimetype='multipart/x-mixed-replace; boundary=frame')

@app.route('/audio_feed')
def audio_feed():
    """Stream audio from microphone"""
    if not audio_proc:
        print("🎤 Audio: No audio process available")
        return Response("Audio not available", status=500)
    
    def generate():
        try:
            chunk_count = 0
            while True:
                chunk = audio_proc.stdout.read(4096)
                if not chunk:
                    print("🎤 Audio: End of stream")
                    break
                
                chunk_count += 1
                if chunk_count % 50 == 0:
                    print(f"🎤 Audio: Sent chunk {chunk_count} ({len(chunk)} bytes)")
                
                yield chunk
        except Exception as e:
            print(f"🎤 Audio stream error: {e}")
    
    return Response(generate(), mimetype=f'audio/L16; rate={SAMPLE_RATE}')

def convert_mono_to_stereo(mono_data):
    """Convert mono audio to stereo by duplicating samples"""
    
    # Convert bytes to mono samples
    mono_samples = struct.unpack(f'<{len(mono_data)//2}h', mono_data)
    
    # Create stereo by interleaving left and right (both same)
    stereo_bytes = b''
    for sample in mono_samples:
        # Write left channel (mono sample)
        stereo_bytes += struct.pack('<h', sample)
        # Write right channel (duplicate)
        stereo_bytes += struct.pack('<h', sample)
    
    return stereo_bytes

@app.route('/play_audio', methods=['POST'])
def play_audio():
    """Play audio on speaker by writing directly to the PulseAudio FIFO."""
    try:
        mono_data = request.data
        if not mono_data:
            return jsonify({'status': 'ok'})
        
        print(f"📥 Received audio: {len(mono_data)} bytes")
        
        # Convert mono to stereo
        stereo_data = convert_mono_to_stereo(mono_data)
        
        print(f"🔄 Converted: {len(mono_data)} → {len(stereo_data)} bytes")

        if not write_to_pulse_fifo(stereo_data):
            return jsonify({'status': 'error', 'message': 'Audio write failed'}), 503

        print(f"📤 Wrote to PulseAudio FIFO ({len(stereo_data)} bytes)\n")
        
        return jsonify({'status': 'ok'})
    except Exception as e:
        print(f"❌ Play audio endpoint error: {e}")
        return jsonify({'status': 'error', 'message': str(e)}), 500


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
import pyaudio

p = pyaudio.PyAudio()
print("Available audio devices:")
for i in range(p.get_device_count()):
    info = p.get_device_info_by_index(i)
    print(f"{i}: {info['name']} - Channels: {info['maxInputChannels']}/{info['maxOutputChannels']}")

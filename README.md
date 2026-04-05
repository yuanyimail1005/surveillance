# Surveillance

A surveillance system with camera, speaker, and microphone running on Raspberry Pi.

## Overview

This project provides a lightweight surveillance solution that leverages the Raspberry Pi's hardware capabilities to capture video, record audio, and play back alerts through a connected speaker.

## Features

- **Camera**: Live video capture and recording using a Raspberry Pi camera module or USB webcam
- **Microphone**: Audio input for sound detection and recording
- **Speaker**: Audio output for alerts and two-way communication

## Hardware Requirements

- Raspberry Pi (3B+ or newer recommended)
- Raspberry Pi Camera Module or USB webcam
- USB microphone or USB sound card with microphone
- Speaker (3.5mm audio jack or USB)
- MicroSD card (16 GB or larger recommended)

## Software Requirements

- Raspberry Pi OS (Bullseye or newer)
- Python 3.7+

## Setup

1. **Clone the repository**

   ```bash
   git clone https://github.com/yuanyimail1005/surveillance.git
   cd surveillance
   ```

2. **Install dependencies**

   ```bash
   pip install -r requirements.txt
   ```

3. **Enable the camera interface**

   Run `sudo raspi-config`, navigate to *Interface Options* → *Camera*, and enable it. Reboot the Pi.

4. **Configure the system**

   Edit the configuration file to set your preferred settings (resolution, frame rate, audio device, etc.).

## Usage

Start the surveillance system:

```bash
python main.py
```

## License

This project is open source. See [LICENSE](LICENSE) for details.

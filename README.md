# 🚁 HS260 Drone Reverse Engineering & Browser Streaming

Complete reverse engineering of the HS260 drone protocol with real-time H.264 video streaming to web browsers.

## 🎯 Features

- ✅ **Full Flight Control** - Reverse engineered UDP protocol (20Hz control loop)
- ✅ **Live Video Streaming** - H.264 720p @ 22-28 FPS
- ✅ **Browser Viewer** - WebCodecs hardware-accelerated decoding
- ✅ **HTTP REST API** - Control drone from any device
- ✅ **WebSocket Streaming** - Real-time video over WebSocket
- ✅ **Android App** - Native drone controller with video preview

## 🚀 Quick Start

### Prerequisites

- Android device (for drone control)
- ADB installed and configured
- Chrome/Edge browser (for WebCodecs support)
- HS260 drone connected to WiFi

### Setup

1. **Install Android App**
```bash
cd DroneController
./gradlew installDebug
```

2. **Start the app and connect to drone**
   - Launch "Drone Controller" app on Android
   - Connect to drone WiFi network
   - Tap "Connect" in the app

3. **Enable ADB port forwarding**
```bash
adb forward tcp:9000 tcp:9000
```

4. **Open browser viewer**
```bash
open h264-stream.html
# Or navigate to: file:///path/to/h264-stream.html
```

## 📡 API Endpoints

Base URL: `http://localhost:9000`

### REST API

- `GET /api/status` - Drone status (connected, flying, frame count, etc.)
- `POST /api/takeoff` - Takeoff command
- `POST /api/land` - Land command
- `POST /api/connect` - Connect to drone
- `POST /api/disconnect` - Disconnect from drone

### WebSocket

- `ws://localhost:9000/stream` - H.264 video stream (binary frames)

### Examples

```bash
# Check status
curl http://localhost:9000/api/status | jq

# Takeoff
curl -X POST http://localhost:9000/api/takeoff

# Land
curl -X POST http://localhost:9000/api/land
```

## 🎮 Control

Use the Android app for flight control:
- Left stick: Throttle (up/down) and Yaw (rotate)
- Right stick: Pitch (forward/back) and Roll (left/right)

## 📺 Video Streaming Architecture

```
┌─────────┐  UDP 1563   ┌─────────┐  WebSocket  ┌─────────┐
│  Drone  │────────────▶│ Android │────────────▶│ Browser │
│  H.264  │   Raw NAL   │  App    │  Binary WS  │WebCodecs│
└─────────┘             └─────────┘             └─────────┘
```

**Flow:**
1. Drone streams H.264 NAL units via UDP port 1563
2. Android app receives and caches SPS/PPS
3. WebSocket server forwards NAL units to browser clients
4. New clients receive cached SPS/PPS immediately
5. Browser decodes with WebCodecs API (hardware accelerated)
6. Video rendered to HTML canvas in real-time

## 🔧 Technical Details

### Drone Protocol

**Control (UDP port 1036)**
- Packet format: `66 0A [pitch] [roll] [throttle] [yaw] 00 00 FF FF 00 00 00 00 00 00 00 00 00 99`
- Update rate: 50ms (20Hz)
- Values: 0x80 = neutral, 0x00 = min, 0xFF = max

**Video (UDP port 1563)**
- Format: H.264 NAL units (raw, no start codes)
- Resolution: 1280x720
- Frame rate: ~25 FPS
- NAL types: SPS (7), PPS (8), I-frames (5), P-frames (1)

**Handshake (TCP port 8888)**
- Connect sequence: `0xD8 0xC0 0xD9`
- Response: `0xDA 0xC0 0xDA` (success)

### Video Decoding

**Android:**
- MediaCodec with hardware Surface rendering
- Receives NAL units from native SDL library
- Forwards to WebSocket (length-prefixed format)

**Browser:**
- WebCodecs VideoDecoder API
- avcC configuration with SPS/PPS description box
- Length-prefixed NAL units (4-byte big-endian length)
- Hardware acceleration when available

### WebSocket Protocol

- Upgrade from HTTP to WebSocket (RFC 6455)
- Binary frames (opcode 0x82)
- Automatic SPS/PPS caching for new clients
- Frame encoding: FIN bit + opcode + length (1/2/8 bytes) + payload

## 📁 Project Structure

```
.
├── DroneController/           # Android app
│   ├── app/src/main/java/com/drone/controller/
│   │   ├── MainActivity.java       # Main activity, UDP control & video
│   │   ├── DebugApiServer.java     # HTTP API + WebSocket server
│   │   └── VideoDecoder.java       # H.264 MediaCodec decoder
│   └── app/libs/
│       └── libsdl-video-decoder.so # Native video processing
├── h264-stream.html          # Browser video viewer
├── debug-monitor.py          # Python log monitor
└── README.md                 # This file
```

## 🐛 Troubleshooting

**No video in browser:**
1. Check ADB forwarding: `adb forward --list`
2. Verify app is running: `adb shell dumpsys activity | grep drone`
3. Check WebSocket connection in browser console
4. Ensure drone is streaming (check app shows video)

**Decoder errors:**
- NAL units must be length-prefixed (4-byte big-endian)
- SPS/PPS required before decoding frames
- Browser must support WebCodecs API (Chrome 94+)

**Connection issues:**
- Ensure drone WiFi is connected
- Check firewall settings on Android
- Verify port 9000 is forwarded via ADB

## 📊 Performance

- **Control latency:** ~50ms (20Hz update rate)
- **Video latency:** ~100-200ms (network + decode)
- **Frame rate:** 22-28 FPS
- **Bandwidth:** ~2-4 Mbps
- **CPU (Android):** ~15-25% (video decode)
- **CPU (Browser):** ~5-10% (hardware decode)

## 🎓 What I Learned

- Reverse engineering UDP protocols with Wireshark
- H.264 NAL unit structure (SPS, PPS, I/P frames)
- Android MediaCodec API for hardware video decoding
- WebCodecs API for browser-based video decoding
- WebSocket binary protocol implementation
- avcC vs Annex B format conversion
- Real-time video streaming optimization

## 📝 License

MIT - This is a reverse engineering project for educational purposes.

## 🙏 Acknowledgments

- HS260 drone for being hackable
- WebCodecs team for amazing browser video APIs
- Android MediaCodec for hardware acceleration

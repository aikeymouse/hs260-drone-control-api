#!/usr/bin/env python3
"""
Vision processing server with MJPEG output.

Receives H.264 from drone, applies OpenCV processing (feature detection, tracking),
and streams processed results as MJPEG to web browser.

Architecture:
  WebSocket (H.264) → Decoder → OpenCV Processing → MJPEG HTTP Server → Browser
"""

import threading
import queue
import websocket
import av
import numpy as np
import cv2
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
import io


class VisionProcessor:
    """Processes video frames with OpenCV and outputs MJPEG stream."""
    
    def __init__(self, websocket_url="ws://localhost:9000/stream"):
        self.websocket_url = websocket_url
        self.frame_queue = queue.Queue(maxsize=2)  # Small buffer for latest frames
        self.processed_frame = None
        self.running = False
        self.codec = None
        
        # Stats
        self.fps = 0.0
        self.frame_count = 0
        self.last_fps_time = time.time()
        self.total_frames = 0
        self.message_count = 0
        self.nal_count = 0
        
        # ORB feature detector
        self.orb = cv2.ORB_create(nfeatures=500)
        self.prev_keypoints = None
        
    def _parse_nal_units(self, data):
        """Parse length-prefixed NAL units from WebSocket message."""
        nal_units = []
        offset = 0
        
        # Parse 4-byte big-endian length-prefixed NAL units
        while offset + 4 < len(data):
            length = (data[offset] << 24) | (data[offset+1] << 16) | \
                    (data[offset+2] << 8) | data[offset+3]
            
            if length > 0 and length <= 100000 and offset + 4 + length <= len(data):
                nal_unit = data[offset + 4 : offset + 4 + length]
                nal_units.append(bytes(nal_unit))
                offset += 4 + length
            else:
                break
        
        # If parsing failed, treat entire message as one NAL unit
        if not nal_units:
            nal_units.append(bytes(data))
        
        return nal_units
    
    def _decode_nal(self, nal_data):
        """Decode H.264 NAL unit to frame."""
        try:
            if self.codec is None:
                self.codec = av.CodecContext.create('h264', 'r')
            
            # Log NAL type for debugging
            nal_type = nal_data[0] & 0x1F
            if self.nal_count < 20:
                nal_names = {1: 'P-frame', 5: 'IDR/I-frame', 6: 'SEI', 
                           7: 'SPS', 8: 'PPS', 9: 'AUD'}
                nal_name = nal_names.get(nal_type, f'Type {nal_type}')
                print(f"  NAL #{self.nal_count}: {nal_name} ({len(nal_data)} bytes)")
            self.nal_count += 1
            
            # Convert to Annex B format (add start code)
            # PyAV expects: 00 00 00 01 + NAL data
            annex_b_data = b'\x00\x00\x00\x01' + nal_data
            
            packet = av.Packet(annex_b_data)
            frames = self.codec.decode(packet)
            
            for frame in frames:
                img = frame.to_ndarray(format='rgb24')
                img = cv2.cvtColor(img, cv2.COLOR_RGB2BGR)
                return img
        except Exception as e:
            # Log errors for first few NAL units
            if self.nal_count < 10:
                print(f"  Decode error: {e.__class__.__name__}: {e}")
        return None
    
    def _process_frame(self, frame):
        """Apply OpenCV processing to frame."""
        # Detect ORB features
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        keypoints = self.orb.detect(gray, None)
        
        # Draw keypoints
        output = cv2.drawKeypoints(frame, keypoints, None, 
                                   color=(0, 255, 0), 
                                   flags=cv2.DRAW_MATCHES_FLAGS_DRAW_RICH_KEYPOINTS)
        
        # Overlay stats
        fps_text = f"FPS: {self.fps:.1f} | Features: {len(keypoints)}"
        cv2.putText(output, fps_text, (10, 30), 
                   cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)
        
        # Frame counter
        cv2.putText(output, f"Frame: {self.total_frames}", (10, 60),
                   cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)
        
        self.prev_keypoints = keypoints
        return output
    
    def _on_message(self, ws, message):
        """WebSocket message handler."""
        self.message_count += 1
        
        if self.message_count == 1:
            print(f"✓ First message received ({len(message)} bytes)")
        
        # Parse length-prefixed NAL units
        nal_units = self._parse_nal_units(message)
        
        if self.message_count <= 3:
            print(f"  Parsed {len(nal_units)} NAL unit(s) from message")
        
        # Decode each NAL unit
        for nal_data in nal_units:
            frame = self._decode_nal(nal_data)
            if frame is not None:
                if self.total_frames == 0:
                    print(f"✓ First frame decoded ({frame.shape})")
                
                # Process frame with OpenCV
                processed = self._process_frame(frame)
                self.processed_frame = processed
                
                # Update FPS
                self.total_frames += 1
                self.frame_count += 1
                current_time = time.time()
                if current_time - self.last_fps_time >= 1.0:
                    self.fps = self.frame_count / (current_time - self.last_fps_time)
                    self.frame_count = 0
                    self.last_fps_time = current_time
    
    def _on_error(self, ws, error):
        """WebSocket error handler."""
        print(f"✗ WebSocket error: {error}")
    
    def _on_close(self, ws, close_status_code, close_msg):
        """WebSocket close handler."""
        print("WebSocket connection closed")
        self.running = False
    
    def _on_open(self, ws):
        """WebSocket open handler."""
        print("✓ Connected to drone video stream")
        print("  Listening for H.264 NAL units...")
        self.running = True
    
    def _websocket_thread(self):
        """WebSocket receive thread."""
        ws = websocket.WebSocketApp(
            self.websocket_url,
            on_open=self._on_open,
            on_message=self._on_message,
            on_error=self._on_error,
            on_close=self._on_close
        )
        ws.run_forever()
    
    def start(self):
        """Start processing."""
        print(f"Connecting to {self.websocket_url}...")
        ws_thread = threading.Thread(target=self._websocket_thread, daemon=True)
        ws_thread.start()
        
        # Wait for connection
        timeout = 5
        start_time = time.time()
        while not self.running and (time.time() - start_time) < timeout:
            time.sleep(0.1)
        
        if not self.running:
            print("✗ Failed to connect to drone")
            return False
        
        print("✓ Vision processor started")
        print("  Waiting for video data from drone...")
        return True
    
    def get_latest_frame(self):
        """Get latest processed frame."""
        return self.processed_frame


class MJPEGStreamHandler(BaseHTTPRequestHandler):
    """HTTP handler for MJPEG streaming."""
    
    processor = None  # Will be set by server
    
    def log_message(self, format, *args):
        """Suppress HTTP request logs."""
        pass
    
    def do_GET(self):
        """Handle GET requests."""
        if self.path == '/stream':
            self.send_mjpeg_stream()
        elif self.path == '/' or self.path == '/index.html':
            self.send_html_page()
        else:
            self.send_error(404)
    
    def send_mjpeg_stream(self):
        """Stream MJPEG."""
        self.send_response(200)
        self.send_header('Content-type', 'multipart/x-mixed-replace; boundary=frame')
        self.send_header('Cache-Control', 'no-cache')
        self.end_headers()
        
        print(f"✓ MJPEG client connected from {self.client_address[0]}")
        
        try:
            while True:
                frame = self.processor.get_latest_frame()
                if frame is not None:
                    # Encode frame as JPEG
                    ret, jpeg = cv2.imencode('.jpg', frame, 
                                            [cv2.IMWRITE_JPEG_QUALITY, 85])
                    if ret:
                        # Send MJPEG frame
                        self.wfile.write(b'--frame\r\n')
                        self.send_header('Content-type', 'image/jpeg')
                        self.send_header('Content-length', len(jpeg))
                        self.end_headers()
                        self.wfile.write(jpeg.tobytes())
                        self.wfile.write(b'\r\n')
                
                time.sleep(0.033)  # ~30 FPS
                
        except (BrokenPipeError, ConnectionResetError):
            print(f"✗ MJPEG client disconnected from {self.client_address[0]}")
    
    def send_html_page(self):
        """Send HTML viewer page."""
        html = """<!DOCTYPE html>
<html>
<head>
    <title>HS260 Vision Processing</title>
    <style>
        body {
            margin: 0;
            padding: 20px;
            background: #1a1a1a;
            color: #fff;
            font-family: 'Courier New', monospace;
        }
        h1 {
            color: #0f0;
            text-align: center;
        }
        .container {
            max-width: 1400px;
            margin: 0 auto;
        }
        .video-container {
            background: #000;
            padding: 20px;
            border: 2px solid #0f0;
            border-radius: 8px;
        }
        img {
            width: 100%;
            height: auto;
            display: block;
        }
        .info {
            margin-top: 20px;
            padding: 15px;
            background: #2a2a2a;
            border-left: 4px solid #0f0;
        }
        .info p {
            margin: 5px 0;
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>🚁 HS260 Vision Processing (ORB Features)</h1>
        
        <div class="video-container">
            <img src="/stream" alt="Processed Video Stream">
        </div>
        
        <div class="info">
            <p><strong>Status:</strong> <span style="color: #0f0;">● LIVE</span></p>
            <p><strong>Processing:</strong> ORB feature detection (500 features)</p>
            <p><strong>Stream:</strong> MJPEG @ ~30 FPS</p>
            <p><strong>Overlay:</strong> Green circles = detected features</p>
        </div>
    </div>
</body>
</html>"""
        self.send_response(200)
        self.send_header('Content-type', 'text/html')
        self.end_headers()
        self.wfile.write(html.encode())


def main():
    """Main entry point."""
    print("=== HS260 Vision Processing Server ===\n")
    
    # Create vision processor
    processor = VisionProcessor()
    
    # Start processing
    if not processor.start():
        print("Failed to start. Make sure:")
        print("  1. Android app is running")
        print("  2. Port forwarding: adb forward tcp:9000 tcp:9000")
        print("  3. Drone is connected")
        return
    # Wait for first frame
    print("\nWaiting for first frame (timeout: 30s)...")
    timeout = 30
    start = time.time()
    while processor.get_latest_frame() is None and (time.time() - start) < timeout:
        time.sleep(0.1)
    
    if processor.get_latest_frame() is None:
        print("✗ No frames received from drone after 30 seconds")
        print("  Make sure video is streaming in Android app")
        return
    
    print("✓ Receiving and processing frames\n")
    
    # Start MJPEG HTTP server
    MJPEGStreamHandler.processor = processor
    server = HTTPServer(('localhost', 8080), MJPEGStreamHandler)
    
    print("=== MJPEG Server Started ===")
    print("  URL: http://localhost:8080")
    print("  Stream: http://localhost:8080/stream")
    print("\nOpen http://localhost:8080 in your browser to view processed video")
    print("Press Ctrl+C to stop\n")
    
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n\nShutting down...")
        server.shutdown()


if __name__ == "__main__":
    main()

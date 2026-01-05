#!/usr/bin/env python3
"""
Simple synchronous video viewer using threading instead of asyncio.
More reliable for OpenCV GUI display on macOS.
"""

import threading
import queue
import websocket
import av
import numpy as np
import cv2
import time
import sys


class SimpleVideoViewer:
    """Simple threaded video viewer."""
    
    def __init__(self, url="ws://localhost:9000/stream", grayscale=False):
        self.url = url
        self.grayscale = grayscale
        self.frame_queue = queue.Queue(maxsize=30)
        self.running = False
        self.codec = None
        self.fps = 0.0
        self.frame_count = 0
        self.last_fps_time = time.time()
        
    def _on_message(self, ws, message):
        """WebSocket message handler."""
        try:
            # Decode NAL unit
            if self.codec is None:
                self.codec = av.CodecContext.create('h264', 'r')
                print("✓ H.264 decoder initialized")
            
            packet = av.Packet(message)
            frames = self.codec.decode(packet)
            
            for frame in frames:
                img = frame.to_ndarray(format='rgb24')
                
                if self.grayscale:
                    img = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY)
                else:
                    img = cv2.cvtColor(img, cv2.COLOR_RGB2BGR)
                
                # Add to queue (drop old frames if full)
                if self.frame_queue.full():
                    try:
                        self.frame_queue.get_nowait()
                    except:
                        pass
                self.frame_queue.put(img)
                
                # Update FPS
                self.frame_count += 1
                current_time = time.time()
                if current_time - self.last_fps_time >= 1.0:
                    self.fps = self.frame_count / (current_time - self.last_fps_time)
                    self.frame_count = 0
                    self.last_fps_time = current_time
                
        except Exception as e:
            pass  # Ignore decode errors
    
    def _on_error(self, ws, error):
        """WebSocket error handler."""
        print(f"✗ WebSocket error: {error}")
    
    def _on_close(self, ws, close_status_code, close_msg):
        """WebSocket close handler."""
        print("WebSocket connection closed")
        self.running = False
    
    def _on_open(self, ws):
        """WebSocket open handler."""
        print("✓ Connected to video stream")
        self.running = True
    
    def _websocket_thread(self):
        """WebSocket receive thread."""
        ws = websocket.WebSocketApp(
            self.url,
            on_open=self._on_open,
            on_message=self._on_message,
            on_error=self._on_error,
            on_close=self._on_close
        )
        ws.run_forever()
    
    def run(self):
        """Run the video viewer."""
        print(f"Connecting to {self.url}...")
        
        # Start WebSocket thread
        ws_thread = threading.Thread(target=self._websocket_thread, daemon=True)
        ws_thread.start()
        
        # Wait for connection
        timeout = 5
        start_time = time.time()
        while not self.running and (time.time() - start_time) < timeout:
            time.sleep(0.1)
        
        if not self.running:
            print("✗ Failed to connect to video stream")
            return
        
        print("\n=== CONTROLS ===")
        print("Press 'q' or ESC in video window to quit")
        print("Press 's' to save frame")
        print("Press Ctrl+C in terminal to force quit\n")
        
        cv2.namedWindow('HS260 Video Stream', cv2.WINDOW_NORMAL)
        
        frames_displayed = 0
        
        try:
            while self.running:
                try:
                    # Get frame from queue (timeout to check for quit)
                    frame = self.frame_queue.get(timeout=0.1)
                    
                    if frames_displayed == 0:
                        print(f"✓ First frame received: {frame.shape}")
                        print(f"✓ OpenCV window should be visible now")
                        print(f"  (Check your Dock or Mission Control if you don't see it)\n")
                    
                    frames_displayed += 1
                    
                    # Overlay FPS
                    fps_text = f"FPS: {self.fps:.1f} | Press 'q' to quit"
                    color = 255 if self.grayscale else (0, 255, 0)
                    cv2.putText(frame, fps_text, (10, 30), 
                               cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)
                    
                    # Display frame
                    cv2.imshow('HS260 Video Stream', frame)
                    
                    # Handle keyboard
                    key = cv2.waitKey(1) & 0xFF
                    if key == ord('q') or key == 27:  # q or ESC
                        print("\nQuitting...")
                        break
                    elif key == ord('s'):
                        filename = f"frame_{int(time.time())}.png"
                        cv2.imwrite(filename, frame)
                        print(f"Saved {filename}")
                    
                    # Print status
                    if frames_displayed % 30 == 0:
                        print(f"Frames: {frames_displayed}, FPS: {self.fps:.1f}")
                    
                except queue.Empty:
                    # No frame available, check if window was closed
                    if cv2.getWindowProperty('HS260 Video Stream', cv2.WND_PROP_VISIBLE) < 1:
                        print("\nWindow closed")
                        break
                    continue
                    
        except KeyboardInterrupt:
            print("\n\nInterrupted by user (Ctrl+C)")
        finally:
            self.running = False
            cv2.destroyAllWindows()
            print(f"\nTotal frames displayed: {frames_displayed}")
            print(f"Final FPS: {self.fps:.1f}")


if __name__ == "__main__":
    grayscale = "--grayscale" in sys.argv or "-g" in sys.argv
    
    print("=== Simple H.264 Video Viewer ===")
    print("Make sure:")
    print("  1. Android app is running")
    print("  2. Port forwarding: adb forward tcp:9000 tcp:9000")
    print("  3. Drone is connected\n")
    
    viewer = SimpleVideoViewer(grayscale=grayscale)
    viewer.run()

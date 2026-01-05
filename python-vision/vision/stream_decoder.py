#!/usr/bin/env python3
"""
WebSocket H.264 Stream Decoder for HS260 Drone

Connects to the Android app's WebSocket video endpoint, receives raw H.264 NAL units,
decodes them to RGB/grayscale frames for computer vision processing.

Usage:
    python stream_decoder.py --test  # Test mode: display video only
    python stream_decoder.py         # Import as module in SLAM pipeline
"""

import asyncio
import websockets
import av
import numpy as np
import cv2
import argparse
import time
from collections import deque
from typing import Optional, Tuple


class H264StreamDecoder:
    """Decodes H.264 video stream from WebSocket to OpenCV frames."""
    
    def __init__(self, websocket_url: str = "ws://localhost:9000/stream", 
                 output_grayscale: bool = True,
                 max_buffer_size: int = 30):
        """
        Initialize H.264 stream decoder.
        
        Args:
            websocket_url: WebSocket endpoint URL
            output_grayscale: Convert frames to grayscale (for SLAM)
            max_buffer_size: Maximum frames to buffer
        """
        self.websocket_url = websocket_url
        self.output_grayscale = output_grayscale
        self.max_buffer_size = max_buffer_size
        
        self.websocket = None
        self.codec = None
        self.frame_buffer = deque(maxlen=max_buffer_size)
        self.running = False
        
        # Stats
        self.frame_count = 0
        self.last_fps_time = time.time()
        self.fps = 0.0
        
        # NAL unit types
        self.sps_pps_received = False
        self.nal_buffer = bytearray()
        
    async def connect(self):
        """Connect to WebSocket and initialize decoder."""
        print(f"Connecting to {self.websocket_url}...")
        try:
            self.websocket = await websockets.connect(
                self.websocket_url,
                max_size=None,  # No message size limit
                ping_interval=None  # Disable ping/pong
            )
            print("✓ Connected to video stream")
            self.running = True
            
            # Initialize PyAV H.264 decoder
            self.codec = av.CodecContext.create('h264', 'r')
            print("✓ H.264 decoder initialized")
            
        except Exception as e:
            print(f"✗ Connection failed: {e}")
            raise
    
    async def disconnect(self):
        """Close WebSocket connection."""
        self.running = False
        if self.websocket:
            await self.websocket.close()
            print("Disconnected from video stream")
    
    def _decode_nal_unit(self, nal_data: bytes) -> Optional[np.ndarray]:
        """
        Decode a single NAL unit to frame.
        
        Args:
            nal_data: Raw NAL unit bytes
            
        Returns:
            Decoded frame as numpy array, or None if no frame produced
        """
        try:
            # Create packet from NAL data
            packet = av.Packet(nal_data)
            
            # Decode packet
            frames = self.codec.decode(packet)
            
            # Return first frame if any
            for frame in frames:
                # Convert to numpy array
                img = frame.to_ndarray(format='rgb24')
                
                # Convert to grayscale if needed
                if self.output_grayscale:
                    img = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY)
                else:
                    img = cv2.cvtColor(img, cv2.COLOR_RGB2BGR)
                
                return img
                
        except Exception as e:
            # Decoding errors are common for incomplete frames
            pass
        
        return None
    
    def _update_fps(self):
        """Update FPS calculation."""
        current_time = time.time()
        if current_time - self.last_fps_time >= 1.0:
            self.fps = self.frame_count / (current_time - self.last_fps_time)
            self.frame_count = 0
            self.last_fps_time = current_time
    
    async def receive_frames(self):
        """
        Receive and decode frames from WebSocket.
        
        Yields:
            Decoded frames as numpy arrays (grayscale or BGR)
        """
        if not self.websocket:
            raise RuntimeError("Not connected. Call connect() first.")
        
        print("Receiving video stream...")
        
        try:
            async for message in self.websocket:
                if not self.running:
                    break
                
                # Decode NAL unit
                frame = self._decode_nal_unit(message)
                
                if frame is not None:
                    # Update stats
                    self.frame_count += 1
                    self._update_fps()
                    
                    # Add to buffer
                    self.frame_buffer.append(frame)
                    
                    # Yield frame
                    yield frame
                    
        except websockets.exceptions.ConnectionClosed:
            print("WebSocket connection closed")
        except Exception as e:
            print(f"Error receiving frames: {e}")
        finally:
            self.running = False
    
    def get_latest_frame(self) -> Optional[np.ndarray]:
        """Get the most recent frame from buffer."""
        return self.frame_buffer[-1] if self.frame_buffer else None
    
    def get_fps(self) -> float:
        """Get current frames per second."""
        return self.fps
    
    def get_resolution(self) -> Optional[Tuple[int, int]]:
        """Get video resolution (width, height)."""
        frame = self.get_latest_frame()
        if frame is not None:
            if len(frame.shape) == 2:  # Grayscale
                return (frame.shape[1], frame.shape[0])
            else:  # Color
                return (frame.shape[1], frame.shape[0])
        return None


async def test_decoder(grayscale: bool = True):
    """
    Test the H.264 decoder by displaying the video stream.
    
    Args:
        grayscale: Display in grayscale mode
    """
    decoder = H264StreamDecoder(output_grayscale=grayscale)
    
    try:
        await decoder.connect()
        
        print("\n=== CONTROLS ===")
        print("Press 'q' in video window to quit")
        print("Press 's' to save frame")
        print("Press Ctrl+C in terminal to force quit\n")
        
        frame_count = 0
        async for frame in decoder.receive_frames():
            frame_count += 1
            
            if frame_count == 1:
                print(f"✓ First frame received: {frame.shape}")
                print(f"  Creating OpenCV window...")
            
            # Overlay FPS on frame
            fps_text = f"FPS: {decoder.get_fps():.1f} | Press 'q' to quit"
            cv2.putText(frame, fps_text, (10, 30), 
                       cv2.FONT_HERSHEY_SIMPLEX, 0.7, 
                       255 if grayscale else (0, 255, 0), 2)
            
            # Display frame
            try:
                cv2.imshow('HS260 Video Stream', frame)
                if frame_count == 1:
                    print(f"✓ OpenCV window created")
                    # Try to bring window to front
                    cv2.setWindowProperty('HS260 Video Stream', cv2.WND_PROP_TOPMOST, 1)
            except Exception as e:
                print(f"✗ Error displaying frame: {e}")
                print(f"  OpenCV might not be properly configured for GUI")
                print(f"  Frames are being received but cannot be displayed")
            
            # Handle keyboard (wait 1ms for key press)
            key = cv2.waitKey(1) & 0xFF
            if key == ord('q') or key == 27:  # 'q' or ESC
                print("\nQuitting...")
                break
            elif key == ord('s'):
                # Save frame
                filename = f"frame_{int(time.time())}.png"
                cv2.imwrite(filename, frame)
                print(f"Saved {filename}")
            
            # Print status every 30 frames
            if frame_count % 30 == 0:
                print(f"Frames: {frame_count}, FPS: {decoder.get_fps():.1f}")
        
    except KeyboardInterrupt:
        print("\n\nInterrupted by user (Ctrl+C)")
    finally:
        await decoder.disconnect()
        cv2.destroyAllWindows()
        
        # Print stats
        resolution = decoder.get_resolution()
        if resolution:
            print(f"\nStream info:")
            print(f"  Resolution: {resolution[0]}x{resolution[1]}")
            print(f"  Final FPS: {decoder.get_fps():.1f}")


def main():
    """Main entry point for testing."""
    parser = argparse.ArgumentParser(description='HS260 H.264 Stream Decoder')
    parser.add_argument('--test', action='store_true', 
                       help='Run in test mode (display video)')
    parser.add_argument('--color', action='store_true',
                       help='Output color frames instead of grayscale')
    
    args = parser.parse_args()
    
    if args.test:
        print("=== H.264 Stream Decoder Test ===")
        print("Make sure:")
        print("  1. Android app is running (adb shell am start ...)")
        print("  2. Port forwarding is active (adb forward tcp:9000 tcp:9000)")
        print("  3. Drone is connected and streaming video")
        print()
        
        asyncio.run(test_decoder(grayscale=not args.color))
    else:
        print("Usage: python stream_decoder.py --test")
        print("Or import as module: from vision.stream_decoder import H264StreamDecoder")


if __name__ == "__main__":
    main()

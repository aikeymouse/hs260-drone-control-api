package com.drone.controller;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.Image;
import android.util.Log;
import android.view.Surface;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * H.264 video decoder using Android MediaCodec
 * Decodes compressed H.264 packets and renders to a Surface
 */
public class VideoDecoder {
    private static final String TAG = "VideoDecoder";
    private static final String MIME_TYPE = "video/avc"; // H.264
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int TIMEOUT_US = 10000; // 10ms timeout
    
    private MediaCodec decoder;
    private Surface surface;
    private boolean isRunning = false;
    private int frameCount = 0;
    private long lastLogTime = 0;
    private boolean configuredWithCSD = false; // Track if we've configured with codec-specific data
    private FrameCallback frameCallback;
    private int callbackFrameInterval = 1; // Call callback every N frames (1 = every frame)
    private byte[] spsData = null;
    private byte[] ppsData = null;
    private java.util.List<byte[]> bufferedFrames = new java.util.ArrayList<>();
    private boolean decoderStarted = false;
    
    public interface FrameCallback {
        void onFrameDecoded(Bitmap frame);
    }
    
    public VideoDecoder(Surface surface) {
        this.surface = surface;
    }
    
    public void setFrameCallback(FrameCallback callback, int interval) {
        this.frameCallback = callback;
        this.callbackFrameInterval = interval;
    }
    
    /**
     * Initialize the MediaCodec decoder - now waits for SPS/PPS before actually starting
     */
    public boolean start() {
        // Just set isRunning to true - actual decoder will be started when we get SPS/PPS
        isRunning = true;
        Log.d(TAG, "VideoDecoder ready, waiting for SPS/PPS");
        return true;
    }
    
    /**
     * Actually start the MediaCodec with SPS/PPS configuration
     */
    private boolean startMediaCodec() {
        try {
            // Create MediaCodec decoder for H.264
            decoder = MediaCodec.createDecoderByType(MIME_TYPE);
            
            // Configure decoder with SPS/PPS
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT);
            
            if (spsData != null && ppsData != null) {
                // Add NAL headers if missing
                byte[] spsWithHeader = spsData;
                byte[] ppsWithHeader = ppsData;
                
                if (spsData.length > 0 && spsData[0] != 0x00) {
                    spsWithHeader = new byte[spsData.length + 4];
                    spsWithHeader[0] = 0x00;
                    spsWithHeader[1] = 0x00;
                    spsWithHeader[2] = 0x00;
                    spsWithHeader[3] = 0x01;
                    System.arraycopy(spsData, 0, spsWithHeader, 4, spsData.length);
                }
                
                if (ppsData.length > 0 && ppsData[0] != 0x00) {
                    ppsWithHeader = new byte[ppsData.length + 4];
                    ppsWithHeader[0] = 0x00;
                    ppsWithHeader[1] = 0x00;
                    ppsWithHeader[2] = 0x00;
                    ppsWithHeader[3] = 0x01;
                    System.arraycopy(ppsData, 0, ppsWithHeader, 4, ppsData.length);
                }
                
                // Combine SPS and PPS into CSD-0
                ByteBuffer csd0 = ByteBuffer.allocate(spsWithHeader.length + ppsWithHeader.length);
                csd0.put(spsWithHeader);
                csd0.put(ppsWithHeader);
                csd0.flip();
                format.setByteBuffer("csd-0", csd0);
                Log.d(TAG, "Configured MediaCodec with SPS/PPS (added NAL headers if missing)");
            }
            
            decoder.configure(format, surface, null, 0);
            decoder.start();
            
            decoderStarted = true;
            configuredWithCSD = true;
            Log.d(TAG, "MediaCodec decoder started successfully with SPS/PPS");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start MediaCodec decoder", e);
            return false;
        }
    }
    
    /**
     * Decode a compressed H.264 packet
     * @param data Compressed H.264 data
     */
    public synchronized void decode(byte[] data) {
        if (!isRunning || data == null || data.length == 0) {
            return;
        }
        
        try {
            // Check for SPS/PPS NAL units
            // Frames can come with or without NAL header (00 00 00 01)
            // NAL type is in first byte (with header) or data[4] (without header)
            if (!decoderStarted && data.length > 0) {
                int nalType;
                int nalStart;
                
                // Check if frame has NAL header (00 00 00 01)
                if (data.length > 4 && data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x00 && data[3] == 0x01) {
                    nalType = data[4] & 0x1F;
                    nalStart = 0; // Include header
                } else {
                    // No NAL header, type is in first byte
                    nalType = data[0] & 0x1F;
                    nalStart = 0;
                }
                
                if (nalType == 7) { // SPS
                    spsData = data.clone();
                    Log.d(TAG, "Found SPS NAL unit, size: " + data.length + ", type byte: 0x" + String.format("%02X", data[0] & 0xFF));
                    
                    // If we have both SPS and PPS, start the decoder
                    if (ppsData != null) {
                        if (startMediaCodec()) {
                            // Process buffered frames
                            Log.d(TAG, "Processing " + bufferedFrames.size() + " buffered frames");
                            for (byte[] frame : bufferedFrames) {
                                decodeFrame(frame);
                            }
                            bufferedFrames.clear();
                        }
                    }
                    return; // Don't process SPS as a regular frame
                } else if (nalType == 8) { // PPS
                    ppsData = data.clone();
                    Log.d(TAG, "Found PPS NAL unit, size: " + data.length + ", type byte: 0x" + String.format("%02X", data[0] & 0xFF));
                    
                    // If we have both SPS and PPS, start the decoder
                    if (spsData != null) {
                        if (startMediaCodec()) {
                            // Process buffered frames
                            Log.d(TAG, "Processing " + bufferedFrames.size() + " buffered frames");
                            for (byte[] frame : bufferedFrames) {
                                decodeFrame(frame);
                            }
                            bufferedFrames.clear();
                        }
                    }
                    return; // Don't process PPS as a regular frame
                }
            }
            
            // If decoder not started yet, buffer the frame
            if (!decoderStarted) {
                if (bufferedFrames.size() < 30) { // Limit buffer size
                    bufferedFrames.add(data.clone());
                }
                return;
            }
            
            // Decoder is ready, process the frame
            decodeFrame(data);
            
        } catch (Exception e) {
            Log.e(TAG, "Error decoding frame", e);
        }
    }
    
    /**
     * Actually decode a frame (decoder must be started)
     */
    private void decodeFrame(byte[] data) {
        if (decoder == null) {
            return;
        }
        
        try {
            // Check if data needs NAL header (00 00 00 01)
            // If first byte is NAL type byte (not 00), add header
            byte[] frameData = data;
            if (data.length > 0 && data[0] != 0x00) {
                // Add NAL header
                frameData = new byte[data.length + 4];
                frameData[0] = 0x00;
                frameData[1] = 0x00;
                frameData[2] = 0x00;
                frameData[3] = 0x01;
                System.arraycopy(data, 0, frameData, 4, data.length);
            }
            
            // Get input buffer
            int inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(frameData);
                    
                    decoder.queueInputBuffer(inputBufferIndex, 0, frameData.length,
                            System.nanoTime() / 1000, 0);
                }
            } else {
                if (frameCount == 0) {
                    Log.w(TAG, "No input buffer available (first frames)");
                }
            }
            
            // Release output buffer (renders to surface automatically)
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
            if (outputBufferIndex >= 0) {
                // Capture frame for callback if set
                if (frameCallback != null && frameCount % callbackFrameInterval == 0) {
                    try {
                        Log.d(TAG, "Attempting to capture frame " + frameCount);
                        Image image = decoder.getOutputImage(outputBufferIndex);
                        if (image != null) {
                            Log.d(TAG, "Got image: " + image.getWidth() + "x" + image.getHeight());
                            Bitmap bitmap = convertImageToBitmap(image);
                            if (bitmap != null) {
                                Log.d(TAG, "Converted to bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                                frameCallback.onFrameDecoded(bitmap);
                            } else {
                                Log.w(TAG, "Failed to convert image to bitmap");
                            }
                            image.close();
                        } else {
                            Log.w(TAG, "getOutputImage returned null at frame " + frameCount);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error capturing frame for callback: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                
                // Render frame to surface
                decoder.releaseOutputBuffer(outputBufferIndex, true);
                frameCount++;
                
                // Log every 30 frames
                long now = System.currentTimeMillis();
                if (frameCount % 30 == 0) {
                    long elapsed = now - lastLogTime;
                    float fps = elapsed > 0 ? (30000.0f / elapsed) : 0;
                    Log.d(TAG, String.format("Rendered %d frames, FPS: %.1f", frameCount, fps));
                    lastLogTime = now;
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = decoder.getOutputFormat();
                Log.d(TAG, "Output format changed: " + newFormat);
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // No output available yet, this is normal
            } else {
                if (frameCount == 0 && outputBufferIndex < 0) {
                    Log.w(TAG, "Waiting for first frame, output index: " + outputBufferIndex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error decoding frame", e);
        }
    }
    
    /**
     * Stop the decoder and release resources
     */
    public synchronized void stop() {
        isRunning = false;
        decoderStarted = false;
        spsData = null;
        ppsData = null;
        bufferedFrames.clear();
        
        if (decoder != null) {
            try {
                decoder.stop();
                decoder.release();
                decoder = null;
                Log.d(TAG, "MediaCodec decoder stopped");
                
                // Give MediaCodec threads time to fully terminate
                // This prevents thread accumulation on quick reconnects
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for decoder cleanup");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping decoder", e);
            }
        }
    }
    
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * Convert MediaCodec Image (YUV format) to RGB Bitmap
     */
    private Bitmap convertImageToBitmap(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes.length < 3) {
                return null;
            }
            
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();
            
            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();
            
            byte[] nv21 = new byte[ySize + uSize + vSize];
            
            // Copy Y plane
            yBuffer.get(nv21, 0, ySize);
            
            // Convert UV planes to NV21 format
            int uvIndex = ySize;
            for (int i = 0; i < uSize; i++) {
                nv21[uvIndex++] = vBuffer.get(i);
                nv21[uvIndex++] = uBuffer.get(i);
            }
            
            // Convert NV21 to Bitmap
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, 
                image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 80, out);
            byte[] imageBytes = out.toByteArray();
            
            return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        } catch (Exception e) {
            Log.e(TAG, "Error converting image to bitmap", e);
            return null;
        }
    }
}

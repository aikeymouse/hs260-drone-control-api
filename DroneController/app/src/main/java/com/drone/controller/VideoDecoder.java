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
     * Initialize the MediaCodec decoder
     */
    public boolean start() {
        try {
            // Create MediaCodec decoder for H.264
            decoder = MediaCodec.createDecoderByType(MIME_TYPE);
            
            // Configure decoder
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT);
            decoder.configure(format, surface, null, 0);
            decoder.start();
            
            isRunning = true;
            Log.d(TAG, "MediaCodec decoder started successfully");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to create MediaCodec decoder", e);
            return false;
        }
    }
    
    /**
     * Decode a compressed H.264 packet
     * @param data Compressed H.264 data
     */
    public synchronized void decode(byte[] data) {
        if (!isRunning || decoder == null || data == null || data.length == 0) {
            return;
        }
        
        try {
            // Double-check decoder is still valid before using it
            // (isRunning might be true while stop() is being called from another thread)
            if (decoder == null) {
                return;
            }
            
            // Check for SPS/PPS NAL units (0x00 0x00 0x00 0x01 0x67 for SPS, 0x68 for PPS)
            // These are needed for MediaCodec initialization but might come in the stream
            if (!configuredWithCSD && data.length > 4) {
                if (data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x00 && data[3] == 0x01) {
                    int nalType = data[4] & 0x1F;
                    if (nalType == 7 || nalType == 8) { // SPS (7) or PPS (8)
                        Log.d(TAG, "Found SPS/PPS NAL unit, type: " + nalType);
                        configuredWithCSD = true;
                    }
                }
            }
            
            // Get input buffer
            int inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data);
                    
                    // Mark as codec config if it's SPS/PPS
                    int flags = 0;
                    if (!configuredWithCSD && data.length > 4 && data[0] == 0x00 && data[1] == 0x00) {
                        int nalType = data[4] & 0x1F;
                        if (nalType == 7 || nalType == 8) {
                            flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
                        }
                    }
                    
                    decoder.queueInputBuffer(inputBufferIndex, 0, data.length,
                            System.nanoTime() / 1000, flags);
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
        if (decoder != null) {
            try {
                decoder.stop();
                decoder.release();
                decoder = null;
                Log.d(TAG, "MediaCodec decoder stopped");
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

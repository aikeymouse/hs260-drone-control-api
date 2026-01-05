package com.drone.controller;

import android.app.Activity;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.util.Log;
import android.graphics.PixelFormat;
import org.libsdl.app.SDLActivity;
import com.h8.p.c;

public class VideoActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "VideoActivity";
    
    private SurfaceView videoView;
    private SurfaceHolder surfaceHolder;
    private TextView statusText;
    private Button backButton;
    
    private VideoDecoder videoDecoder;
    private boolean isActive = false;
    
    // Callback for receiving video frames from native library
    private final c videoFrameListener = new c() {
        private int frameCount = 0;
        
        @Override
        public void b(byte[] frameData) {
            if (!isActive) {
                return;
            }
            
            frameCount++;
            if (frameCount % 30 == 1) {
                Log.d(TAG, "Video frame " + frameCount + " received, size: " + frameData.length);
            }
            
            // Use local reference to prevent race condition
            VideoDecoder decoder = videoDecoder;
            if (decoder != null) {
                decoder.decode(frameData);
            }
        }
        
        @Override
        public void A(int result) {
            // Play result callback
            Log.d(TAG, "Play result: " + result);
        }
        
        @Override
        public void h(boolean z, boolean z2, int[] config) {
            // Configuration callback
        }
        
        @Override
        public void q(int quality) {
            // Quality callback
        }
        
        @Override
        public void w(int networkType) {
            // Network type callback
        }
        
        @Override
        public void y(int i, int i2, byte[] yuv) {
            // YUV buffer callback
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video);
        
        Log.d(TAG, "VideoActivity created");
        
        // Initialize UI
        videoView = findViewById(R.id.videoSurfaceView);
        statusText = findViewById(R.id.videoStatusText);
        backButton = findViewById(R.id.backButton);
        
        backButton.setOnClickListener(v -> finish());
        
        // Setup surface
        surfaceHolder = videoView.getHolder();
        surfaceHolder.addCallback(this);
        surfaceHolder.setFormat(PixelFormat.OPAQUE);
        
        updateStatus("Initializing...");
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "VideoActivity resumed");
        isActive = true;
        
        // Listener will be registered after decoder is created in surfaceCreated()
        if (!MainActivity.isConnectedToDrone()) {
            updateStatus("Not connected to drone");
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "VideoActivity paused");
        isActive = false;
        
        // Unregister from MainActivity
        MainActivity.setVideoListener(null);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "VideoActivity destroyed");
        
        // Clean up decoder
        if (videoDecoder != null) {
            videoDecoder.stop();
            videoDecoder = null;
        }
    }
    
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "Surface created");
        
        if (MainActivity.isConnectedToDrone()) {
            createDecoder(holder);
        } else {
            updateStatus("Not connected - no video");
        }
    }
    
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "Surface changed: " + width + "x" + height);
    }
    
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "Surface destroyed");
        
        // Stop decoder when surface is destroyed
        if (videoDecoder != null) {
            videoDecoder.stop();
            videoDecoder = null;
        }
    }
    
    private void createDecoder(SurfaceHolder holder) {
        if (videoDecoder != null) {
            Log.d(TAG, "Decoder already exists, stopping old one");
            videoDecoder.stop();
            videoDecoder = null;
        }
        
        if (holder.getSurface() == null || !holder.getSurface().isValid()) {
            Log.e(TAG, "Surface is not valid, cannot create decoder");
            updateStatus("Surface not ready");
            return;
        }
        
        Log.d(TAG, "Creating video decoder");
        videoDecoder = new VideoDecoder(holder.getSurface());
        
        if (videoDecoder.start()) {
            Log.d(TAG, "Video decoder started successfully");
            updateStatus("Video streaming");
            
            // Register with MainActivity AFTER decoder is ready
            if (MainActivity.isConnectedToDrone() && isActive) {
                MainActivity.setVideoListener(videoFrameListener);
                Log.d(TAG, "Registered with MainActivity for video frames");
            }
        } else {
            Log.e(TAG, "Failed to start video decoder");
            videoDecoder = null;
            updateStatus("Decoder failed to start");
        }
    }
    
    private void updateStatus(String status) {
        runOnUiThread(() -> {
            if (statusText != null) {
                statusText.setText(status);
            }
        });
    }
}

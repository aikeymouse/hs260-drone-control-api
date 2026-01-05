package com.drone.controller;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.gson.Gson;
import org.libsdl.app.FlySendInfo;
import org.libsdl.app.FlyReceiveInfo;
import org.libsdl.app.SDLActivity;
import com.h8.p.c;  // LiveListener interface for video frames
import java.io.*;
import java.net.*;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "DroneController";
    private static final String DRONE_IP = "192.168.100.1";
    private static final int CONTROL_PORT = 19798;  // UDP for flight control (from decompiled app!)
    private static final int VIDEO_PORT = 1563;      // Separate port for video stream
    private static final int TCP_PORT = 4646;        // TCP control channel (REQUIRED for video!)
    private static final int PACKET_SIZE = 20;       // Flight control packet size
    private static final int CONTROL_RATE_MS = 50;   // Send at 20Hz (~50ms intervals)
    
    private Socket tcpSocket;
    private DatagramSocket controlSocket;
    private DatagramSocket videoSocket;
    private InetAddress droneAddress;
    private Thread controlThread;
    private Thread videoThread;
    private boolean receivingVideo = false;
    
    private SurfaceView videoView;
    private Button btnTakeoff;
    private Button btnLand;
    private Button btnConnect;
    private Button btnDisconnect;
    private TextView tvStatus;
    private TextView tvDebug;
    
    private boolean isConnected = false;
    private boolean isFlying = false;
    private boolean sendingControl = false;
    private boolean tcpHandshakeComplete = false;  // Track TCP handshake completion
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Telemetry data
    private int batteryLevel = 0;
    private int altitude = 0;
    private boolean motorRunning = false;
    private boolean altitudeHold = false;
    private FlyReceiveInfo lastTelemetry = new FlyReceiveInfo();
    
    // Video surface for hardware rendering
    private SurfaceHolder surfaceHolder;
    private VideoDecoder videoDecoder;
    
    // Debug API server for remote control via ADB
    private DebugApiServer apiServer;
    
    // Flight control using native library
    private FlySendInfo flySendInfo;
    private Object packetLock = new Object();
    
    // Video frame statistics
    private int frameCount = 0;
    private long lastFrameTime = 0;
    
    // Video frame listener - receives decoded YUV frames from native library
    private c videoFrameListener = new c() {
        @Override
        public void A(int result) {
            // Play result callback
            logDebug("Video decoder result: " + result);
        }

        @Override
        public void b(byte[] frameData) {
            // MAIN VIDEO FRAME CALLBACK - receives H.264 encoded packets
            frameCount++;
            long now = System.currentTimeMillis();
            
            // Log NAL type on first few frames
            if (frameCount <= 5 && frameData != null && frameData.length > 0) {
                int nalType = frameData[0] & 0x1F;  // Extract NAL unit type from first byte
                StringBuilder hex = new StringBuilder("Frame " + frameCount + " (NAL type=" + nalType + ", " + frameData.length + " bytes): ");
                for (int i = 0; i < Math.min(16, frameData.length); i++) {
                    hex.append(String.format("%02X ", frameData[i] & 0xFF));
                }
                logDebug(hex.toString());
            }
            
            if (frameCount % 30 == 0) {  // Log every 30 frames
                long elapsed = now - lastFrameTime;
                float fps = elapsed > 0 ? (30000.0f / elapsed) : 0;
                logDebug(String.format("Video frames: %d, FPS: %.1f", frameCount, fps));
                lastFrameTime = now;
            }
            
            // Forward to WebSocket clients (this is filtered by native library - missing SPS/PPS)
            if (apiServer != null && frameData != null && frameData.length > 0) {
                apiServer.sendH264Packet(frameData);
                if (frameCount == 1) {
                    logDebug("First H.264 frame forwarded to WebSocket: " + frameData.length + " bytes");
                }
            }
            
            // Decode H.264 packet and render to surface
            if (videoDecoder != null && videoDecoder.isRunning()) {
                videoDecoder.decode(frameData);
            }
        }

        @Override
        public void h(boolean z, boolean z2, int[] iArr) {
            // Configuration callback
        }

        @Override
        public void q(int i) {
            // Quality callback
        }

        @Override
        public void w(int networkType) {
            // Network type callback
            logDebug("Network type changed: " + networkType);
        }

        @Override
        public void y(int width, int height, byte[] yuvData) {
            // YUV buffer callback with dimensions - MediaCodec renders to Surface
            // We don't need to process this manually, just log for debugging
            if (width > 0 && height > 0) {
                logDebug(String.format("Video resolution: %dx%d", width, height));
            }
        }
    };
    
    private static final int PERMISSION_REQUEST_CODE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        videoView = findViewById(R.id.video_view);
        btnTakeoff = findViewById(R.id.btn_takeoff);
        btnLand = findViewById(R.id.btn_land);
        btnConnect = findViewById(R.id.btn_connect);
        btnDisconnect = findViewById(R.id.btn_disconnect);
        tvStatus = findViewById(R.id.tv_status);
        tvDebug = findViewById(R.id.tv_debug);

        // Initialize video surface and video decoder
        surfaceHolder = videoView.getHolder();
        videoView.setZOrderOnTop(false); // Ensure video is rendered below UI
        videoView.setZOrderMediaOverlay(false); // Don't overlay on media
        surfaceHolder.setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                logDebug("Surface created, initializing video decoder");
                // Create and start MediaCodec decoder
                videoDecoder = new VideoDecoder(holder.getSurface());
                
                // Set frame callback for MJPEG streaming (every 2nd frame to reduce load)
                if (apiServer != null) {
                    videoDecoder.setFrameCallback(new VideoDecoder.FrameCallback() {
                        int framesSent = 0;
                        @Override
                        public void onFrameDecoded(android.graphics.Bitmap frame) {
                            apiServer.sendFrame(frame);
                            framesSent++;
                            if (framesSent % 100 == 0) {
                                logDebug("MJPEG: Sent " + framesSent + " decoded frames");
                            }
                        }
                    }, 2); // Send every 2nd frame (~15 FPS)
                }
                
                if (!videoDecoder.start()) {
                    logDebug("ERROR: Failed to start video decoder");
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                logDebug(String.format("Surface changed: %dx%d", width, height));
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                logDebug("Surface destroyed, stopping video decoder");
                if (videoDecoder != null) {
                    videoDecoder.stop();
                    videoDecoder = null;
                }
            }
        });

        btnConnect.setOnClickListener(v -> {
            if (isConnected) {
                disconnect();
            } else {
                connectToDrone();
            }
        });
        btnTakeoff.setOnClickListener(v -> sendTakeoff());
        btnLand.setOnClickListener(v -> sendLand());

        // Initialize FlySendInfo with neutral values
        initializeFlySendInfo();
        
        // Start debug API server for remote control via ADB
        startApiServer();

        // Request location permission (required to read WiFi SSID on Android 10+)
        requestLocationPermission();
    }
    
    private void requestLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 
                    PERMISSION_REQUEST_CODE);
            } else {
                // Permission already granted
                checkWiFiAndPrompt();
                updateUI();
            }
        } else {
            // No permission needed on older Android versions
            checkWiFiAndPrompt();
            updateUI();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                logDebug("Location permission granted");
                checkWiFiAndPrompt();
                updateUI();
            } else {
                updateStatus("Location permission needed to detect WiFi");
                new AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("Location permission is required to detect the drone's WiFi network.\n\nWithout it, you'll need to manually ensure you're connected to the HolyStone WiFi.")
                    .setPositiveButton("Grant Permission", (dialog, which) -> {
                        requestLocationPermission();
                    })
                    .setNegativeButton("Continue Anyway", (dialog, which) -> {
                        updateStatus("Ready (WiFi detection disabled)");
                    })
                    .show();
            }
        }
    }
    private void checkWiFiAndPrompt() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            
            if (!wifiManager.isWifiEnabled()) {
                updateStatus("WiFi is disabled");
                new AlertDialog.Builder(this)
                    .setTitle("WiFi Disabled")
                    .setMessage("Please enable WiFi to connect to the drone.")
                    .setPositiveButton("Open WiFi Settings", (dialog, which) -> {
                        startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return;
            }
            
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            String ssid = wifiInfo.getSSID().replace("\"", "");
            Log.d(TAG, "Current WiFi SSID: " + ssid);
            
            if (ssid.equals("<unknown ssid>")) {
                updateStatus("Not connected to WiFi");
                new AlertDialog.Builder(this)
                    .setTitle("No WiFi Connection")
                    .setMessage("Please connect to the drone's WiFi network (starts with 'HolyStone').")
                    .setPositiveButton("Open WiFi Settings", (dialog, which) -> {
                        startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return;
            }
            
            if (!ssid.startsWith("HolyStone")) {
                updateStatus("Wrong network: " + ssid);
                logDebug("Connected to: " + ssid);
                new AlertDialog.Builder(this)
                    .setTitle("Wrong Network")
                    .setMessage("You're connected to '" + ssid + "'.\n\nPlease connect to the drone's WiFi network (starts with 'HolyStone').")
                    .setPositiveButton("Open WiFi Settings", (dialog, which) -> {
                        startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                    })
                    .setNegativeButton("Ignore", null)
                    .show();
            } else {
                updateStatus("Ready - Connected to " + ssid);
                logDebug("Drone network detected: " + ssid);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking WiFi", e);
            updateStatus("Error checking WiFi");
        }
    }

    private void connectToDrone() {
        new Thread(() -> {
            try {
                updateStatus("Checking WiFi connection...");
                
                // Verify we're connected to WiFi
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                if (activeNetwork == null || !activeNetwork.isConnected()) {
                    updateStatus("ERROR: No network connection");
                    showToast("Please connect to drone WiFi first");
                    return;
                }
                
                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                String ssid = wifiInfo.getSSID().replace("\"", "");
                logDebug("Connected to: " + ssid);
                logDebug("IP: " + formatIP(wifiInfo.getIpAddress()));
                
                updateStatus("Connecting to drone...");
                
                // Initialize UDP socket for control
                controlSocket = new DatagramSocket();
                droneAddress = InetAddress.getByName(DRONE_IP);
                
                // Test connectivity
                if (!droneAddress.isReachable(3000)) {
                    updateStatus("ERROR: Drone not reachable");
                    showToast("Cannot reach drone at " + DRONE_IP);
                    return;
                }
                
                // Initialize with neutral values
                initializeFlySendInfo();
                
                isConnected = true;
                updateStatus("Connected! Sending keep-alive...");
                logDebug("===== CONNECTION ESTABLISHED =====");
                logDebug("UDP Target: " + DRONE_IP + ":" + CONTROL_PORT);
                logDebug("Local Port: " + controlSocket.getLocalPort());
                logDebug("Packet Size: " + PACKET_SIZE + " bytes");
                logDebug("Send Rate: " + CONTROL_RATE_MS + "ms (" + (1000/CONTROL_RATE_MS) + "Hz)");
                logDebug("Using NATIVE LIBRARY for packet encoding!");
                
                // Recreate video decoder if it was destroyed on disconnect
                if (videoDecoder == null && surfaceHolder != null && surfaceHolder.getSurface() != null) {
                    logDebug("Recreating video decoder for reconnection");
                    videoDecoder = new VideoDecoder(surfaceHolder.getSurface());
                    
                    // Set frame callback for MJPEG streaming
                    if (apiServer != null) {
                        videoDecoder.setFrameCallback(new VideoDecoder.FrameCallback() {
                            int framesSent = 0;
                            @Override
                            public void onFrameDecoded(android.graphics.Bitmap frame) {
                                apiServer.sendFrame(frame);
                                framesSent++;
                                if (framesSent % 100 == 0) {
                                    logDebug("MJPEG: Sent " + framesSent + " decoded frames");
                                }
                            }
                        }, 2); // Send every 2nd frame (~15 FPS)
                    }
                    
                    if (!videoDecoder.start()) {
                        logDebug("ERROR: Failed to start video decoder");
                    }
                }
                
                // Initialize native video library
                // Mode 1 for platform 4 (HS260 with A9-720P), Mode 0 for others
                // Platform 4 uses MediaCodec H.264 decoding, not live555
                int initResult = SDLActivity.liveInit(1);
                logDebug("Native video library initialized with mode 1 (platform 4): " + initResult);
                
                // Register video frame listener to receive decoded frames
                SDLActivity.setLiveListener(videoFrameListener);
                logDebug("Video frame listener registered");
                
                mainHandler.post(this::updateUI);
                
                // CRITICAL: Establish TCP connection FIRST (drone requires this before video!)
                try {
                    tcpSocket = new Socket();
                    tcpSocket.connect(new InetSocketAddress(droneAddress, TCP_PORT), 3000);
                    logDebug("TCP control channel established on port " + TCP_PORT);
                    
                    // Start TCP reader thread (drone may send configuration data)
                    startTcpReader();
                    
                } catch (Exception e) {
                    Log.e(TAG, "TCP connection failed", e);
                    logDebug("TCP failed: " + e.getMessage() + " (video may not work)");
                }
                
                // Start telemetry receiver FIRST (drone may send responses)
                startTelemetryReceiver();
                
                // Start continuous control loop
                startControlLoop();
                
                // CRITICAL: Wait for TCP handshake to complete before starting video!
                // Original app waits for encrypted handshake response from drone
                logDebug("Waiting for TCP handshake to complete...");
                int waitCount = 0;
                while (!tcpHandshakeComplete && waitCount < 50) {  // Wait up to 5 seconds
                    Thread.sleep(100);
                    waitCount++;
                }
                
                if (tcpHandshakeComplete) {
                    logDebug("TCP handshake complete after " + (waitCount * 100) + "ms, starting video stream...");
                } else {
                    logDebug("WARNING: TCP handshake timeout after 5s, attempting video anyway...");
                }
                
                // Start video stream AFTER TCP handshake completes
                startVideoStream();
                
            } catch (Exception e) {
                Log.e(TAG, "Connection failed", e);
                updateStatus("Connection failed: " + e.getMessage());
                mainHandler.post(this::updateUI);
            }
        }).start();
    }
    
    private void initializeFlySendInfo() {
        synchronized (packetLock) {
            flySendInfo = new FlySendInfo();
            // Set neutral/center values (128 = center for joysticks)
            flySendInfo.setRoll(128);
            flySendInfo.setPitch(128);
            flySendInfo.setAccelerate(128);  // Throttle
            flySendInfo.setYaw(128);
            flySendInfo.setOneKey(0);
            flySendInfo.setEmergencyLand(0);
            flySendInfo.setRoll360(0);
            flySendInfo.setHeadless(0);
            flySendInfo.setAltHold(0);
            flySendInfo.setFollow(0);
            flySendInfo.setGyroAdjust(0);
            flySendInfo.setSpeedLevel(1);  // Medium speed
            flySendInfo.setLight(0);
            flySendInfo.setGesture(0);
            flySendInfo.setFollowDirection(0);
            flySendInfo.setFollowType(0);
            flySendInfo.setLength(20);
            logDebug("FlySendInfo initialized with neutral values");
        }
    }
    
    private void startTelemetryReceiver() {
        // Start receiving telemetry from drone on port 19798
        new Thread(() -> {
            try {
                logDebug("===== TELEMETRY RECEIVER =====");
                logDebug("Listening for drone responses on port " + CONTROL_PORT);
                
                byte[] receiveBuffer = new byte[1024];
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                int packetCount = 0;
                
                while (isConnected) {
                    try {
                        controlSocket.receive(receivePacket);
                        int length = receivePacket.getLength();
                        
                        if (length > 0) {
                            // Parse telemetry using native method
                            SDLActivity.nativeGetFlyReceiveData(lastTelemetry, receiveBuffer, length);
                            
                            // Extract key values
                            batteryLevel = lastTelemetry.getBatVal();
                            altitude = lastTelemetry.getHeight6();
                            motorRunning = lastTelemetry.getMotorRunning() == 1;
                            altitudeHold = lastTelemetry.getAltHold() == 1;
                            
                            // Debug log every 50 packets
                            if (packetCount++ % 50 == 0) {
                                logDebug("Telemetry: Battery=" + batteryLevel + 
                                       "%, Height=" + altitude + 
                                       ", Motors=" + (motorRunning ? "ON" : "OFF") +
                                       ", AltHold=" + (altitudeHold ? "ON" : "OFF") +
                                       ", Landed=" + (lastTelemetry.getLanded() == 1) +
                                       ", TakeOff=" + (lastTelemetry.getTakeOff() == 1) +
                                       ", Headless=" + (lastTelemetry.getHeadless() == 1) +
                                       ", Gale=" + (lastTelemetry.getGale() == 1));
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        // Normal timeout, continue
                    } catch (Exception e) {
                        if (isConnected) {
                            Log.e(TAG, "Telemetry receive error", e);
                        }
                    }
                }
                
                logDebug("Telemetry receiver stopped");
            } catch (Exception e) {
                Log.e(TAG, "Telemetry receiver failed", e);
            }
        }).start();
    }
    
    private void startTcpReader() {
        new Thread(() -> {
            try {
                logDebug("===== TCP READER =====");
                logDebug("Reading TCP data from port " + TCP_PORT);
                
                java.io.InputStream inputStream = tcpSocket.getInputStream();
                java.io.OutputStream outputStream = tcpSocket.getOutputStream();
                
                // CRITICAL: Send encrypted TCP handshake FIRST (17 bytes)
                // This is THE MISSING PIECE that triggers video streaming!
                byte[] encData = new byte[17];
                byte[] handshake = SDLActivity.getEncData(encData, encData.length);
                
                if (handshake != null) {
                    // Check if encryption bypass mode
                    boolean passEnc = SDLActivity.isPassEnc();
                    logDebug("TCP: isPassEnc = " + passEnc);
                    
                    if (!passEnc) {
                        // Send encrypted handshake
                        outputStream.write(handshake);
                        outputStream.flush();
                        
                        StringBuilder hex = new StringBuilder("TCP: Sent encrypted handshake (17 bytes): ");
                        for (int i = 0; i < handshake.length; i++) {
                            hex.append(String.format("%02X ", handshake[i] & 0xFF));
                        }
                        logDebug(hex.toString());
                        
                        // CRITICAL: Wait for handshake to be processed by drone
                        // Original app waits in loop until handshake succeeds
                        Thread.sleep(500);
                        logDebug("TCP: Handshake sent, waiting for drone response...");
                    } else {
                        logDebug("TCP: Encryption bypassed (pass mode)");
                    }
                } else {
                    Log.e(TAG, "TCP: Failed to get encrypted handshake data!");
                }
                
                byte[] buffer = new byte[2048];
                boolean cmd0Received = false;
                
                while (isConnected && tcpSocket != null && !tcpSocket.isClosed()) {
                    try {
                        int bytesRead = inputStream.read(buffer);
                        if (bytesRead > 0) {
                            String data = new String(buffer, 0, bytesRead, "ISO-8859-1").trim();
                            logDebug("TCP received (" + bytesRead + " bytes): " + data);
                            
                            // CRITICAL: Send acknowledgment for CMD 0 (device info) - ONCE ONLY
                            if (data.contains("\"CMD\": 0") || data.contains("\"CMD\":0")) {
                                if (!cmd0Received) {
                                    cmd0Received = true;
                                    // Don't send response - drone keeps repeating if we do
                                    logDebug("TCP: Drone info received (FW 1.3.7, 720P). Not sending ACK (causes loop).");
                                    
                                    // Parse platform info and configure video decoder
                                    // Platform "A9-720P" means: platform type (varies), 1280x720 resolution
                                    // For HS260: platform type is unknown, but we know it's 720p
                                    try {
                                        // Set platform configuration for video decoder
                                        // Parameters: platform_type, width, height
                                        // Original app uses parsed values, we'll use detected 720p
                                        SDLActivity.setPlatform(0, 1280, 720);  // platform 0, 1280x720
                                        SDLActivity.setWideAngle(false);  // Wide angle mode off
                                        logDebug("TCP: Video decoder configured for 1280x720");
                                    } catch (Exception e) {
                                        Log.e(TAG, "Failed to configure video decoder", e);
                                    }
                                    
                                    // Mark TCP handshake as complete - video can now start!
                                    tcpHandshakeComplete = true;
                                    logDebug("TCP: Handshake complete! Video streaming can begin.");
                                }
                            }
                        } else if (bytesRead < 0) {
                            logDebug("TCP connection closed by drone");
                            disconnect();
                            break;
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        // Timeout is normal, continue
                    } catch (java.net.SocketException e) {
                        if (isConnected) {
                            logDebug("TCP socket closed: " + e.getMessage());
                            disconnect();
                        }
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "TCP read error", e);
                        logDebug("TCP read error: " + e.getMessage());
                        if (isConnected) {
                            disconnect();
                        }
                        break;
                    }
                }
                
                logDebug("TCP reader stopped");
            } catch (Exception e) {
                Log.e(TAG, "TCP reader failed", e);
                logDebug("TCP reader error: " + e.getMessage());
                if (isConnected) {
                    disconnect();
                }
            }
        }).start();
    }
    
    private void startControlLoop() {
        sendingControl = true;
        controlThread = new Thread(() -> {
            logDebug("Control loop started");
            int packetCount = 0;
            int errorCount = 0;
            long lastLogTime = System.currentTimeMillis();
            
            while (sendingControl && isConnected) {
                try {
                    synchronized (packetLock) {
                        // Use native library to encode FlySendInfo into binary packet
                        SDLActivity.getFlySendData(flySendInfo);
                        byte[] packetData = flySendInfo.getData();
                        
                        DatagramPacket packet = new DatagramPacket(
                            packetData, 
                            packetData.length, 
                            droneAddress, 
                            CONTROL_PORT
                        );
                        controlSocket.send(packet);
                        packetCount++;
                        
                        // Log every 2 seconds (40 packets at 20Hz)
                        long now = System.currentTimeMillis();
                        if (now - lastLogTime > 2000) {
                            logDebug("Sent " + packetCount + " packets (" + errorCount + " errors)");
                            lastLogTime = now;
                        }
                        
                        // Log packet hex dump every 100 packets for verification
                        if (packetCount % 100 == 0) {
                            StringBuilder hex = new StringBuilder("Packet #" + packetCount + ": ");
                            for (int i = 0; i < packetData.length; i++) {
                                hex.append(String.format("%02X ", packetData[i] & 0xFF));
                            }
                            logDebug(hex.toString());
                        }
                    }
                    Thread.sleep(CONTROL_RATE_MS);
                } catch (Exception e) {
                    errorCount++;
                    if (sendingControl) {
                        Log.e(TAG, "Control loop error", e);
                        if (errorCount < 3) {
                            updateStatus("Control error: " + e.getMessage());
                            logDebug("ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                        }
                        
                        // If too many consecutive errors, disconnect
                        if (errorCount > 10) {
                            logDebug("Too many control errors, disconnecting");
                            disconnect();
                            break;
                        }
                    }
                }
            }
            logDebug("Control loop stopped");
        });
        controlThread.start();
    }
    
    private void startVideoStream() {
        new Thread(() -> {
            try {
                // Create UDP socket for video on port 1563
                videoSocket = new DatagramSocket(null);
                videoSocket.setReuseAddress(true);
                videoSocket.bind(new InetSocketAddress(VIDEO_PORT));
                videoSocket.setSoTimeout(3000);
                videoSocket.setBroadcast(true);
                videoSocket.setReuseAddress(true);  // Set again after bind (original does this)
                
                logDebug("===== VIDEO STREAM =====");
                logDebug("Video socket bound to LOCAL port: " + VIDEO_PORT);
                
                // CONNECT to drone video port (this tells kernel to filter packets)
                // Note: This may trigger ICMP errors initially, but we'll handle them
                videoSocket.connect(droneAddress, VIDEO_PORT);
                logDebug("Video socket connected to DRONE " + droneAddress + ":" + VIDEO_PORT);
                
                // Send handshake packet to request video stream: 0xD8 0xC0 0xD9
                byte[] handshake = new byte[]{(byte) 0xD8, (byte) 0xC0, (byte) 0xD9};
                DatagramPacket handshakePacket = new DatagramPacket(
                    handshake, 
                    handshake.length, 
                    droneAddress, 
                    VIDEO_PORT
                );
                videoSocket.send(handshakePacket);
                logDebug("Video handshake sent: D8 C0 D9");
                logDebug("Waiting for drone to start streaming...");
                
                receivingVideo = true;
                byte[] videoBuffer = new byte[102400]; // 100KB buffer for video frames
                int videoPacketCount = 0;
                int icmpErrorCount = 0;
                long lastVideoLog = System.currentTimeMillis();
                
                while (receivingVideo && isConnected) {
                    try {
                        DatagramPacket videoPacket = new DatagramPacket(videoBuffer, videoBuffer.length);
                        videoSocket.receive(videoPacket);
                        
                        videoPacketCount++;
                        int length = videoPacket.getLength();
                        
                        // Log immediately on first packet
                        if (videoPacketCount == 1) {
                            StringBuilder hex = new StringBuilder("FIRST VIDEO PACKET (" + length + " bytes): ");
                            for (int i = 0; i < Math.min(32, length); i++) {
                                hex.append(String.format("%02X ", videoBuffer[i] & 0xFF));
                            }
                            logDebug(hex.toString());
                            logDebug("Video stream ACTIVE! Source: " + videoPacket.getAddress() + ":" + videoPacket.getPort());
                        }
                        
                        // Pass to native library for decoding
                        SDLActivity.udpData(videoBuffer, length);
                        
                        // Debug: Check forwarding conditions
                        if (videoPacketCount == 1) {
                            logDebug("FORWARDING CHECK: apiServer=" + (apiServer != null) + ", length=" + length);
                        }
                        
                        // Also forward RAW packets to WebSocket (contains SPS/PPS!)
                        if (apiServer != null && length > 0) {
                            byte[] packet = new byte[length];
                            System.arraycopy(videoBuffer, 0, packet, 0, length);
                            apiServer.sendH264Packet(packet);
                            
                            // Log first forward
                            if (videoPacketCount == 1) {
                                logDebug("First video packet forwarded to WebSocket: " + length + " bytes");
                            }
                        } else if (videoPacketCount == 1) {
                            logDebug("FORWARDING SKIPPED: apiServer=" + (apiServer != null) + ", length=" + length);
                        }
                        
                        // Log every 2 seconds
                        long now = System.currentTimeMillis();
                        if (now - lastVideoLog > 2000) {
                            logDebug("Video packets received: " + videoPacketCount + " (avg " + length + " bytes)");
                            lastVideoLog = now;
                        }
                        
                    } catch (java.net.PortUnreachableException e) {
                        // Drone not streaming yet - this is normal initially
                        icmpErrorCount++;
                        if (icmpErrorCount == 1) {
                            logDebug("Drone not streaming yet (ICMP Port Unreachable) - waiting...");
                        } else if (icmpErrorCount % 10 == 0) {
                            logDebug("Still waiting for drone to start video stream (ICMP x" + icmpErrorCount + ")");
                        }
                        try { Thread.sleep(500); } catch (InterruptedException ie) {}
                        // Resend handshake periodically
                        if (icmpErrorCount % 5 == 0) {
                            videoSocket.send(handshakePacket);
                            logDebug("Resent video handshake");
                        }
                    } catch (SocketTimeoutException e) {
                        // Normal timeout - keep waiting
                        long now = System.currentTimeMillis();
                        if (now - lastVideoLog > 10000) {
                            logDebug("Waiting for video... (packets: " + videoPacketCount + ", ICMP errors: " + icmpErrorCount + ")");
                            lastVideoLog = now;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Video receive error", e);
                        logDebug("Video error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        break;
                    }
                }
                
                logDebug("Video stream stopped");
                
            } catch (Exception e) {
                Log.e(TAG, "Video stream failed", e);
                logDebug("Video stream error: " + e.getMessage());
            }
        }).start();
    }

    private void sendTakeoff() {
        logDebug("===== TAKEOFF BUTTON PRESSED =====");
        logDebug("isConnected: " + isConnected + ", isFlying: " + isFlying);
        synchronized (packetLock) {
            flySendInfo.setOneKey(1);  // Trigger takeoff
            logDebug("FlySendInfo.OneKey set to 1");
        }
        isFlying = true;
        updateStatus("Takeoff command sent");
        logDebug("TAKEOFF: OneKey=1, next packets will include this command");
        
        // Reset OneKey after 1 second (pulse the command)
        mainHandler.postDelayed(() -> {
            synchronized (packetLock) {
                flySendInfo.setOneKey(0);
            }
            logDebug("TAKEOFF: OneKey reset to 0");
        }, 1000);
        
        updateUI();
    }

    private void sendLand() {
        logDebug("===== LAND BUTTON PRESSED =====");
        logDebug("isConnected: " + isConnected + ", isFlying: " + isFlying);
        synchronized (packetLock) {
            flySendInfo.setOneKey(1);  // Same command for land
            logDebug("FlySendInfo.OneKey set to 1");
        }
        isFlying = false;
        updateStatus("Land command sent");
        logDebug("LAND: OneKey=1, next packets will include this command");
        
        // Reset OneKey after 1 second
        mainHandler.postDelayed(() -> {
            synchronized (packetLock) {
                flySendInfo.setOneKey(0);
            }
            logDebug("LAND: OneKey reset to 0");
        }, 1000);
        
        updateUI();
    }

    private void updateStatus(String status) {
        mainHandler.post(() -> tvStatus.setText(status));
    }

    private void logDebug(String message) {
        Log.d(TAG, message);
        mainHandler.post(() -> {
            String current = tvDebug.getText().toString();
            String[] lines = current.split("\n");
            StringBuilder sb = new StringBuilder();
            // Keep last 10 lines
            int start = Math.max(0, lines.length - 9);
            for (int i = start; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
            sb.append(message);
            tvDebug.setText(sb.toString());
        });
    }

    private void showToast(String message) {
        mainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
    
    private String formatIP(int ip) {
        return String.format("%d.%d.%d.%d",
            (ip & 0xff),
            (ip >> 8 & 0xff),
            (ip >> 16 & 0xff),
            (ip >> 24 & 0xff));
    }

    private void updateUI() {
        btnConnect.setText(isConnected ? "Disconnect" : "Connect to Drone");
        btnConnect.setEnabled(true);
        btnDisconnect.setEnabled(false);
        btnDisconnect.setVisibility(View.GONE);
        btnTakeoff.setEnabled(isConnected && !isFlying);
        btnLand.setEnabled(isConnected && isFlying);
    }
    
    private void disconnect() {
        logDebug("===== DISCONNECTING =====");
        
        // Stop all activities
        sendingControl = false;
        receivingVideo = false;
        isConnected = false;
        isFlying = false;
        tcpHandshakeComplete = false;
        
        // Stop video decoder
        if (videoDecoder != null) {
            videoDecoder.stop();
            videoDecoder = null;
            logDebug("Video decoder stopped");
        }
        
        // Close all sockets
        try {
            if (controlThread != null) {
                controlThread.interrupt();
                controlThread = null;
            }
            if (videoThread != null) {
                videoThread.interrupt();
                videoThread = null;
            }
            if (controlSocket != null && !controlSocket.isClosed()) {
                controlSocket.close();
                controlSocket = null;
            }
            if (videoSocket != null && !videoSocket.isClosed()) {
                videoSocket.close();
                videoSocket = null;
            }
            if (tcpSocket != null && !tcpSocket.isClosed()) {
                tcpSocket.close();
                tcpSocket = null;
            }
            logDebug("All sockets closed");
        } catch (Exception e) {
            Log.e(TAG, "Error closing sockets", e);
        }
        
        // Reset frame counter
        frameCount = 0;
        lastFrameTime = System.currentTimeMillis();
        
        // Update UI
        updateStatus("Disconnected");
        mainHandler.post(this::updateUI);
        
        logDebug("Disconnect complete - ready to reconnect");
    }
    
    private void startApiServer() {
        apiServer = new DebugApiServer(new DebugApiServer.ApiCallback() {
            @Override
            public void onTakeoff() {
                mainHandler.post(() -> sendTakeoff());
            }

            @Override
            public void onLand() {
                mainHandler.post(() -> sendLand());
            }

            @Override
            public void onConnect() {
                mainHandler.post(() -> connectToDrone());
            }

            @Override
            public void onDisconnect() {
                mainHandler.post(() -> disconnect());
            }

            @Override
            public String getStatus() {
                try {
                    org.json.JSONObject status = new org.json.JSONObject();
                    status.put("connected", isConnected);
                    status.put("flying", isFlying);
                    status.put("sendingControl", sendingControl);
                    status.put("receivingVideo", receivingVideo);
                    status.put("tcpHandshakeComplete", tcpHandshakeComplete);
                    status.put("frameCount", frameCount);
                    status.put("videoDecoderRunning", videoDecoder != null && videoDecoder.isRunning());
                    
                    // Telemetry data
                    status.put("battery", batteryLevel);
                    status.put("altitude", altitude);
                    status.put("motorRunning", motorRunning);
                    status.put("altitudeHold", altitudeHold);
                    status.put("landed", lastTelemetry.getLanded() == 1);
                    status.put("takeOff", lastTelemetry.getTakeOff() == 1);
                    status.put("headless", lastTelemetry.getHeadless() == 1);
                    status.put("calibrate", lastTelemetry.getCalibrate() == 1);
                    status.put("galeWarning", lastTelemetry.getGale() == 1);
                    status.put("currentOver", lastTelemetry.getCurrOver() == 1);
                    status.put("followMode", lastTelemetry.getFollow() == 1);
                    
                    return status.toString();
                } catch (Exception e) {
                    return "{\"error\":\"" + e.getMessage() + "\"}";
                }
            }

            @Override
            public android.graphics.Bitmap getLatestFrame() {
                // Get bitmap from SurfaceView
                if (videoView == null) {
                    return null;
                }
                try {
                    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                        videoView.getWidth(), 
                        videoView.getHeight(), 
                        android.graphics.Bitmap.Config.ARGB_8888
                    );
                    android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                    videoView.draw(canvas);
                    return bitmap;
                } catch (Exception e) {
                    return null;
                }
            }
        });
        apiServer.start();
        
        // Frame capture is now handled via VideoDecoder callback
        // See surfaceCreated() for MJPEG frame streaming setup
        
        logDebug("Debug API server started on port 9000");
        logDebug("Run: adb forward tcp:9000 tcp:9000");
        logDebug("Then open: http://localhost:9000");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (apiServer != null) {
            apiServer.stop();
        }
        disconnect();
    }
}

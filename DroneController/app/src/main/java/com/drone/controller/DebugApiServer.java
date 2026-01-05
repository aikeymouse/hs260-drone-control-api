package com.drone.controller;

import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple HTTP API server for debug/control from PC via ADB port forwarding
 * Usage: adb forward tcp:9000 tcp:9000
 * Then access: http://localhost:9000/api/status
 */
public class DebugApiServer {
    private static final String TAG = "DebugApiServer";
    private static final int PORT = 9000;
    
    private ServerSocket serverSocket;
    private Thread serverThread;
    private boolean isRunning = false;
    private ApiCallback callback;
    private CopyOnWriteArrayList<Socket> mjpegClients = new CopyOnWriteArrayList<>();
    private CopyOnWriteArrayList<Socket> h264Clients = new CopyOnWriteArrayList<>();
    private byte[] latestJpegFrame = null;
    private long h264BytesSent = 0;
    private long h264PacketsSent = 0;
    
    // Cache SPS/PPS for new clients
    private byte[] cachedSPS = null;
    private byte[] cachedPPS = null;
    
    public interface ApiCallback {
        void onTakeoff();
        void onLand();
        void onConnect();
        void onDisconnect();
        String getStatus();
        Bitmap getLatestFrame();
        // Manual control - slow movements
        void onYawLeft();     // Turn left
        void onYawRight();    // Turn right
        void onMoveUp();      // Move up
        void onMoveDown();    // Move down
        void onStopManual();  // Stop manual control
    }
    
    public DebugApiServer(ApiCallback callback) {
        this.callback = callback;
    }
    
    public void start() {
        if (isRunning) {
            Log.w(TAG, "Server already running");
            return;
        }
        
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                isRunning = true;
                Log.d(TAG, "API server started on port " + PORT);
                Log.d(TAG, "Use: adb forward tcp:9000 tcp:9000");
                
                while (isRunning && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        handleClient(client);
                    } catch (Exception e) {
                        if (isRunning) {
                            Log.e(TAG, "Error handling client", e);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error", e);
            }
        });
        serverThread.start();
    }
    
    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping server", e);
        }
        Log.d(TAG, "API server stopped");
    }
    
    /**
     * Close all WebSocket H.264 streaming clients
     */
    public void closeAllH264Clients() {
        Log.d(TAG, "Closing all H.264 WebSocket clients (" + h264Clients.size() + " connected)");
        for (Socket client : h264Clients) {
            try {
                client.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        h264Clients.clear();
        
        // Reset cached SPS/PPS
        cachedSPS = null;
        cachedPPS = null;
        
        Log.d(TAG, "All H.264 clients closed");
    }
    
    private void handleClient(Socket client) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            // Read HTTP request line
            String requestLine = in.readLine();
            if (requestLine == null) {
                client.close();
                return;
            }
            
            Log.d(TAG, "Request: " + requestLine);
            
            // Parse request: GET /api/command HTTP/1.1
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendResponse(client, 400, "Bad Request");
                return;
            }
            
            String method = parts[0];
            String path = parts[1];
            
            // Check for WebSocket upgrade before consuming headers
            if (path.startsWith("/stream")) {
                handleWebSocketH264(client, requestLine, in);
                return; // WebSocket handler manages the connection
            }
            
            // Skip headers (read until empty line)
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // Skip headers
            }
            
            // Route request
            handleRequest(client, method, path);
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing request", e);
            try {
                client.close();
            } catch (Exception ex) {
                // Ignore
            }
        }
    }
    
    private void handleRequest(Socket client, String method, String path) throws Exception {
        // CORS headers for browser access
        String corsHeaders = "Access-Control-Allow-Origin: *\r\n" +
                           "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                           "Access-Control-Allow-Headers: Content-Type\r\n";
        
        // Handle OPTIONS preflight
        if ("OPTIONS".equals(method)) {
            sendResponse(client, 200, "OK", corsHeaders);
            return;
        }
        
        // API routes
        if (path.equals("/api/status")) {
            String status = callback.getStatus();
            sendJsonResponse(client, 200, status, corsHeaders);
            client.close();
            
        } else if (path.equals("/api/takeoff")) {
            callback.onTakeoff();
            sendJsonResponse(client, 200, "{\"result\":\"takeoff command sent\"}", corsHeaders);
            client.close();
            
        } else if (path.equals("/api/land")) {
            callback.onLand();
            sendJsonResponse(client, 200, "{\"result\":\"land command sent\"}", corsHeaders);
            client.close();
            
        } else if (path.equals("/api/connect")) {
            callback.onConnect();
            sendJsonResponse(client, 200, "{\"result\":\"connect initiated\"}", corsHeaders);
            client.close();
            
        } else if (path.equals("/api/disconnect")) {
            callback.onDisconnect();
            sendJsonResponse(client, 200, "{\"result\":\"disconnect initiated\"}", corsHeaders);
            client.close();
            
        } else if (path.equals("/api/yaw/left")) {
            callback.onYawLeft();
            sendJsonResponse(client, 200, "{\"result\":\"turning left\"}", corsHeaders);
            client.close();
            
        } else if (path.equals("/api/yaw/right")) {
            callback.onYawRight();
            sendJsonResponse(client, 200, "{\"result\":\"turning right\"}", corsHeaders);
            client.close();
            
        } else if (path.equals("/api/move/up")) {
            callback.onMoveUp();
            sendJsonResponse(client, 200, "{\"result\":\"moving up\"}", corsHeaders);
            client.close();
            
        } else if (path.equals("/api/move/down")) {
            callback.onMoveDown();
            sendJsonResponse(client, 200, "{\"result\":\"moving down\"}", corsHeaders);
            client.close();
            
        } else if (path.equals("/api/stop")) {
            callback.onStopManual();
            sendJsonResponse(client, 200, "{\"result\":\"stopped\"}", corsHeaders);
            client.close();
            
        } else if (path.equals("/api/video.mjpg")) {
            serveMjpegStream(client);
            // Don't close - streaming keeps connection open
            
        } else if (path.equals("/") || path.equals("/index.html")) {
            sendHtmlDashboard(client, corsHeaders);
            client.close();
            
        } else {
            sendResponse(client, 404, "Not Found", corsHeaders);
            client.close();
        }
    }
    
    private void sendResponse(Socket client, int code, String message) throws Exception {
        sendResponse(client, code, message, "");
    }
    
    private void sendResponse(Socket client, int code, String message, String extraHeaders) throws Exception {
        OutputStream out = client.getOutputStream();
        String response = "HTTP/1.1 " + code + " " + message + "\r\n" +
                         extraHeaders +
                         "Content-Length: 0\r\n" +
                         "\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
    
    private void sendJsonResponse(Socket client, int code, String json, String extraHeaders) throws Exception {
        OutputStream out = client.getOutputStream();
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 " + code + " OK\r\n" +
                         extraHeaders +
                         "Content-Type: application/json\r\n" +
                         "Content-Length: " + body.length + "\r\n" +
                         "\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
    
    private void sendHtmlDashboard(Socket client, String extraHeaders) throws Exception {
        String html = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "  <title>DroneController Debug Dashboard</title>\n" +
                "  <style>\n" +
                "    body { font-family: Arial; margin: 20px; background: #1e1e1e; color: #fff; }\n" +
                "    h1 { color: #4CAF50; }\n" +
                "    .container { display: flex; gap: 20px; }\n" +
                "    .left-panel { flex: 1; }\n" +
                "    .video-panel { flex: 2; }\n" +
                "    .status { padding: 10px; background: #2d2d2d; border-radius: 5px; margin: 10px 0; }\n" +
                "    #video-stream { width: 100%; max-width: 960px; background: #000; border-radius: 5px; }\n" +
                "    button { padding: 15px 30px; margin: 5px; font-size: 16px; cursor: pointer; " +
                "             border: none; border-radius: 5px; width: 100%; }\n" +
                "    .connect { background: #4CAF50; color: white; }\n" +
                "    .disconnect { background: #f44336; color: white; }\n" +
                "    .takeoff { background: #2196F3; color: white; }\n" +
                "    .land { background: #FF9800; color: white; }\n" +
                "    button:hover { opacity: 0.8; }\n" +
                "    #status-display { white-space: pre-wrap; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <h1>🚁 DroneController Debug Dashboard</h1>\n" +
                "  <div class='container'>\n" +
                "    <div class='left-panel'>\n" +
                "      <div class='status'>\n" +
                "        <h3>Status:</h3>\n" +
                "        <div id='status-display'>Loading...</div>\n" +
                "      </div>\n" +
                "      <div>\n" +
                "        <button class='connect' onclick='sendCommand(\"connect\")'>Connect</button>\n" +
                "        <button class='disconnect' onclick='sendCommand(\"disconnect\")'>Disconnect</button>\n" +
                "        <button class='takeoff' onclick='sendCommand(\"takeoff\")'>Takeoff</button>\n" +
                "        <button class='land' onclick='sendCommand(\"land\")'>Land</button>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "    <div class='video-panel'>\n" +
                "      <h3>Live Video Feed</h3>\n" +
                "      <img id='video-stream' src='/api/video.mjpg' alt='Video stream will appear here when connected'>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "  <script>\n" +
                "    function sendCommand(cmd) {\n" +
                "      fetch('/api/' + cmd)\n" +
                "        .then(r => r.json())\n" +
                "        .then(data => { console.log(data); updateStatus(); })\n" +
                "        .catch(e => console.error(e));\n" +
                "    }\n" +
                "    function updateStatus() {\n" +
                "      fetch('/api/status')\n" +
                "        .then(r => r.json())\n" +
                "        .then(data => {\n" +
                "          document.getElementById('status-display').textContent = JSON.stringify(data, null, 2);\n" +
                "        })\n" +
                "        .catch(e => console.error(e));\n" +
                "    }\n" +
                "    setInterval(updateStatus, 1000);\n" +
                "    updateStatus();\n" +
                "  </script>\n" +
                "</body>\n" +
                "</html>";
        
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        OutputStream out = client.getOutputStream();
        String response = "HTTP/1.1 200 OK\r\n" +
                         extraHeaders +
                         "Content-Type: text/html\r\n" +
                         "Content-Length: " + body.length + "\r\n" +
                         "\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
    
    private void serveMjpegStream(Socket client) {
        try {
            mjpegClients.add(client);
            OutputStream out = client.getOutputStream();
            
            // Send MJPEG headers
            String headers = "HTTP/1.1 200 OK\r\n" +
                           "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                           "Cache-Control: no-cache\r\n" +
                           "Connection: keep-alive\r\n" +
                           "\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.flush();
            
            Log.d(TAG, "MJPEG client connected, streaming video...");
            
            // Keep connection alive and stream frames
            while (isRunning && !client.isClosed() && mjpegClients.contains(client)) {
                try {
                    Thread.sleep(33); // ~30 FPS
                } catch (InterruptedException e) {
                    break;
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error serving MJPEG", e);
        } finally {
            mjpegClients.remove(client);
            try {
                client.close();
            } catch (Exception e) {
                // Ignore
            }
            Log.d(TAG, "MJPEG client disconnected");
        }
    }
    
    public void sendFrame(Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        
        if (mjpegClients.isEmpty()) {
            return;
        }
        
        try {
            // Convert bitmap to JPEG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            byte[] jpegData = baos.toByteArray();
            
            if (jpegData.length == 0) {
                Log.w(TAG, "Empty JPEG data");
                return;
            }
            
            // Send to all connected clients
            int sentCount = 0;
            for (Socket client : mjpegClients) {
                try {
                    if (client.isClosed()) {
                        mjpegClients.remove(client);
                        continue;
                    }
                    
                    OutputStream out = client.getOutputStream();
                    out.write(("--frame\r\n" +
                              "Content-Type: image/jpeg\r\n" +
                              "Content-Length: " + jpegData.length + "\r\n" +
                              "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(jpegData);
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    sentCount++;
                } catch (Exception e) {
                    Log.w(TAG, "Error sending frame to client: " + e.getMessage());
                    mjpegClients.remove(client);
                    try {
                        client.close();
                    } catch (Exception ex) {
                        // Ignore
                    }
                }
            }
            
            if (sentCount > 0) {
                // Log occasionally
                long now = System.currentTimeMillis();
                if (now - lastFrameLogTime > 5000) {
                    Log.d(TAG, "Streaming " + jpegData.length + " bytes to " + sentCount + " client(s)");
                    lastFrameLogTime = now;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending frame", e);
        }
    }
    
    /**
     * Handle WebSocket upgrade request for H.264 streaming
     */
    private void handleWebSocketH264(Socket client, String requestLine, BufferedReader in) {
        new Thread(() -> {
            try {
                // Read headers to find WebSocket key
                String webSocketKey = null;
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("Sec-WebSocket-Key:")) {
                        webSocketKey = line.substring(19).trim();
                    }
                }
                
                if (webSocketKey == null) {
                    client.close();
                    return;
                }
                
                // Calculate WebSocket accept key
                String acceptKey = calculateWebSocketAccept(webSocketKey);
                
                // Send WebSocket handshake response
                OutputStream out = client.getOutputStream();
                String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " + acceptKey + "\r\n" +
                        "\r\n";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
                
                // Add client to H.264 streaming list
                h264Clients.add(client);
                Log.d(TAG, "WebSocket H.264 client connected. Total clients: " + h264Clients.size());
                
                // Send cached SPS/PPS immediately to new client
                if (cachedSPS != null && cachedPPS != null) {
                    try {
                        out.write(encodeWebSocketFrame(cachedSPS));
                        out.write(encodeWebSocketFrame(cachedPPS));
                        out.flush();
                        Log.d(TAG, "Sent cached SPS (" + cachedSPS.length + " bytes) and PPS (" + cachedPPS.length + " bytes) to new client");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send SPS/PPS to new client", e);
                    }
                }
                
                // Keep connection alive - frames sent via sendH264Packet()
                while (!client.isClosed() && h264Clients.contains(client)) {
                    Thread.sleep(1000);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "WebSocket error", e);
            } finally {
                h264Clients.remove(client);
                try {
                    client.close();
                } catch (Exception ignored) {}
                Log.d(TAG, "WebSocket H.264 client disconnected. Remaining: " + h264Clients.size());
            }
        }).start();
    }
    
    /**
     * Send H.264 packet to all connected WebSocket clients
     */
    public void sendH264Packet(byte[] packet) {
        if (packet == null || packet.length == 0) {
            return;
        }
        
        // Cache SPS (NAL type 7) and PPS (NAL type 8) for new clients
        if (packet.length > 0) {
            int nalType = packet[0] & 0x1F;
            if (nalType == 7) {
                cachedSPS = packet.clone();
                Log.d(TAG, "Cached SPS (" + packet.length + " bytes)");
            } else if (nalType == 8) {
                cachedPPS = packet.clone();
                Log.d(TAG, "Cached PPS (" + packet.length + " bytes)");
            }
        }
        
        if (h264Clients.isEmpty()) {
            return;
        }
        
        try {
            // Encode packet as WebSocket binary frame
            byte[] frame = encodeWebSocketFrame(packet);
            
            int sentCount = 0;
            for (Socket client : h264Clients) {
                try {
                    OutputStream out = client.getOutputStream();
                    out.write(frame);
                    out.flush();
                    sentCount++;
                } catch (Exception e) {
                    h264Clients.remove(client);
                    try {
                        client.close();
                    } catch (Exception ignored) {}
                }
            }
            
            if (sentCount > 0) {
                h264PacketsSent++;
                h264BytesSent += packet.length;
                
                // Log every 100 packets
                if (h264PacketsSent % 100 == 0) {
                    float mbSent = h264BytesSent / (1024f * 1024f);
                    Log.d(TAG, String.format("H.264: Sent %d packets (%.2f MB) to %d client(s)", 
                        h264PacketsSent, mbSent, sentCount));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending H.264 packet", e);
        }
    }
    
    /**
     * Calculate WebSocket accept key from client key
     */
    private String calculateWebSocketAccept(String key) {
        try {
            String magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            String combined = key + magic;
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Error calculating WebSocket accept", e);
            return "";
        }
    }
    
    /**
     * Encode data as WebSocket binary frame
     */
    private byte[] encodeWebSocketFrame(byte[] data) {
        int payloadLength = data.length;
        byte[] frame;
        int offset;
        
        if (payloadLength < 126) {
            frame = new byte[2 + payloadLength];
            frame[0] = (byte) 0x82; // FIN + binary frame
            frame[1] = (byte) payloadLength;
            offset = 2;
        } else if (payloadLength < 65536) {
            frame = new byte[4 + payloadLength];
            frame[0] = (byte) 0x82; // FIN + binary frame
            frame[1] = 126;
            frame[2] = (byte) ((payloadLength >> 8) & 0xFF);
            frame[3] = (byte) (payloadLength & 0xFF);
            offset = 4;
        } else {
            frame = new byte[10 + payloadLength];
            frame[0] = (byte) 0x82; // FIN + binary frame
            frame[1] = 127;
            for (int i = 0; i < 8; i++) {
                frame[2 + i] = (byte) ((payloadLength >> (8 * (7 - i))) & 0xFF);
            }
            offset = 10;
        }
        
        System.arraycopy(data, 0, frame, offset, payloadLength);
        return frame;
    }
    
    private long lastFrameLogTime = 0;
}

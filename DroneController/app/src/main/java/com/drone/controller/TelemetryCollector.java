package com.drone.controller;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import org.libsdl.app.FlyReceiveInfo;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Collects and stores telemetry data from the drone
 * Provides export to JSON, CSV, and raw formats
 */
public class TelemetryCollector {
    private static final String TAG = "TelemetryCollector";
    private static final int MAX_PACKETS = 10000;  // Keep last 10k packets
    
    private final LinkedList<TelemetryPacket> packets;
    private final ReentrantLock lock;
    private long sessionStartTime;
    private boolean collecting;
    private int totalPacketsReceived;
    
    public static class TelemetryPacket {
        public final long timestamp;
        public final String timestampStr;
        public final byte[] rawData;
        public final String rawHex;
        public final int battery;
        public final int altitude;
        public final int motorRunning;
        public final int takeOff;
        public final int landed;
        public final int altHold;
        public final int headless;
        public final int calibrate;
        public final int currOver;
        public final int gale;
        public final int height6;
        
        public TelemetryPacket(byte[] data, int length, FlyReceiveInfo info) {
            this.timestamp = System.currentTimeMillis();
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
            this.timestampStr = sdf.format(new Date(timestamp));
            
            // Store raw data
            this.rawData = new byte[length];
            System.arraycopy(data, 0, this.rawData, 0, length);
            
            // Convert to hex
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < length; i++) {
                hex.append(String.format("%02X ", data[i]));
            }
            this.rawHex = hex.toString().trim();
            
            // Store parsed values
            this.battery = info.getBatVal();
            this.altitude = info.getHeight6();
            this.motorRunning = info.getMotorRunning();
            this.takeOff = info.getTakeOff();
            this.landed = info.getLanded();
            this.altHold = info.getAltHold();
            this.headless = info.getHeadless();
            this.calibrate = info.getCalibrate();
            this.currOver = info.getCurrOver();
            this.gale = info.getGale();
            this.height6 = info.getHeight6();
        }
    }
    
    public TelemetryCollector() {
        this.packets = new LinkedList<>();
        this.lock = new ReentrantLock();
        this.collecting = false;
        this.totalPacketsReceived = 0;
    }
    
    /**
     * Start collecting telemetry data
     */
    public void startCollection() {
        lock.lock();
        try {
            if (!collecting) {
                sessionStartTime = System.currentTimeMillis();
                packets.clear();
                totalPacketsReceived = 0;
                collecting = true;
                Log.d(TAG, "Started telemetry collection");
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Check if currently collecting telemetry
     */
    public boolean isRecording() {
        lock.lock();
        try {
            return collecting;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Stop collecting telemetry data
     */
    public void stopCollection() {
        lock.lock();
        try {
            collecting = false;
            Log.d(TAG, "Stopped telemetry collection. Collected " + packets.size() + " packets");
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Add a telemetry packet
     */
    public void addPacket(byte[] data, int length, FlyReceiveInfo info) {
        lock.lock();
        try {
            if (!collecting) {
                return;
            }
            
            TelemetryPacket packet = new TelemetryPacket(data, length, info);
            packets.add(packet);
            totalPacketsReceived++;
            
            // Remove oldest packets if exceeding limit
            while (packets.size() > MAX_PACKETS) {
                packets.removeFirst();
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Clear all collected data
     */
    public void clear() {
        lock.lock();
        try {
            packets.clear();
            totalPacketsReceived = 0;
            Log.d(TAG, "Cleared telemetry data");
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Get collection statistics
     */
    public String getStats() {
        lock.lock();
        try {
            JSONObject stats = new JSONObject();
            stats.put("collecting", collecting);
            stats.put("packets_stored", packets.size());
            stats.put("total_received", totalPacketsReceived);
            
            if (!packets.isEmpty()) {
                long duration = System.currentTimeMillis() - sessionStartTime;
                stats.put("duration_seconds", duration / 1000.0);
                stats.put("start_time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(sessionStartTime)));
            }
            
            return stats.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error getting stats", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Export data as JSON
     */
    public String exportJSON() {
        lock.lock();
        try {
            JSONObject root = new JSONObject();
            
            // Metadata
            JSONObject metadata = new JSONObject();
            metadata.put("drone", "HS260");
            metadata.put("format_version", "1.0");
            metadata.put("export_time", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .format(new Date()));
            
            if (!packets.isEmpty()) {
                long duration = System.currentTimeMillis() - sessionStartTime;
                metadata.put("session_start", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .format(new Date(sessionStartTime)));
                metadata.put("duration_seconds", duration / 1000.0);
                metadata.put("packet_count", packets.size());
                metadata.put("total_received", totalPacketsReceived);
            }
            
            root.put("metadata", metadata);
            
            // Packets
            JSONArray packetsArray = new JSONArray();
            for (TelemetryPacket packet : packets) {
                JSONObject p = new JSONObject();
                p.put("timestamp", packet.timestampStr);
                p.put("raw_hex", packet.rawHex);
                
                JSONObject parsed = new JSONObject();
                parsed.put("battery", packet.battery);
                parsed.put("altitude", packet.altitude);
                parsed.put("motors", packet.motorRunning);
                parsed.put("takeOff", packet.takeOff);
                parsed.put("landed", packet.landed);
                parsed.put("altHold", packet.altHold);
                parsed.put("headless", packet.headless);
                parsed.put("calibrate", packet.calibrate);
                parsed.put("currOver", packet.currOver);
                parsed.put("gale", packet.gale);
                parsed.put("height6", packet.height6);
                
                p.put("parsed", parsed);
                packetsArray.put(p);
            }
            
            root.put("packets", packetsArray);
            
            return root.toString(2);  // Pretty print with 2 spaces
        } catch (Exception e) {
            Log.e(TAG, "Error exporting JSON", e);
            return "{}";
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Export data as CSV
     */
    public String exportCSV() {
        lock.lock();
        try {
            StringBuilder csv = new StringBuilder();
            
            // Header
            csv.append("Timestamp,Raw_Hex,Battery,Altitude,Motors,TakeOff,Landed,AltHold,Headless,Calibrate,CurrOver,Gale,Height6\n");
            
            // Data rows
            for (TelemetryPacket packet : packets) {
                csv.append(packet.timestampStr).append(",");
                csv.append("\"").append(packet.rawHex).append("\",");
                csv.append(packet.battery).append(",");
                csv.append(packet.altitude).append(",");
                csv.append(packet.motorRunning).append(",");
                csv.append(packet.takeOff).append(",");
                csv.append(packet.landed).append(",");
                csv.append(packet.altHold).append(",");
                csv.append(packet.headless).append(",");
                csv.append(packet.calibrate).append(",");
                csv.append(packet.currOver).append(",");
                csv.append(packet.gale).append(",");
                csv.append(packet.height6).append("\n");
            }
            
            return csv.toString();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Export raw binary data
     */
    public byte[] exportRaw() {
        lock.lock();
        try {
            int totalSize = 0;
            for (TelemetryPacket packet : packets) {
                totalSize += packet.rawData.length + 8;  // 8 bytes for timestamp
            }
            
            byte[] result = new byte[totalSize];
            int offset = 0;
            
            for (TelemetryPacket packet : packets) {
                // Write timestamp (8 bytes, long)
                long ts = packet.timestamp;
                for (int i = 0; i < 8; i++) {
                    result[offset++] = (byte) (ts >> (56 - i * 8));
                }
                
                // Write packet data
                System.arraycopy(packet.rawData, 0, result, offset, packet.rawData.length);
                offset += packet.rawData.length;
            }
            
            return result;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Check if currently collecting
     */
    public boolean isCollecting() {
        lock.lock();
        try {
            return collecting;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Get packet count
     */
    public int getPacketCount() {
        lock.lock();
        try {
            return packets.size();
        } finally {
            lock.unlock();
        }
    }
}

package org.libsdl.app;

import android.util.Log;
import com.h8.p.b;
import com.h8.p.c;

/**
 * Minimal SDLActivity wrapper to access native flight control encoding functions
 * Includes callback methods required by native library
 */
public class SDLActivity {
    
    private static final String TAG = "SDLActivity";
    private static boolean debug = false;  // Disable debug logging for now
    
    // Callback interfaces for native code
    private static b fromJNIInterface;
    private static c liveListener;
    
    static {
        // Load native libraries in correct order
        System.loadLibrary("gnustl_shared");
        System.loadLibrary("SDL2");
        System.loadLibrary("opencv_java3");
        System.loadLibrary("live555");
        System.loadLibrary("main");  // This contains the encoding logic
    }
    
    // Native method to encode FlySendInfo into binary packet
    public static native void nativeGetFlySendData(FlySendInfo flySendInfo, int length);
    
    // Native method to decode binary packet into FlyReceiveInfo
    public static native void nativeGetFlyReceiveData(FlyReceiveInfo flyReceiveInfo, byte[] data, int length);
    
    // Native video/UDP data handler
    public static native void nativeUdpData(byte[] data, int length);
    
    // Native initialization (may be needed for video)
    public static native int nativeInit(Object obj);
    public static native int nativeLiveInit(int mode);
    
    // TCP encryption handshake methods
    public static native int nativeGetEncData(byte[] data, int length);
    public static native boolean nativeIsPassEnc();
    
    // Platform configuration for video decoder
    public static native int nativeSetPlatform(int platform, int width, int height);
    public static native void nativeSetWideAngle(boolean enable);
    
    // Wrapper for easier access
    public static void getFlySendData(FlySendInfo flySendInfo) {
        nativeGetFlySendData(flySendInfo, flySendInfo.getLength());
    }
    
    public static void getFlyReceiveData(FlyReceiveInfo flyReceiveInfo, byte[] data, int length) {
        nativeGetFlyReceiveData(flyReceiveInfo, data, length);
    }
    
    // Pass video data to native library for decoding
    public static void udpData(byte[] data, int length) {
        try {
            nativeUdpData(data, length);
        } catch (Exception e) {
            Log.e(TAG, "nativeUdpData error", e);
        }
    }
    
    // Initialize native library (call once on startup)
    public static int init(Object obj) {
        try {
            return nativeInit(obj);
        } catch (Exception e) {
            Log.e(TAG, "nativeInit error", e);
            return -1;
        }
    }
    
    public static int liveInit(int mode) {
        try {
            return nativeLiveInit(mode);
        } catch (Exception e) {
            Log.e(TAG, "nativeLiveInit error", e);
            return -1;
        }
    }
    
    // Get encrypted TCP handshake data (17 bytes)
    public static byte[] getEncData(byte[] data, int length) {
        if (nativeGetEncData(data, length) == -1) {
            return null;
        }
        return data;
    }
    
    // Check if encryption is bypassed
    public static boolean isPassEnc() {
        try {
            return nativeIsPassEnc();
        } catch (Exception e) {
            Log.e(TAG, "isPassEnc error", e);
            return false;
        }
    }
    
    // Set platform configuration for video decoder
    public static void setPlatform(int platform, int width, int height) {
        try {
            nativeSetPlatform(platform, width, height);
            if (debug) {
                Log.d(TAG, "setPlatform: " + platform + ", " + width + "x" + height);
            }
        } catch (Exception e) {
            Log.e(TAG, "setPlatform error", e);
        }
    }
    
    // Set wide angle mode for camera
    public static void setWideAngle(boolean enable) {
        try {
            nativeSetWideAngle(enable);
            if (debug) {
                Log.d(TAG, "setWideAngle: " + enable);
            }
        } catch (Exception e) {
            Log.e(TAG, "setWideAngle error", e);
        }
    }
    
    // Empty release methods (app doesn't use them)
    public static native void nativeReleaseFlySendData();
    public static native void nativeReleaseFlyReceiveData();
    
    public static void releaseFlySendData() {
        nativeReleaseFlySendData();
    }
    
    public static void releaseFlyReceiveData() {
        nativeReleaseFlyReceiveData();
    }
    
    // ====================================================================================
    // CALLBACK METHODS - Called BY native code (JNI callbacks)
    // ====================================================================================
    
    /**
     * Called by native code when video decoder has result
     * @param i Result code (0 = success, etc)
     */
    public static void getPlayResultFromJNI(int i) {
        if (debug) {
            Log.d(TAG, "getPlayResultFromJNI: " + i + ", liveListener=" + (liveListener != null));
        }
        c cVar = liveListener;
        if (cVar != null) {
            cVar.A(i);
        }
    }
    
    /**
     * Called by native code to deliver decoded video frame (YUV data)
     * This is the MAIN video frame callback - called for each decoded frame
     * @param bArr Decoded YUV frame data
     */
    public static void getFrameFromJNI(byte[] bArr) {
        if (debug) {
            Log.d(TAG, "getFrameFromJNI: " + bArr.length + " bytes, liveListener=" + (liveListener != null));
        }
        c cVar = liveListener;
        if (cVar != null) {
            cVar.b(bArr);  // Deliver frame to listener
        }
    }
    
    /**
     * Called by native code to report network type changes
     * @param i Network type code
     */
    public static void getFrameSetNetType(int i) {
        if (debug) {
            Log.d(TAG, "getFrameSetNetType: " + i);
        }
        c cVar = liveListener;
        if (cVar != null) {
            cVar.w(i);
        }
    }
    
    // ====================================================================================
    // SETTER METHODS - To register callback listeners
    // ====================================================================================
    
    /**
     * Register the interface for JNI callbacks
     */
    public static void setFromJNIInterface(b bVar) {
        fromJNIInterface = bVar;
        if (debug) {
            Log.d(TAG, "setFromJNIInterface: " + (bVar != null));
        }
    }
    
    /**
     * Register the listener for live video frames
     * MUST call this before video streaming to receive frames!
     */
    public static void setLiveListener(c cVar) {
        liveListener = cVar;
        if (debug) {
            Log.d(TAG, "setLiveListener: " + (cVar != null));
        }
    }
}
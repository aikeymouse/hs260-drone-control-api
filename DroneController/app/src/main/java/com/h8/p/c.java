package com.h8.p;

/* Interface for live video frame callbacks from native code */
public interface c {
    void A(int i);  // Play result callback
    void b(byte[] bArr);  // Video frame callback - delivers decoded YUV frames
    void h(boolean z, boolean z2, int[] iArr);  // Configuration callback
    void q(int i);  // Quality callback
    void w(int i);  // Network type callback
    void y(int i, int i2, byte[] bArr);  // YUV buffer callback
}

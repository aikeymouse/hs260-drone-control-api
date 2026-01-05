package org.libsdl.app;

import java.util.Arrays;

/* loaded from: classes.dex */
public class FlySendInfo {
    public static final int MAX_SIXTEEN = 16;
    public static final int MAX_TWENTY = 20;
    private int altHold;
    private int emergencyLand;
    private int follow;
    private int followDirection;
    private int followType;
    private int gesture;
    private int gyroAdjust;
    private int headless;
    private int light;
    private int oneKey;
    private int roll360;
    private int speedLevel;
    private int roll = 128;
    private int pitch = 128;
    private int accelerate = 128;
    private int yaw = 128;
    private int length = 20;
    private boolean isSetLength = false;
    private byte[] data = new byte[20];

    private int next(int i) {
        return i == 0 ? 1 : 0;
    }

    public FlySendInfo copyFlySendInfo(FlySendInfo flySendInfo) {
        flySendInfo.setSetLength(this.isSetLength);
        flySendInfo.setLength(this.length);
        return flySendInfo;
    }

    public int getAccelerate() {
        return this.accelerate;
    }

    public int getAltHold() {
        return this.altHold;
    }

    public byte[] getData() {
        return this.data;
    }

    public int getEmergencyLand() {
        return this.emergencyLand;
    }

    public int getFollow() {
        return this.follow;
    }

    public int getFollowDirection() {
        return this.followDirection;
    }

    public int getFollowType() {
        return this.followType;
    }

    public int getGesture() {
        return this.gesture;
    }

    public int getGyroAdjust() {
        return this.gyroAdjust;
    }

    public int getHeadless() {
        return this.headless;
    }

    public int getLength() {
        return this.length;
    }

    public int getLight() {
        return this.light;
    }

    public int getOneKey() {
        return this.oneKey;
    }

    public int getPitch() {
        return this.pitch;
    }

    public int getRoll() {
        return this.roll;
    }

    public int getRoll360() {
        return this.roll360;
    }

    public int getSpeedLevel() {
        return this.speedLevel;
    }

    public int getYaw() {
        return this.yaw;
    }

    public boolean isSetLength() {
        return this.isSetLength;
    }

    public void setAccelerate(int i) {
        if (i == 256) {
            i = 255;
        }
        this.accelerate = i;
    }

    public void setAltHold(int i) {
        this.altHold = i;
    }

    public void setData(byte[] bArr) {
        this.data = bArr;
    }

    public void setEmergencyLand(int i) {
        this.emergencyLand = i;
    }

    public void setFollow(int i) {
        this.follow = i;
    }

    public void setFollowDirection(int i) {
        this.followDirection = i;
    }

    public void setFollowType(int i) {
        this.followType = i;
    }

    public void setGesture(int i) {
        this.gesture = i;
    }

    public void setGyroAdjust(int i) {
        this.gyroAdjust = i;
    }

    public void setHeadless(int i) {
        this.headless = i;
    }

    public void setLength(int i) {
        this.length = i;
    }

    public void setLight(int i) {
        this.light = i;
    }

    public void setNextAltHold() {
        this.altHold = next(this.altHold);
    }

    public void setNextHeadless() {
        this.headless = next(this.headless);
    }

    public void setNextRoll360() {
        this.roll360 = next(this.roll360);
    }

    public void setNextSpeedLevel() {
        int i = this.speedLevel;
        if (i == 0) {
            this.speedLevel = 1;
        } else if (i == 1) {
            this.speedLevel = 2;
        } else {
            if (i != 2) {
                return;
            }
            this.speedLevel = 0;
        }
    }

    public void setOneKey(int i) {
        this.oneKey = i;
    }

    public void setPitch(int i) {
        if (i == 256) {
            i = 255;
        }
        this.pitch = i;
    }

    public void setRoll(int i) {
        if (i == 256) {
            i = 255;
        }
        this.roll = i;
    }

    public void setRoll360(int i) {
        this.roll360 = i;
    }

    public void setSetLength(boolean z) {
        this.isSetLength = z;
    }

    public void setSpeedLevel(int i) {
        this.speedLevel = i;
    }

    public void setYaw(int i) {
        if (i == 256) {
            i = 255;
        }
        this.yaw = i;
    }

    public String toString() {
        return "FlySendInfo{roll=" + this.roll + ", pitch=" + this.pitch + ", accelerate=" + this.accelerate + ", yaw=" + this.yaw + ", oneKey=" + this.oneKey + ", emergencyLand=" + this.emergencyLand + ", roll360=" + this.roll360 + ", headless=" + this.headless + ", altHold=" + this.altHold + ", follow=" + this.follow + ", gyroAdjust=" + this.gyroAdjust + ", speedLevel=" + this.speedLevel + ", light=" + this.light + ", gesture=" + this.gesture + ", data=" + Arrays.toString(this.data) + '}';
    }
}

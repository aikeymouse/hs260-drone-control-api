package org.libsdl.app;

/* loaded from: classes.dex */
public class FlyReceiveInfo {
    public static final int FLY_START = 1;
    public static final int FLY_STOP = 0;
    int altHold;
    int batVal;
    int calibrate;
    int controlCamera;
    int controlRecord;
    int currOver;
    int follow;
    int gale;
    int headless;
    int height6;
    public String hexString = "";
    int landed;
    int motorRunning;
    int takeOff;
    int toCamera;

    public int getAltHold() {
        return this.altHold;
    }

    public int getBatVal() {
        return this.batVal;
    }

    public int getCalibrate() {
        return this.calibrate;
    }

    public int getControlCamera() {
        return this.controlCamera;
    }

    public int getControlRecord() {
        return this.controlRecord;
    }

    public int getCurrOver() {
        return this.currOver;
    }

    public int getFollow() {
        return this.follow;
    }

    public int getGale() {
        return this.gale;
    }

    public int getHeadless() {
        return this.headless;
    }

    public int getHeight6() {
        return this.height6;
    }

    public String getHexString() {
        return this.hexString;
    }

    public int getLanded() {
        return this.landed;
    }

    public int getMotorRunning() {
        return this.motorRunning;
    }

    public int getTakeOff() {
        return this.takeOff;
    }

    public int getToCamera() {
        return this.toCamera;
    }

    public void setAltHold(int i) {
        this.altHold = i;
    }

    public void setBatVal(int i) {
        this.batVal = i;
    }

    public void setCalibrate(int i) {
        this.calibrate = i;
    }

    public void setControlCamera(int i) {
        this.controlCamera = i;
    }

    public void setControlRecord(int i) {
        this.controlRecord = i;
    }

    public void setCurrOver(int i) {
        this.currOver = i;
    }

    public void setFollow(int i) {
        this.follow = i;
    }

    public void setGale(int i) {
        this.gale = i;
    }

    public void setHeadless(int i) {
        this.headless = i;
    }

    public void setHeight6(int i) {
        this.height6 = i;
    }

    public void setHexString(String str) {
        this.hexString = str;
    }

    public void setLanded(int i) {
        this.landed = i;
    }

    public void setMotorRunning(int i) {
        this.motorRunning = i;
    }

    public void setTakeOff(int i) {
        this.takeOff = i;
    }

    public void setToCamera(int i) {
        this.toCamera = i;
    }

    public String toString() {
        return "FlyReceiveInfo{hexString='" + this.hexString + "', altHold=" + this.altHold + ", batVal=" + this.batVal + ", calibrate=" + this.calibrate + ", currOver=" + this.currOver + ", follow=" + this.follow + ", headless=" + this.headless + ", landed=" + this.landed + ", motorRunning=" + this.motorRunning + ", takeOff=" + this.takeOff + ", toCamera=" + this.toCamera + ", controlCamera=" + this.controlCamera + ", controlRecord=" + this.controlRecord + ", height6=" + this.height6 + ", gale=" + this.gale + '}';
    }
}

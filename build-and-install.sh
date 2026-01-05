#!/bin/bash
# Build and install DroneController app to connected Android device

set -e  # Exit on error

# Set Java home
export JAVA_HOME=/usr/local/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}Building DroneController app...${NC}"
cd DroneController

# Clean and build
./gradlew clean assembleDebug

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Build successful!${NC}"
else
    echo -e "${RED}❌ Build failed!${NC}"
    exit 1
fi

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}❌ No Android device connected!${NC}"
    echo "Please connect your device and enable USB debugging"
    exit 1
fi

echo -e "${YELLOW}Installing app to device...${NC}"
adb install -r app/build/outputs/apk/debug/app-debug.apk

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Installation successful!${NC}"
    echo -e "${YELLOW}Starting app...${NC}"
    adb shell am start -n com.drone.controller/.MainActivity
    echo -e "${GREEN}✅ App started!${NC}"
else
    echo -e "${RED}❌ Installation failed!${NC}"
    exit 1
fi

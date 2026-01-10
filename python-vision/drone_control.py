#!/usr/bin/env python3
"""
Drone Control Helper - Safe takeoff/land with gyro calibration

Usage:
    python drone_control.py takeoff    # Calibrate + takeoff
    python drone_control.py land       # Land
    python drone_control.py status     # Check status
    python drone_control.py calibrate  # Calibrate gyro only
"""

import requests
import json
import sys
import time


API_BASE = "http://localhost:9000"


def calibrate_gyro():
    """Calibrate gyro before takeoff."""
    print("🔧 Calibrating gyro...")
    try:
        response = requests.post(f"{API_BASE}/api/calibrate", timeout=5)
        result = response.json()
        print(f"✅ {result.get('message', 'Calibration complete')}")
        return True
    except Exception as e:
        print(f"❌ Calibration failed: {e}")
        return False


def takeoff():
    """Safe takeoff with gyro calibration."""
    print("=" * 50)
    print("🚁 SAFE TAKEOFF SEQUENCE")
    print("=" * 50)
    
    # Step 1: Calibrate gyro
    print("\n📋 Step 1: Calibrating gyro...")
    if not calibrate_gyro():
        print("❌ Cannot takeoff without calibration")
        return False
    
    # Small delay
    print("⏳ Waiting 500ms...")
    time.sleep(0.5)
    
    # Step 2: Takeoff
    print("\n📋 Step 2: Taking off...")
    try:
        response = requests.post(f"{API_BASE}/api/takeoff", timeout=5)
        result = response.json()
        print(f"✅ Takeoff command sent")
        print(f"   Status: {result.get('status', 'success')}")
        
        print("\n⚠️  IMPORTANT:")
        print("   - Keep clear of propellers")
        print("   - Be ready to land if unstable")
        print("   - Wait for drone to stabilize before enabling autopilot")
        print()
        return True
        
    except Exception as e:
        print(f"❌ Takeoff failed: {e}")
        return False


def land():
    """Land the drone."""
    print("🛬 Landing...")
    try:
        response = requests.post(f"{API_BASE}/api/land", timeout=5)
        result = response.json()
        print(f"✅ Land command sent")
        print(f"   Status: {result.get('status', 'success')}")
        return True
    except Exception as e:
        print(f"❌ Land failed: {e}")
        return False


def get_status():
    """Get drone status."""
    try:
        response = requests.get(f"{API_BASE}/api/status", timeout=2)
        status = response.json()
        
        print("=" * 50)
        print("🚁 DRONE STATUS")
        print("=" * 50)
        print(f"Connected:     {status.get('connected', False)}")
        print(f"Flying:        {status.get('flying', False)}")
        print(f"Battery:       {status.get('battery', 'N/A')}%")
        print(f"Altitude:      {status.get('altitude', 'N/A')}")
        print(f"Motors:        {'ON' if status.get('motorRunning') else 'OFF'}")
        print(f"Calibrating:   {status.get('calibrate', False)}")
        print("=" * 50)
        
        return True
    except Exception as e:
        print(f"❌ Error: {e}")
        print("\n💡 Make sure:")
        print("   1. Android app is running")
        print("   2. ADB port forwarding: adb forward tcp:9000 tcp:9000")
        return False


def print_usage():
    """Print usage instructions."""
    print(__doc__)


def main():
    if len(sys.argv) < 2:
        print_usage()
        sys.exit(1)
    
    command = sys.argv[1].lower()
    
    if command == 'takeoff':
        success = takeoff()
        sys.exit(0 if success else 1)
    elif command == 'land':
        success = land()
        sys.exit(0 if success else 1)
    elif command == 'status':
        success = get_status()
        sys.exit(0 if success else 1)
    elif command == 'calibrate':
        success = calibrate_gyro()
        sys.exit(0 if success else 1)
    else:
        print(f"❌ Unknown command: {command}")
        print_usage()
        sys.exit(1)


if __name__ == "__main__":
    main()

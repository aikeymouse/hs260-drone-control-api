# API Compatibility Notes

## HS260 Drone API Limitations

### Actual API (Port 9000)

The HS260 drone control API runs on **port 9000** (not 3000) via the Android DroneController app with ADB port forwarding:

```bash
adb forward tcp:9000 tcp:9000
```

### Supported Endpoints

The API only supports **discrete directional commands**:

| Command | Endpoint | Description |
|---------|----------|-------------|
| Move up | `POST /api/move/up` | Increase altitude |
| Move down | `POST /api/move/down` | Decrease altitude |
| Move left | `POST /api/move/left` | Move laterally left |
| Move right | `POST /api/move/right` | Move laterally right |
| Yaw left | `POST /api/yaw/left` | Rotate counter-clockwise |
| Yaw right | `POST /api/yaw/right` | Rotate clockwise |
| Stop | `POST /api/stop` | Stop all motion |
| Takeoff | `POST /api/takeoff` | Takeoff (OneKey pulse) |
| Land | `POST /api/land` | Land (OneKey pulse) |

### NOT Supported

❌ **Continuous velocity control** - No `/api/drone/velocity` endpoint  
❌ **Forward/backward motion** - Drone hardware limitation (no pitch control)  
❌ **Simultaneous commands** - Only one command active at a time  
❌ **Velocity feedback** - No telemetry for actual velocities  

## Autopilot Adaptations

The `AutopilotController` adapts to these limitations:

### 1. Velocity → Direction Conversion

```python
# Desired velocities (from vision processing)
vx = 0.15  # Forward (m/s) - IGNORED (not supported)
vy = 0.13  # Lateral (m/s) - converted to move/left or move/right
vz = 0.00  # Vertical (m/s) - converted to move/up or move/down

# Conversion logic
if abs(vy) > 0.05:  # deadband
    if vy > 0:
        command = '/api/move/left'
    else:
        command = '/api/move/right'
```

### 2. Command Priority

Since only one command can be active:

1. **Vertical** (up/down) - highest priority for safety
2. **Lateral** (left/right) - corridor centering
3. **Yaw** (rotation) - orientation adjustment

### 3. Forward Motion Limitation

**Problem:** The HS260 cannot move forward/backward independently.

**Workaround:** The drone must be **manually flown forward** while autopilot handles:
- Lateral centering (staying in middle of corridor)
- Altitude adjustment (avoiding ceiling/floor)
- Obstacle avoidance (stopping lateral motion near walls)

**Usage Pattern:**
```
1. Manually fly drone forward (user control)
2. Enable autopilot with keyboard 'e'
3. Autopilot adjusts left/right to stay centered
4. User continues forward motion manually
5. Autopilot prevents wall collisions
```

### 4. Modified Navigation Algorithm

Original bee-inspired algorithm:
```python
# Full 3D velocity control (ideal)
vx = speed_from_flow()      # Forward speed
vy = -balance * 0.3          # Lateral centering
vz = altitude_correction()   # Vertical
```

Adapted for HS260:
```python
# Lateral centering only (vx ignored)
vy = -balance * 0.3          # Converted to move/left or move/right
vz = altitude_correction()   # Converted to move/up or move/down

# User manually controls forward motion
# Autopilot provides "lane keeping" assistance
```

## Testing Implications

### What Works

✅ **Corridor centering** - Autopilot keeps drone centered using lateral motion  
✅ **Altitude hold** - Adjusts up/down to maintain safe height  
✅ **Wall avoidance** - Stops lateral motion when too close to walls  
✅ **Manual confirmation** - Each command requires user approval  

### What Doesn't Work

❌ **Fully autonomous navigation** - Cannot control forward speed  
❌ **Speed regulation** - Flow magnitude can't control forward velocity  
❌ **Automatic approach/retreat** - User must control forward motion  

### Recommended Testing Mode

**Semi-Autonomous "Lane Keeping"**:

1. User manually flies forward (throttle stick)
2. Autopilot handles left/right corrections
3. Autopilot warns of obstacles
4. User adjusts forward speed based on warnings

This is similar to:
- Car lane-keeping assist (not full self-driving)
- Drone stabilization (not waypoint navigation)

## API Endpoint Summary

### Current Setup

```
Port: 9000 (Android app)
Protocol: HTTP REST
Commands: Discrete directions only
Response: JSON status
```

### Example Command Flow

```bash
# Enable ADB forwarding
adb forward tcp:9000 tcp:9000

# Test connection
curl http://localhost:9000/api/status

# Move left (autopilot would send this)
curl -X POST http://localhost:9000/api/move/left

# Stop movement
curl -X POST http://localhost:9000/api/stop
```

### Autopilot Command Mapping

| Vision Output | Velocity | API Command |
|---------------|----------|-------------|
| Drifting right | vy = +0.13 | POST /api/move/left |
| Drifting left | vy = -0.10 | POST /api/move/right |
| Too low | vz = +0.08 | POST /api/move/up |
| Too high | vz = -0.05 | POST /api/move/down |
| Centered | vy ≈ 0 | POST /api/stop |
| Forward desired | vx = +0.15 | ❌ Not sent (user control) |

## Future Improvements

### Option 1: Enhanced API

If you can modify the Android app:

```kotlin
// Add velocity control endpoint
@POST("/api/drone/velocity")
fun setVelocity(@Body vel: VelocityCommand) {
    // Convert to FlySendInfo values
    flySendInfo.Roll = velocityToStick(vel.vy)      // lateral
    flySendInfo.Accelerate = velocityToStick(vel.vz) // vertical
    // Forward motion still not possible (hardware limit)
}
```

### Option 2: Pulse Width Modulation

Instead of binary commands, send timed pulses:

```python
# Short pulse for small correction
send('/api/move/left')
time.sleep(0.1)  # 100ms
send('/api/stop')

# Longer pulse for larger correction
send('/api/move/left')
time.sleep(0.5)  # 500ms
send('/api/stop')
```

### Option 3: Advanced Drone

Use a drone with proper SDK:
- DJI SDK - Full velocity control
- PX4 - MAVLink protocol
- ArduPilot - Mission planning

## Conclusion

The autopilot is **compatible** with the HS260 API but operates in **semi-autonomous mode**:

- ✅ Lateral centering works
- ✅ Altitude control works
- ✅ Obstacle detection works
- ❌ Speed regulation doesn't work (user controls forward motion)

**Think of it as "autopilot lane-keeping assist" rather than "full self-driving".**

The vision system provides valuable guidance even without forward velocity control!

# Autopilot Integration Guide

## Overview

The vision server now includes integrated autopilot navigation with manual confirmation mode for safe testing.

## Quick Start

### 1. Start Vision Server with Autopilot

```bash
cd python-vision
source venv/bin/activate
python vision_server.py
```

### 2. Connect and Takeoff (WITH CALIBRATION)

⚠️ **IMPORTANT:** Always calibrate gyro before takeoff!

**Option A - Using helper script (recommended):**
```bash
# This does calibration + takeoff automatically
python drone_control.py takeoff
```

**Option B - Using web interface:**
- The web interface at http://localhost:9000 automatically calibrates
- Click takeoff button (🛫) in the web UI

**Option C - Manual API calls:**
```bash
# Step 1: Calibrate gyro (REQUIRED)
curl -X POST http://localhost:9000/api/calibrate

# Step 2: Wait 500ms, then takeoff
curl -X POST http://localhost:9000/api/takeoff
```

### 3. Wait for Video Stream

The server will connect to the drone and start processing video. Once frames are received:

```
✓ Receiving and processing frames

=== MJPEG Server Started ===
  URL: http://localhost:8080
  Stream: http://localhost:8080/stream

=== AUTOPILOT CONTROLS ===
  [e] Enable autopilot
  [s] Execute single step (when enabled)
  [d] Disable autopilot
  [q] Quit server
```

⚠️ **Before enabling autopilot:**
- Drone must be flying (use `python drone_control.py takeoff`)
- Video feed must be stable (20+ FPS)
- Drone should be in stable hover

### 4. Enable Autopilot

Press `e` to enable autopilot:

```
✓ AUTOPILOT ENABLED
  Confirmation mode: ON
  Press 's' to execute single step
  Press 'd' to disable
```

### 5. Execute Navigation Steps

Press `s` to execute a single navigation step. You'll see:

```
==================================================
📋 AUTOPILOT COMMAND: ADJUST_LEFT
   Forward: +0.15 m/s
   Lateral: +0.13 m/s  ← LEFT
   Vertical: +0.00 m/s
   Yaw: +0.0 °/s
   Balance: -0.45
   Flow: 4.86 px/frame
   Danger: 0/5
==================================================
Execute? [y/N/stop]:
```

**Type your response:**
- `y` → Execute command (send to drone)
- `N` or Enter → Skip this command
- `stop` → Trigger emergency stop

### 6. Disable Autopilot

Press `d` to disable autopilot when done.

## Keyboard Controls

| Key | Action |
|-----|--------|
| `e` | Enable autopilot navigation |
| `s` | Execute single step (confirmation required) |
| `d` | Disable autopilot |
| `q` | Quit server |

## Safety Features

### Command Smoothing
- 5-sample moving average filter
- Prevents jerky motions
- Smooths out vision noise

### Rate Limiting
- Minimum 200ms between commands
- Prevents command flooding
- Protects drone from rapid oscillations

### Confirmation Mode
- Manual approval required for each command
- Displays full command details
- Shows balance, flow, danger level
- Three options: execute/skip/emergency stop

### Speed Limits
- Forward (vx): max 0.5 m/s
- Lateral (vy): max 0.3 m/s
- Vertical (vz): max 0.2 m/s
- Deadband: 0.05 m/s (ignore tiny corrections)

### Emergency Stop
- Triggered after 3 consecutive stop commands
- Auto-disables autopilot
- User can also type 'stop' during confirmation

## Navigation Algorithm

The autopilot uses bee-inspired flow balancing:

### Lateral Centering
```
balance = (left_flow - right_flow) / total_flow
vy = -balance × 0.3 m/s

Example:
  balance = -0.45  →  more flow on right  →  move LEFT (+0.135 m/s)
  balance = +0.30  →  more flow on left   →  move RIGHT (-0.09 m/s)
  balance = 0.00   →  centered            →  no lateral motion
```

### Speed Regulation
```
target_flow = 3.0 px/frame (conservative)

if flow < 1.5:      vx = 0.20 m/s   # Safe to go forward
elif flow < 3.6:    vx = 0.15 m/s   # Cruise speed
elif flow < 4.5:    vx = 0.10 m/s   # Slowing down
else:               vx = 0.00 m/s   # Stop
```

### Obstacle Avoidance
```
if danger_level >= 2:
    vx = 0.0              # Stop forward motion
    vy = vy × 1.5         # Increase lateral correction
    
if danger_level >= 3:
    try vertical motion   # Climb or descend if sides blocked
```

## Command Interpretation

### Balance Display
```
Balance: -0.45
```
- **Negative** (-0.45): More flow on RIGHT → move LEFT
- **Positive** (+0.30): More flow on LEFT → move RIGHT
- **Zero** (0.00): Centered in corridor

### Flow Magnitude
```
Flow: 4.86 px/frame
```
- **Low** (< 1.5): Far from obstacles, safe to move forward
- **Medium** (1.5-4.5): Cruising speed, normal navigation
- **High** (> 4.5): Too close, slow down or stop

### Danger Level
```
Danger: 2/5
```
- **0-1**: Safe, normal navigation
- **2**: Obstacle detected, caution
- **3**: Multiple obstacles, defensive maneuvers
- **4+**: Emergency situation, stop

### Velocity Commands
```
Forward: +0.15 m/s
Lateral: +0.13 m/s  ← LEFT
Vertical: +0.00 m/s
Yaw: +0.0 °/s
```
- **Forward** (vx): Positive = forward, 0 = stop
- **Lateral** (vy): Positive = left, negative = right
- **Vertical** (vz): Positive = up, negative = down
- **Yaw**: Rotation rate (not currently used)

## Testing Workflow

### Safe Testing Procedure

1. **Setup Environment**
   - Clear, open space
   - Remove obstacles
   - Emergency stop accessible

2. **Start Systems**
   - Launch Android DroneController app
   - Enable ADB forwarding: `adb forward tcp:9000 tcp:9000`
   - Start vision_server.py
   - Open browser to http://localhost:8080
   - Verify video feed working

3. **Takeoff with Calibration (CRITICAL)**
   ```bash
   # This calibrates gyro THEN takes off
   python drone_control.py takeoff
   ```
   - Wait for stable hover
   - Check video stream is smooth (20+ FPS)
   - Verify balance bar visible in browser

4. **Test Without Executing**
   - Enable autopilot (`e`)
   - Execute steps (`s`)
   - Press `N` to skip actual execution
   - Verify command calculations correct

5. **Test Stationary Hover**
   - Drone already in stable hover from takeoff
   - Enable autopilot
   - Execute single step with `y`
   - Observe drone response
   - Disable immediately if unexpected

6. **Test Corridor Navigation**
   - Start in middle of hallway
   - Enable autopilot
   - Execute steps one at a time
   - Monitor balance staying near 0
   - Watch for smooth lateral corrections

7. **Test Obstacle Avoidance**
   - Approach wall slowly
   - Watch danger level increase
   - Verify forward motion stops
   - Confirm lateral/vertical suggestions

8. **Landing**
   ```bash
   # Safe landing
   python drone_control.py land
   ```

### Troubleshooting

**No Vision Data**
- Check browser shows video at http://localhost:8080
- Verify vision_server.py receiving frames
- Check FPS counter updating

**Commands Not Executing**
- Verify drone API running at http://localhost:3000
- Check API endpoint: POST /api/drone/velocity
- Look for error messages in terminal

**Erratic Navigation**
- Check FPS (should be 20-25)
- Verify balance bar stable in video
- May need to tune control gains
- Try lower max speeds

**Emergency Stop Triggered**
- Check why 3 consecutive stops occurred
- May indicate trapped/cornered situation
- Disable and manually recover drone
- Review obstacle detection zones

## Advanced Usage

### Disable Confirmation Mode

Edit `vision_server.py`, change:
```python
self.autopilot = AutopilotController(
    api_url="http://localhost:3000",
    confirmation_mode=False,  # ← Disable confirmation
    command_history_size=5
)
```

**⚠️ WARNING**: Only use without confirmation after extensive testing!

### Tune Control Gains

Edit `autopilot.py`:
```python
# More aggressive lateral correction
self.balance_gain = 0.5  # Default: 0.3

# Faster speed regulation
self.target_flow = 5.0   # Default: 3.0

# Higher max speeds
self.max_vx = 1.0        # Default: 0.5 m/s
```

### Add Continuous Mode

Currently step-by-step only. To add continuous execution:

```python
def autopilot_continuous(self):
    """Run autopilot in continuous mode."""
    while self.autopilot_enabled:
        self.autopilot_step()
        time.sleep(0.2)  # Rate limiting
```

## API Integration

### Velocity Command Format

The autopilot sends POST requests to:
```
http://localhost:3000/api/drone/velocity
```

With JSON payload:
```json
{
  "vx": 0.15,    // Forward velocity (m/s)
  "vy": 0.13,    // Lateral velocity (m/s)
  "vz": 0.00,    // Vertical velocity (m/s)
  "yaw_rate": 0  // Rotation rate (°/s)
}
```

### Expected Response

Success:
```json
{
  "status": "ok"
}
```

Error:
```json
{
  "status": "error",
  "message": "Connection lost"
}
```

## Files

- `autopilot.py` - Core autopilot controller class
- `autopilot_runner.py` - Standalone demo with simulated data
- `drone_control.py` - Safe takeoff/land helper (with calibration)
- `vision_server.py` - Vision processing + autopilot integration
- `NAVIGATION_GUIDE.md` - Detailed vision system guide
- `NAVIGATION_PLAN.md` - Bio-inspired navigation research
- `API_COMPATIBILITY.md` - HS260 API limitations and workarounds

## Helper Commands

```bash
# Safe takeoff (calibrates gyro automatically)
python drone_control.py takeoff

# Land
python drone_control.py land

# Check drone status
python drone_control.py status

# Calibrate gyro only (without takeoff)
python drone_control.py calibrate
```

## Next Steps

1. ✅ Test confirmation mode with simulated data (`autopilot_runner.py`)
2. ✅ Test with live vision (`vision_server.py` + keyboard controls)
3. ⏳ Test with real drone (stationary hover first)
4. ⏳ Test corridor navigation
5. ⏳ Tune gains based on flight tests
6. ⏳ Add telemetry logging
7. ⏳ Implement continuous mode
8. ⏳ Add ground station UI

## Safety Checklist

Before flying with autopilot:

- [ ] **Gyro calibrated** (use `python drone_control.py takeoff`)
- [ ] Confirmation mode enabled
- [ ] Emergency stop functional
- [ ] Clear flight area
- [ ] Manual override ready
- [ ] Kill switch accessible
- [ ] Battery charged (>50%)
- [ ] ADB forwarding active (`adb forward tcp:9000 tcp:9000`)
- [ ] Video feed stable (20+ FPS)
- [ ] API connectivity verified
- [ ] Test hover completed successfully
- [ ] Spotter present

**Critical: Always calibrate gyro before takeoff! Never skip calibration!**

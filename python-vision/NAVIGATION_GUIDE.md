# Vision-Based Navigation Guide for HS260 Drone

## Overview

This guide explains how to interpret the vision processing output and use it for autonomous drone navigation. The system implements **bio-inspired navigation** based on honeybee flight strategies, using optical flow balancing for corridor centering and obstacle avoidance.

---

## Visual Interface Elements

### 1. Flow Balance Bar (Bottom of Screen)

**Location:** Bottom center of video feed

**Display:**
```
L ←―――――――●―――――――→ R
```

**Interpretation:**
- **White circle in center** = Balanced flow (drone centered in corridor)
- **Circle shifted LEFT** = More flow on right side → Move RIGHT to center
- **Circle shifted RIGHT** = More flow on left side → Move LEFT to center
- **Orange/Yellow circle** = Imbalanced (needs correction)
- **Green circle** = Well-balanced (good positioning)

**Balance Value:** `-1.0` (go right) to `+1.0` (go left)

---

### 2. Balance Value Display

**Format:** `Balance: X.XX`

**Ranges:**
```
Balance < -0.3   →  RIGHT side has more flow  →  Move LEFT
Balance > +0.3   →  LEFT side has more flow   →  Move RIGHT
-0.3 to +0.3     →  CENTERED (optimal)
```

**Example from screenshot:**
- `Balance: -0.45` = Right wall closer or moving faster past right side
- **Action:** Move LEFT to equalize flow and center in corridor

---

### 3. Navigation Recommendation

**Format:** `Nav: ACTION`

**Possible values:**
- `CENTERED` = Drone is well-centered, continue current path
- `ADJUST_LEFT` = Move left to center in corridor
- `ADJUST_RIGHT` = Move right to center in corridor
- `SLOW_DOWN` = Too much ventral flow, reduce speed
- `CONTINUE` = All clear, maintain course

**Example from screenshot:**
- `Nav: ADJUST_LEFT` = System recommends moving left to achieve balance

---

### 4. Flight Command

**Format:** `Command: DIRECTION`

**Possible values:**
- `OK` = All clear, safe to continue
- `LEFT` = Recommended to move left
- `RIGHT` = Recommended to move right
- `UP` = Recommended to move up
- `DOWN` = Recommended to move down
- `SLOW` = Reduce forward speed
- `STOP` = Emergency stop (imminent collision)

**Example from screenshot:**
- `Command: LEFT` = Final flight command based on balance + obstacles

---

### 5. Zone Grid (4×3 Grid Overlay)

**Grid Layout:**
```
┌─────┬─────┬─────┬─────┐
│ 0,0 │ 1,0 │ 2,0 │ 3,0 │  Top row (UP)
├─────┼─────┼─────┼─────┤
│ 0,1 │ 1,1 │ 2,1 │ 3,1 │  Middle row
├─────┼─────┼─────┼─────┤
│ 0,2 │ 1,2 │ 2,2 │ 3,2 │  Bottom row (DOWN)
└─────┴─────┴─────┴─────┘
 LEFT         CENTER  RIGHT
```

**Zone Colors:**
- **Green border (thin)** = Clear zone, no obstacles detected
- **Yellow border** = Caution - obstacle detected, TTC > 1.5s
- **Orange border (thick)** = Warning - obstacle approaching, TTC 1.0-1.5s
- **Red border (very thick)** = Danger - imminent collision, TTC < 1.0s

**Zone Label:** Small number showing tracked feature points in that zone

**Example from screenshot:**
- Zone [1,1] (center-left, middle row) = Orange border = Warning
- Red text: "WARNING: Zone [1,1] approaching"

---

### 6. Directional Arrows

**Location:** Four arrows at edges of frame

**Colors:**
- **Green arrow** = Safe to move in that direction
- **Red arrow** = Blocked, obstacle detected

**Positions:**
- **Top center** = UP
- **Bottom center** = DOWN
- **Left edge** = LEFT
- **Right edge** = RIGHT

---

### 7. Optical Flow Visualization

**Green circles** = Feature tracking points
**Green lines** = Motion vectors showing optical flow direction

**Interpretation:**
- Dense green points = Good tracking
- Lines radiating outward = Moving forward (expansion)
- Lines converging = Moving backward
- Asymmetric patterns = Obstacles or rotation

---

### 8. Status Information

**Top-left corner:**
- `FPS: 23.0` = Processing frame rate (20-30 is good)
- `Command: LEFT` = Current flight recommendation
- `Flow: 4.86` = Average optical flow magnitude (pixels/frame)
- `Frame: 1311` = Frame counter

**Red warning messages** appear when zones change status

---

## Navigation Strategies

### Strategy 1: Corridor Centering (Bee Navigation)

**Principle:** Maintain equal optical flow on left and right sides

**Implementation:**
1. Monitor balance value continuously
2. If balance < -0.3 → Apply left velocity correction
3. If balance > +0.3 → Apply right velocity correction
4. Target: Keep balance between -0.3 and +0.3

**Control law:**
```python
lateral_velocity = -balance_value * gain  # gain ≈ 0.5 to 1.0
```

**Example:**
```
Balance = -0.45  →  lateral_velocity = -(-0.45) * 0.8 = +0.36  →  Move LEFT
Balance = +0.60  →  lateral_velocity = -(+0.60) * 0.8 = -0.48  →  Move RIGHT
Balance = +0.10  →  lateral_velocity = -(+0.10) * 0.8 = -0.08  →  Minor correction RIGHT
```

---

### Strategy 2: Speed Regulation

**Principle:** Maintain constant ventral (bottom) optical flow

**Flow magnitude guidelines:**
- **Target:** 5.0 pixels/frame (optimal)
- **Too slow:** < 2.5 pixels/frame → Increase forward speed
- **Too fast:** > 7.5 pixels/frame → Decrease forward speed
- **Emergency:** > 10.0 pixels/frame → Stop immediately

**Control law:**
```python
speed_error = (current_flow - target_flow) / target_flow
forward_velocity = -speed_error * gain  # gain ≈ 0.5
```

**Example from screenshot:**
```
Flow: 4.86  →  Close to target (5.0)  →  Maintain current speed
```

---

### Strategy 3: Obstacle Avoidance

**Priority hierarchy:**

1. **Emergency stop** (highest priority)
   - Any zone with red border
   - TTC < 0.5s
   - Command: STOP immediately

2. **Avoid blocked directions**
   - Check directional arrows
   - Red arrow = blocked, don't go that way
   - Choose alternative green arrow direction

3. **Zone-based avoidance**
   - Orange/yellow zones → Prepare to maneuver
   - Monitor TTC values
   - Slow down if multiple zones show warnings

**Decision tree:**
```
IF danger_level >= 3:
    STOP
ELSE IF forward_blocked:
    IF up_safe:
        MOVE UP
    ELSE IF left_safe AND balance > 0:
        MOVE LEFT
    ELSE IF right_safe AND balance < 0:
        MOVE RIGHT
    ELSE:
        STOP
ELSE IF balance < -0.3:
    MOVE LEFT (to center)
ELSE IF balance > +0.3:
    MOVE RIGHT (to center)
ELSE:
    CONTINUE FORWARD
```

---

### Strategy 4: Combined Navigation

**Optimal approach combines all strategies:**

```python
# 1. Check for immediate danger
if any_zone_red or ttc_min < 0.5:
    command = "STOP"
    
# 2. Check forward path
elif forward_blocked:
    command = select_alternative_direction()
    
# 3. Apply balance correction
elif abs(balance) > 0.3:
    if balance < -0.3:
        command = "LEFT"
    else:
        command = "RIGHT"
    
# 4. Regulate speed
if flow_magnitude > 7.5:
    command = "SLOW"
elif flow_magnitude < 2.5 and no_obstacles:
    command = "ACCELERATE"
    
# 5. Default
else:
    command = "OK"
```

---

## Interpretation Examples

### Example 1: Centered in Corridor

**Display:**
```
Balance: 0.12
Nav: CENTERED
Command: OK
Flow: 5.1
```

**Interpretation:**
- Nearly balanced (0.12 is close to 0)
- Flow magnitude at target (5.1 ≈ 5.0)
- All zones green
- **Action:** Continue forward, maintain current speed

---

### Example 2: Too Close to Right Wall (Current Screenshot)

**Display:**
```
Balance: -0.45
Nav: ADJUST_LEFT
Command: LEFT
Flow: 4.86
Zone [1,1]: Orange (warning)
```

**Interpretation:**
- Negative balance (-0.45) = Right side has more flow
- Right wall is closer or moving faster past it
- Center-left zone shows warning (door edge approaching)
- Flow is good (4.86 ≈ 5.0)
- **Action:** Move LEFT to center, slow approach to maintain TTC

---

### Example 3: Approaching Obstacle

**Display:**
```
Balance: 0.05
Nav: CENTERED
Command: STOP
Flow: 8.2
Zone [1,1]: Red (danger)
Zone [2,1]: Red (danger)
```

**Interpretation:**
- Well-centered laterally (balance ≈ 0)
- BUT high flow (8.2) = moving too fast
- Center zones red = imminent collision
- **Action:** EMERGENCY STOP, then assess alternative paths

---

### Example 4: Corridor Turn (More Flow on Left)

**Display:**
```
Balance: +0.65
Nav: ADJUST_RIGHT
Command: RIGHT
Flow: 4.2
```

**Interpretation:**
- Positive balance (+0.65) = Left side has more flow
- Likely approaching left turn or too close to left wall
- Flow slightly low (4.2) but acceptable
- **Action:** Move RIGHT to center, can increase speed slightly

---

## Autonomous Flight Logic

### Basic Autopilot Algorithm

```python
def autopilot_step(vision_result):
    """
    Execute one step of autonomous navigation.
    
    Args:
        vision_result: Output from obstacle detector
    
    Returns:
        (vx, vy, vz, yaw_rate): Control commands
    """
    # Extract key metrics
    balance = vision_result['balance']['lateral_balance']
    flow_mag = vision_result['flow_magnitude']
    danger = vision_result['danger_level']
    safe_dirs = vision_result['safe_directions']
    
    # Initialize control commands
    vx = 0  # forward/back
    vy = 0  # left/right
    vz = 0  # up/down
    yaw_rate = 0  # rotation
    
    # Emergency stop
    if danger >= 3:
        return (0, 0, 0, 0)
    
    # Lateral balance control (centering)
    BALANCE_GAIN = 0.5
    vy = -balance * BALANCE_GAIN  # Negative because balance is inverted
    
    # Forward speed control
    TARGET_FLOW = 5.0
    SPEED_GAIN = 0.3
    flow_error = (flow_mag - TARGET_FLOW) / TARGET_FLOW
    vx = 0.5 - (flow_error * SPEED_GAIN)  # Base speed 0.5 m/s
    
    # Obstacle avoidance
    if not safe_dirs['forward']:
        vx = 0  # Stop forward motion
        if safe_dirs['up']:
            vz = 0.3  # Move up
        elif safe_dirs['left'] and balance > -0.2:
            vy = 0.5  # Move left if not already too far left
        elif safe_dirs['right'] and balance < 0.2:
            vy = -0.5  # Move right if not already too far right
        else:
            return (0, 0, 0, 0)  # Stop completely
    
    # Clamp velocities to safe limits
    vx = np.clip(vx, -0.5, 1.0)
    vy = np.clip(vy, -0.5, 0.5)
    vz = np.clip(vz, -0.3, 0.3)
    
    return (vx, vy, vz, yaw_rate)
```

---

## Performance Metrics

### Good Performance Indicators

✅ **FPS:** 20-30 (smooth real-time processing)
✅ **Balance:** -0.3 to +0.3 (centered)
✅ **Flow:** 4.0 to 6.0 (optimal speed)
✅ **All zones:** Green (clear path)
✅ **Tracking points:** 50+ visible (good feature detection)

### Warning Signs

⚠️ **FPS:** < 15 (processing too slow, reduce features)
⚠️ **Balance:** > |0.5| (significantly off-center)
⚠️ **Flow:** > 8.0 (too fast, risk of collision)
⚠️ **Orange zones:** Approaching obstacles
⚠️ **Tracking points:** < 20 (poor lighting or texture)

### Critical Issues

🛑 **Red zones:** Immediate collision risk
🛑 **Command: STOP:** Emergency situation
🛑 **All arrows red:** Trapped, no safe direction
🛑 **Flow:** > 12.0 (way too fast)
🛑 **FPS:** < 10 (processing breakdown)

---

## Best Practices

### 1. Pre-Flight Checklist
- ✅ FPS > 20 in preview
- ✅ Green tracking points visible
- ✅ Balance bar responding to camera movement
- ✅ Zone grid overlay visible
- ✅ Ensure good lighting (not too dark, avoid direct sunlight)

### 2. Manual Flight Training
Before autopilot:
1. Fly manually while watching balance bar
2. Practice keeping balance near 0.0
3. Observe how obstacles appear in zones
4. Test emergency stop response time

### 3. Corridor Selection
**Good corridors:**
- Well-lit, even lighting
- Textured walls (not blank white)
- Width: 1-3 meters
- Straight or gentle curves

**Avoid:**
- Very narrow (< 1m)
- Glass walls (poor features)
- Mirrors (confusing reflections)
- Very dark or bright areas

### 4. Speed Guidelines
- **Learning:** Start at low flow (2-3 pixels/frame)
- **Cruising:** Maintain 4-6 pixels/frame
- **Max safe:** < 8 pixels/frame
- **Emergency only:** > 8 pixels/frame

### 5. Monitoring During Flight
Watch for:
- Balance trends (drifting left or right over time)
- Zone colors changing (early warning)
- FPS drops (processing overload)
- Sudden flow spikes (approaching wall)

---

## Troubleshooting

### Issue: Balance jumps around randomly

**Causes:**
- Poor lighting (shadows, flicker)
- Blank walls (no texture)
- Too few tracking points

**Solutions:**
- Add texture to environment (posters, patterns)
- Increase lighting
- Lower flight speed
- Reduce maxCorners in feature detector

---

### Issue: Always shows "ADJUST_LEFT" or "ADJUST_RIGHT"

**Causes:**
- Asymmetric corridor (one wall closer)
- Camera not centered on drone
- Light source on one side

**Solutions:**
- Adjust camera mount
- Accept asymmetry, use balance threshold
- Add artificial texture to smooth wall

---

### Issue: Orange/red zones even when far from walls

**Causes:**
- Moving objects (people, other drones)
- Lighting changes (shadows moving)
- Reflections
- Overly sensitive thresholds

**Solutions:**
- Increase expansion_threshold (currently 1.5)
- Increase TTC thresholds (danger: 1.0s, warning: 2.0s)
- Fly in static environment first

---

### Issue: Low FPS (< 15)

**Causes:**
- Too many feature points
- CPU overloaded
- Large video resolution

**Solutions:**
- Reduce maxCorners (currently 150 → try 100)
- Reduce grid_size (4x3 → 3x2)
- Close other applications
- Check for memory leaks

---

## Future Enhancements

### Planned Features (from NAVIGATION_PLAN.md)

1. **Proper Divergence Field** (Phase 3)
   - True ∇·v calculation
   - Separates expansion from rotation
   - More accurate obstacle detection

2. **Looming Detector** (Phase 5)
   - LGMD-inspired rapid expansion detection
   - Emergency collision avoidance
   - < 1ms processing time

3. **IMU Fusion** (if accessible)
   - Combine optical flow with gyro/accel
   - Ego-motion compensation
   - More robust heading estimation

4. **Adaptive Thresholds**
   - Auto-adjust based on environment
   - Learn from successful flights
   - Speed-dependent safety margins

---

## Quick Reference Card

```
┌─────────────────────────────────────────────────────┐
│         VISION NAVIGATION QUICK GUIDE               │
├─────────────────────────────────────────────────────┤
│ BALANCE BAR:                                        │
│   Center (±0.3)     →  Good, centered              │
│   Left (<-0.3)      →  Move RIGHT                  │
│   Right (>+0.3)     →  Move LEFT                   │
│                                                     │
│ FLOW MAGNITUDE:                                     │
│   2-3 px/frame      →  Slow, safe learning         │
│   4-6 px/frame      →  Optimal cruise              │
│   7-8 px/frame      →  Fast, caution               │
│   >8 px/frame       →  TOO FAST, slow down         │
│                                                     │
│ ZONE COLORS:                                        │
│   Green             →  Clear                       │
│   Yellow/Orange     →  Approaching (1-2s)          │
│   Red               →  DANGER (< 1s)               │
│                                                     │
│ COMMANDS:                                           │
│   OK                →  Continue                    │
│   LEFT/RIGHT        →  Lateral correction          │
│   SLOW              →  Reduce speed                │
│   STOP              →  Emergency halt              │
│                                                     │
│ SAFE FPS:           20-30                          │
│ TARGET BALANCE:     -0.3 to +0.3                   │
│ TARGET FLOW:        5.0 px/frame                   │
└─────────────────────────────────────────────────────┘
```

---

## References

**Bio-Inspired Navigation:**
- Srinivasan et al. (1996) - Honeybee odometry
- Franceschini et al. (2007) - Optic flow regulation
- Floreano & Zufferey (2010) - Fly-inspired steering

**Implementation:**
- `/python-vision/NAVIGATION_PLAN.md` - Full development roadmap
- `/python-vision/vision/obstacle_detector_fast.py` - Core detector
- `/python-vision/vision/navigation/flow_balancer.py` - Balance logic

---

**Last Updated:** January 6, 2026  
**System Version:** HS260 Vision Processing v2.0 (Sparse Flow + Balance)

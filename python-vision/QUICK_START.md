# Autopilot Quick Start

## 🎯 What's New

Your vision server now has **integrated autopilot navigation** with full safety features!

## 🚀 Run It Now

### Option 1: Demo Mode (No Drone Required)

Test autopilot with simulated vision data:

```bash
cd python-vision
source venv/bin/activate
python autopilot_runner.py
```

Follow prompts to test 4 scenarios:
- Centered corridor navigation
- Drifting correction (balance -0.45)
- Obstacle avoidance
- Narrow corridor handling

### Option 2: Live Navigation (With Drone)

**Step 1:** Takeoff with gyro calibration (REQUIRED):

```bash
cd python-vision
source venv/bin/activate

# Safe takeoff (auto-calibrates gyro)
python drone_control.py takeoff
```

**Step 2:** Run vision server with autopilot:

```bash
python vision_server.py
```

**Keyboard Controls:**
- Press `e` → Enable autopilot
- Press `s` → Execute single step (with confirmation)
- Press `d` → Disable autopilot
- Press `q` → Quit

## 📺 Monitor Progress

Open browser: **http://localhost:8080**

Watch the live video with:
- Balance bar (L ←―●―→ R)
- Obstacle zones (green/orange/red)
- Safe direction arrows
- Navigation recommendations

## ✅ Safety Features Active

✓ **Manual confirmation** - You approve each command  
✓ **Command smoothing** - 5-step moving average filter  
✓ **Rate limiting** - Max 1 command per 0.2s  
✓ **Speed limits** - Conservative max 0.5 m/s forward  
✓ **Emergency stop** - Auto-triggers on repeated failures  

## 📋 What You'll See

When you press `s` (step), you'll get a confirmation prompt:

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

**Your options:**
- Type `y` → Send command to drone
- Type `N` or Enter → Skip this command
- Type `stop` → Emergency stop

## 🧭 Understanding the Commands

### Balance Value
- **-0.45** = More flow on right → Move LEFT to center
- **+0.30** = More flow on left → Move RIGHT to center
- **0.00** = Perfectly centered

### Flow Magnitude
- **< 1.5** = Far from obstacles, safe to go forward
- **1.5-4.5** = Normal cruising speed
- **> 4.5** = Too close, slow down or stop

### Danger Level
- **0-1** = Safe, normal navigation
- **2** = Obstacle detected, use caution
- **3+** = Multiple obstacles, defensive maneuvers

## 📖 Full Documentation

- **AUTOPILOT_README.md** - Complete integration guide
- **NAVIGATION_GUIDE.md** - Vision system interpretation (40+ pages)
- **NAVIGATION_PLAN.md** - Bio-inspired navigation research

## ⚠️ Safety First

**Before real flight:**
1. Test with demo mode (`autopilot_runner.py`)
2. Test with live vision, pressing `N` to skip execution
3. Ensure clear flight area
4. Have manual override ready
5. Start with stationary hover test
6. Only use `y` when confident

**Never disable confirmation mode until fully tested!**

## 🐛 Troubleshooting

**No autopilot controls showing?**
- Check vision_server.py started successfully
- Verify frames being received (FPS counter updating)

**Commands not executing?**
- Verify drone API running at http://localhost:3000
- Check for error messages in terminal

**Strange navigation?**
- Check video FPS (should be 20-25)
- Verify balance bar stable in browser
- May need to tune control gains

## 🎓 How It Works

Uses **bee-inspired flow balancing**:

1. **Lateral centering**: Compares left vs right optical flow
   - More flow on right → move left
   - Keeps drone centered in corridors

2. **Speed regulation**: Monitors ventral (bottom) flow
   - Low flow → safe to go faster
   - High flow → slow down, getting close

3. **Obstacle avoidance**: Analyzes 12 zones (4×3 grid)
   - Expanding zones → approaching obstacles
   - Stops forward motion when needed
   - Suggests safe alternative directions

All running at **20-25 FPS** with smooth, safe control!

---

**Ready to fly? Start with `python autopilot_runner.py` to test safely! 🚁**

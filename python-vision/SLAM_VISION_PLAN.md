# ORB-SLAM2 Vision System for HS260 Drone

## Overview

Implement monocular ORB-SLAM2 for real-time camera pose tracking and 3D mapping from the drone's H.264 video stream, enabling autonomous position control without any artificial markers.

## Architecture

```
HS260 Drone (H.264 @ 720p, 25fps)
    ↓ UDP:1563
Android App (DroneController)
    ↓ WebSocket:9000
Python SLAM System
    ├── Stream Decoder (H.264 → Grayscale frames)
    ├── ORB Feature Tracker (2000 keypoints/frame)
    ├── Visual Odometry (pose estimation)
    ├── Map Management (3D points, keyframes)
    ├── Loop Closure Detection
    ├── Pose Graph Optimization (g2o)
    ├── PID Controller (position stabilization)
    └── Visualization (Pangolin 3D view)
    ↓ HTTP API
Android App → HS260 Drone (control commands)
```

## Implementation Plan

### Step 1: Setup Python Environment

**Create:** `requirements.txt`
```
opencv-contrib-python>=4.8.0  # Includes ORB, SIFT, feature matching
numpy>=1.24.0
websockets>=12.0              # WebSocket client for video stream
requests>=2.31.0              # HTTP API for drone control
av>=11.0.0                    # PyAV for H.264 decoding
g2o-python>=0.0.1            # Graph optimization backend
pangolin>=0.8.0              # 3D visualization (or use Open3D)
scipy>=1.11.0                # Matrix operations, optimization
```

**Install:**
```bash
cd /Users/denisemelyanov/Projects/HS-drone-hack
source venv/bin/activate
pip install -r requirements.txt
```

---

### Step 2: WebSocket H.264 Stream Decoder

**Create:** `vision/stream_decoder.py`

**Functionality:**
- Connect to `ws://localhost:9000/video`
- Receive raw H.264 NAL units (SPS, PPS, I-frames, P-frames)
- Reconstruct H.264 stream with proper headers
- Decode to grayscale frames using PyAV or OpenCV
- Output 720p grayscale frames for feature extraction

**Key Components:**
```python
class H264StreamDecoder:
    def __init__(self, websocket_url):
        # WebSocket connection
        # PyAV container for H.264 decoding
        # Frame buffer queue
        
    async def connect(self):
        # Connect to WebSocket
        # Receive SPS/PPS parameters
        
    async def receive_frames(self):
        # Receive NAL units
        # Feed to decoder
        # Convert to grayscale
        # Yield frames to SLAM pipeline
```

**Performance Target:** 15-25 FPS decode throughput

---

### Step 3: ORB Feature Tracking

**Create:** `vision/orb_tracker.py`

**Functionality:**
- Extract ORB keypoints and descriptors from grayscale frames
- Match features between consecutive frames
- Filter matches using ratio test and geometric verification
- Estimate frame-to-frame transformation

**Key Components:**
```python
class ORBTracker:
    def __init__(self, num_features=2000):
        self.orb = cv2.ORB_create(nFeatures=num_features)
        self.matcher = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=False)
        
    def extract_features(self, frame):
        # Detect ORB keypoints
        # Compute descriptors
        # Return (keypoints, descriptors)
        
    def match_features(self, desc1, desc2):
        # Find matches using Hamming distance
        # Apply Lowe's ratio test (0.7-0.8 threshold)
        # Return good matches
        
    def estimate_motion(self, kp1, kp2, matches, camera_matrix):
        # Extract matched point pairs
        # Compute Essential Matrix (5-point algorithm)
        # Decompose to R, t (rotation, translation)
        # Return camera pose transformation
```

**Parameters:**
- ORB features: 2000 keypoints per frame
- Matching threshold: Hamming distance < 50
- Ratio test: 0.75

---

### Step 4: Visual Odometry System

**Create:** `vision/visual_odometry.py`

**Functionality:**
- Maintain camera pose (position + orientation)
- Track frame-to-frame transformations
- Build local map of 3D feature points
- Detect and handle loop closures
- Optimize trajectory using pose graph

**Key Components:**
```python
class VisualOdometry:
    def __init__(self, camera_matrix):
        self.camera_matrix = camera_matrix
        self.current_pose = np.eye(4)  # 4x4 transformation matrix
        self.trajectory = []
        self.map_points = []  # 3D points in world frame
        self.keyframes = []
        
    def process_frame(self, frame, timestamp):
        # Extract features
        # Match with previous frame
        # Estimate relative motion
        # Update current pose
        # Triangulate new 3D points
        # Add to local map
        
    def add_keyframe(self, frame, pose):
        # Determine if frame should be keyframe
        # Store keyframe data
        # Update map points
        
    def detect_loop_closure(self, frame):
        # Compare current frame with past keyframes
        # Use ORB descriptor bag-of-words
        # Return loop closure candidates
        
    def optimize_trajectory(self):
        # Build pose graph
        # Add loop closure constraints
        # Optimize using g2o
        # Update all poses and map points
```

**Keyframe Selection Criteria:**
- Minimum 20 frames since last keyframe
- Sufficient feature overlap (< 70% matches with previous keyframe)
- Sufficient parallax for triangulation

---

### Step 5: PID Position Controller

**Create:** `vision/slam_controller.py`

**Functionality:**
- Convert SLAM camera pose to drone position
- Implement PID controllers for X, Y, Z, Yaw
- Send control commands to HTTP API
- Handle target position updates

**Key Components:**
```python
class SLAMController:
    def __init__(self, api_url="http://localhost:9000"):
        self.api = requests.Session()
        self.pid_x = PIDController(kp=0.5, ki=0.1, kd=0.2)
        self.pid_y = PIDController(kp=0.5, ki=0.1, kd=0.2)
        self.pid_z = PIDController(kp=0.6, ki=0.1, kd=0.25)
        self.pid_yaw = PIDController(kp=0.8, ki=0.05, kd=0.15)
        self.target_position = np.array([0, 0, 1.5])  # meters
        
    def update(self, current_pose, dt):
        # Extract position from pose matrix
        # Calculate errors
        # Compute PID outputs
        # Map to stick commands (-100 to +100)
        # Send HTTP API commands
        
    def send_control(self, pitch, roll, throttle, yaw):
        # POST /api/move/forward (pitch)
        # POST /api/move/right (roll)
        # POST /api/move/up (throttle)
        # POST /api/yaw/right (yaw)
        
    def set_target(self, x, y, z, yaw=0):
        # Update target position
```

**Control Mapping:**
- X (forward/back) → Pitch stick
- Y (left/right) → Roll stick
- Z (up/down) → Throttle stick
- Rotation → Yaw stick

**PID Update Rate:** 20-30 Hz

---

### Step 6: Real-time Visualization

**Create:** `vision/slam_visualizer.py`

**Functionality:**
- Display video feed with tracked features
- Show 3D point cloud map
- Render camera trajectory
- Display current position estimate

**Key Components:**
```python
class SLAMVisualizer:
    def __init__(self):
        self.pangolin_viewer = None  # 3D viewer
        self.opencv_display = True   # 2D video display
        
    def draw_features(self, frame, keypoints, matches):
        # Draw keypoints on frame
        # Draw feature matches
        # Show in OpenCV window
        
    def update_3d_view(self, pose, map_points, trajectory):
        # Update camera pose in 3D view
        # Render point cloud
        # Draw trajectory path
        # Draw coordinate axes
        
    def display_info(self, frame, pose, num_features, fps):
        # Overlay text on video frame
        # Position: (X, Y, Z)
        # Orientation: (roll, pitch, yaw)
        # Features tracked: N
        # FPS: XX
```

**Display Windows:**
1. **Video Feed** (OpenCV): Frame with features overlay
2. **3D Map** (Pangolin): Point cloud, trajectory, camera pose
3. **Telemetry Panel**: Position, velocity, battery, FPS

---

## Camera Calibration

### Initial Setup

Before running SLAM, camera calibration is required for accurate metric scale.

**Option 1: Manual Calibration**
```bash
python vision/calibrate_camera.py \
  --pattern checkerboard \
  --size 9x6 \
  --square_size 0.025  # 25mm squares
```

**Option 2: Approximate Parameters (HS260)**
```python
# Estimated camera parameters for HS260
focal_length = 600  # pixels (approximate for 720p, ~60° FOV)
camera_matrix = np.array([
    [focal_length, 0, 640],   # cx = width/2
    [0, focal_length, 360],   # cy = height/2
    [0, 0, 1]
])
distortion_coeffs = np.array([0.1, -0.05, 0, 0, 0])  # Barrel distortion
```

**Save calibration to:** `vision/camera_calibration.json`

---

## Scale Initialization

Monocular SLAM cannot determine absolute scale. Solutions:

### Option 1: Known Altitude Initialization
```python
# Assume takeoff altitude = 1.5 meters
# Scale SLAM trajectory to match known height
initial_height = 1.5  # meters
slam_scale = initial_height / slam_estimated_height
```

### Option 2: Visual Reference Object
```python
# Place known-size object in first frame
# Measure object size in pixels
# Calculate scale from known real-world size
reference_object_size = 0.3  # meters (e.g., 30cm box)
```

### Option 3: Auto-scale from Motion
```python
# Assume slow takeoff with known throttle
# Estimate vertical velocity from control input
# Integrate to get height, compare with SLAM
```

**Recommended:** Option 1 (known altitude) for simplicity

---

## Performance Optimization

### Frame Processing Pipeline

**Full SLAM Mode** (accurate but slow):
- Process: 10-15 FPS
- ORB extraction: Every frame
- Loop closure: Every 20 frames
- Optimization: Every 50 frames

**Fast Tracking Mode** (real-time):
- Process: 25-30 FPS
- ORB extraction: Keyframes only (every 10-15 frames)
- Optical flow: Inter-frame tracking (sparse Lucas-Kanade)
- Loop closure: Disabled or background thread
- Optimization: Background thread

### CPU Optimization Strategies

1. **Reduce Resolution:**
   - Process at 480p instead of 720p
   - Downscale factor: 0.67x

2. **Sparse Feature Tracking:**
   - Track only 500-1000 features between keyframes
   - Full ORB extraction only for keyframes

3. **Multi-threading:**
   ```python
   # Thread 1: Frame decoding
   # Thread 2: Feature extraction + tracking
   # Thread 3: Mapping + optimization
   # Thread 4: Visualization
   # Thread 5: Control loop
   ```

4. **Skip Frames:**
   - Process every 2nd or 3rd frame
   - Still achieves 10-15 Hz position updates

---

## Integration with Existing System

### Required Changes

**None to Android App** - System runs entirely in Python, uses existing:
- WebSocket video endpoint: `ws://localhost:9000/video`
- HTTP control API: `http://localhost:9000/api/*`

### Running the System

```bash
# Terminal 1: Start Android app with ADB forwarding
adb forward tcp:9000 tcp:9000
adb shell am start -n com.drone.controller/.MainActivity

# Terminal 2: Activate venv and run SLAM
cd /Users/denisemelyanov/Projects/HS-drone-hack
source venv/bin/activate
python vision/slam_main.py --mode autonomous --visualize
```

### Command Line Options

```bash
python vision/slam_main.py \
  --mode [tracking|mapping|autonomous] \
  --visualize \
  --camera-calibration vision/camera_calibration.json \
  --target-position 0,0,1.5 \
  --enable-loop-closure \
  --fps 15
```

---

## Development Phases

### Phase 1: Basic Visual Odometry (Week 1)
- ✅ WebSocket decoder
- ✅ ORB feature extraction
- ✅ Frame-to-frame matching
- ✅ Pose estimation
- ✅ Simple visualization

**Deliverable:** Display camera trajectory in 2D

### Phase 2: Mapping & Keyframes (Week 2)
- ✅ 3D point triangulation
- ✅ Keyframe selection
- ✅ Local map management
- ✅ 3D visualization with Pangolin

**Deliverable:** Build 3D map of environment

### Phase 3: Loop Closure & Optimization (Week 3)
- ✅ BoW descriptor database
- ✅ Loop detection
- ✅ Pose graph optimization (g2o)
- ✅ Map refinement

**Deliverable:** Drift-free trajectory on circular flight

### Phase 4: Autonomous Control (Week 4)
- ✅ PID controller implementation
- ✅ Target position tracking
- ✅ Waypoint navigation
- ✅ Safety features (battery check, bounds)

**Deliverable:** Hover at setpoint, navigate to waypoints

---

## Testing Strategy

### Test 1: Static Environment Mapping
**Setup:** Drone on table (not flying), move camera manually
**Expected:** Build consistent 3D map of room

### Test 2: Hover Stabilization
**Setup:** Takeoff to 1.5m, enable position hold
**Expected:** Maintain position within ±0.2m for 30 seconds

### Test 3: Waypoint Navigation
**Setup:** Define 4 waypoints in square pattern (1m sides)
**Expected:** Navigate to each waypoint with ±0.3m accuracy

### Test 4: Loop Closure
**Setup:** Fly circular pattern, return to start
**Expected:** Detect loop closure, optimize trajectory, reduce drift

---

## Known Limitations & Mitigations

### 1. Scale Ambiguity
**Problem:** Monocular SLAM cannot determine absolute scale
**Mitigation:** Initialize with known altitude or reference object

### 2. Low Texture Environments
**Problem:** ORB fails on blank walls, uniform surfaces
**Mitigation:** Fly in textured environments, add posters/patterns to walls

### 3. Motion Blur
**Problem:** Fast movements blur image, lose features
**Mitigation:** Limit maximum velocity in controller, increase exposure time

### 4. Lighting Changes
**Problem:** Brightness changes affect feature matching
**Mitigation:** Use adaptive histogram equalization (CLAHE) preprocessing

### 5. CPU Performance
**Problem:** Real-time SLAM is computationally intensive
**Mitigation:** Run in "Fast Tracking Mode", optimize critical paths, use C++ ORB-SLAM2 if needed

---

## Alternative Approaches

If full ORB-SLAM2 proves too complex or slow:

### Option A: Simplified Visual Odometry
- Frame-to-frame tracking only
- No loop closure or global optimization
- Faster but accumulates drift

### Option B: Hybrid ArUco + Optical Flow
- ArUco markers for absolute position
- Optical flow for smooth inter-marker tracking
- Simpler implementation, requires marker setup

### Option C: Pre-built Libraries
- Use `pyslam` (Python SLAM framework)
- Use `orbslam2_python` (Python bindings for C++ ORB-SLAM2)
- Faster development but less control

**Recommendation:** Start with simplified VO (Option A), add mapping/optimization later

---

## Future Enhancements

1. **Stereo Vision:** Add second camera for true scale and better depth
2. **IMU Fusion:** Integrate phone's IMU data (if accessible) for better pose estimation
3. **Deep Learning:** Use learned features (SuperPoint, SuperGlue) instead of ORB
4. **Semantic SLAM:** Detect objects (doors, furniture) for semantic mapping
5. **Multi-drone SLAM:** Collaborate with multiple drones for larger maps

---

## References

### Papers
- ORB-SLAM2: Mur-Artal & Tardós (2017)
- ORB Features: Rublee et al. (2011)
- Loop Closure: Gálvez-López & Tardós (2012) - DBoW2

### Libraries
- OpenCV: https://opencv.org/
- g2o: https://github.com/RainerKuemmerle/g2o
- Pangolin: https://github.com/stevenlovegrove/Pangolin
- PyAV: https://github.com/PyAV-Org/PyAV

### Tutorials
- Python Feature Detection: https://docs.opencv.org/4.x/dc/dc3/tutorial_py_matcher.html
- Visual Odometry: https://github.com/uoip/monoVO-python
- SLAM Fundamentals: https://www.youtube.com/watch?v=U6vr3iNrwRA

---

## Project Structure

```
HS-drone-hack/
├── vision/
│   ├── __init__.py
│   ├── stream_decoder.py      # WebSocket H.264 decoder
│   ├── orb_tracker.py          # ORB feature extraction & matching
│   ├── visual_odometry.py      # Pose estimation & mapping
│   ├── slam_controller.py      # PID control loop
│   ├── slam_visualizer.py      # 3D visualization
│   ├── slam_main.py            # Main application
│   ├── calibrate_camera.py     # Camera calibration tool
│   ├── camera_calibration.json # Calibration parameters
│   └── utils/
│       ├── geometry.py         # Matrix operations
│       ├── optimization.py     # g2o wrapper
│       └── pid.py              # PID controller class
├── requirements.txt
└── SLAM_VISION_PLAN.md (this file)
```

---

## Success Metrics

**Minimum Viable System:**
- ✅ Decode video stream at 15+ FPS
- ✅ Track camera pose with < 10% drift over 30 seconds
- ✅ Maintain hover position within ±0.3m
- ✅ Visualize trajectory and map in real-time

**Production Ready:**
- ✅ 25+ FPS processing
- ✅ < 5% drift with loop closure
- ✅ ±0.15m position accuracy
- ✅ Automatic recovery from tracking loss
- ✅ Safe battery landing on low power

---

## Getting Started

```bash
# 1. Install dependencies
cd /Users/denisemelyanov/Projects/HS-drone-hack
source venv/bin/activate
pip install -r requirements.txt

# 2. Calibrate camera (optional but recommended)
python vision/calibrate_camera.py

# 3. Test video decoder
python vision/stream_decoder.py --test

# 4. Run basic tracking
python vision/slam_main.py --mode tracking --visualize

# 5. Enable autonomous control
python vision/slam_main.py --mode autonomous --target 0,0,1.5
```

**Next Step:** Implement `vision/stream_decoder.py` and test WebSocket connection!

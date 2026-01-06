# Monocular Vision Navigation Enhancement Plan

## Overview

Implement bio-inspired vision-based navigation using only monocular optical flow. These proven techniques enable safe path finding without depth sensors, using methods from insect and bird navigation research.

## Current Implementation Status

**Existing Components:**
- ✅ Sparse Lucas-Kanade optical flow (FastObstacleDetector)
- ✅ Basic TTC estimation (simple distance/rate formula)
- ✅ Grid-based zone analysis (4×3 grid)
- ✅ Safe direction computation
- ⚠️ Simplified divergence (radial approximation)
- ❌ No Focus of Expansion (FOE) detection
- ❌ No flow balancing behaviors

**Performance:** 25-30 FPS @ 720p with sparse flow

---

## Implementation Phases

### Phase 1: Focus of Expansion (FOE) Detection
**Priority:** HIGH | **Effort:** 4-6 hours | **Impact:** ⭐⭐⭐⭐⭐

**Purpose:** Identify where the drone is heading by finding where optical flow vectors radiate from.

**Benefits:**
- Separates obstacles in flight path from those at sides
- Enables path-relative obstacle avoidance
- Foundation for advanced navigation behaviors

**Algorithm:**
```python
def estimate_foe(flow, method='weighted_average'):
    """
    Estimate Focus of Expansion from optical flow.
    Uses weighted average of flow vector origins.
    """
    # Weight each pixel by flow magnitude
    # Compute flow directions (angles)
    # Trace back flow vectors to intersection point
    # Return weighted average FOE position
```

**References:**
- Bruss & Horn (1983) "Passive navigation"
- Nelson & Aloimonos (1988) "Finding motion parameters from spherical motion fields"

---

### Phase 2: Tau-Based Time-to-Contact
**Priority:** HIGH | **Effort:** 2-4 hours | **Impact:** ⭐⭐⭐⭐⭐

**Purpose:** Replace simple TTC formula with Lee's tau theory (used by birds for landing).

**Key Insight:** τ = θ / (dθ/dt) where θ is visual angle. No distance measurement needed!

**Algorithm:**
```python
def compute_tau_ttc(prev_size, curr_size, dt):
    """
    Lee's tau computation for TTC estimation.
    
    Args:
        prev_size: Previous region size (pixels)
        curr_size: Current region size (pixels)
        dt: Time delta (seconds)
    
    Returns:
        tau: Time-to-contact (seconds)
    """
    if curr_size <= 0 or prev_size <= 0:
        return float('inf')
    
    rate = (curr_size - prev_size) / dt
    if abs(rate) < 1e-6:
        return float('inf')
    
    tau = curr_size / rate
    return abs(tau)
```

**References:**
- Lee, D.N. (1976) "A Theory of Visual Control of Braking"
- Regan & Vincent (1995) "Visual processing of looming and time to contact"

---

### Phase 3: Proper Divergence Field Computation
**Priority:** MEDIUM | **Effort:** 2-3 hours | **Impact:** ⭐⭐⭐⭐

**Purpose:** More accurate expansion detection using true divergence formula.

**Current Limitation:** Radial approximation using dot product with vector from center.

**Improvement:**
```python
def compute_flow_divergence(flow):
    """
    Compute proper divergence field from optical flow.
    
    Divergence: ∇·v = ∂vₓ/∂x + ∂vᵧ/∂y
    Curl: ∇×v = ∂vᵧ/∂x - ∂vₓ/∂y
    """
    # Spatial derivatives using numpy gradient
    dvx_dx = np.gradient(flow[..., 0], axis=1)
    dvy_dy = np.gradient(flow[..., 1], axis=0)
    divergence = dvx_dx + dvy_dy
    
    # Curl for rotation detection
    dvy_dx = np.gradient(flow[..., 1], axis=1)
    dvx_dy = np.gradient(flow[..., 0], axis=0)
    curl = dvy_dx - dvx_dy
    
    return divergence, curl
```

**Benefits:**
- Separates expansion from rotation
- More robust obstacle detection
- Helps with ego-motion compensation

**References:**
- Koenderink & van Doorn (1975) "Invariant properties of the motion parallax field"
- Barrows & Neely (2000) "Mixed-mode VLSI optic flow sensors"

---

### Phase 4: Flow Balancing (Bee Navigation)
**Priority:** HIGH | **Effort:** 2-3 hours | **Impact:** ⭐⭐⭐⭐

**Purpose:** Enable corridor centering and altitude control using lateral flow balance.

**Bio-Inspiration:** Bees maintain equal left/right optical flow to center themselves.

**Algorithm:**
```python
def compute_flow_balance(flow):
    """
    Compute flow balance for centering behavior.
    
    Returns:
        lateral_balance: -1 (go right) to +1 (go left)
        ventral_flow: Average bottom flow for altitude
    """
    h, w = flow.shape[:2]
    mag = np.sqrt(flow[..., 0]**2 + flow[..., 1]**2)
    
    # Lateral balance (left vs right)
    left_flow = np.mean(mag[:, :w//3])
    right_flow = np.mean(mag[:, 2*w//3:])
    lateral_balance = (left_flow - right_flow) / (left_flow + right_flow + 1e-6)
    
    # Ventral flow (bottom of frame)
    ventral_flow = np.mean(mag[2*h//3:, :])
    
    return lateral_balance, ventral_flow
```

**Use Cases:**
- Corridor centering (balance = 0 → centered)
- Obstacle avoidance (steer away from high flow side)
- Altitude hold (maintain constant ventral flow)

**References:**
- Srinivasan et al. (1996) "Honeybee navigation: nature and calibration of the 'odometer'"
- Franceschini et al. (2007) "Optic flow regulation: the key to aircraft automatic guidance"

---

### Phase 5: LGMD-Inspired Looming Detector
**Priority:** MEDIUM | **Effort:** 3-4 hours | **Impact:** ⭐⭐⭐

**Purpose:** Fast collision detection inspired by locust LGMD neurons.

**Key Insight:** Detects exponentially growing regions → imminent collision.

**Algorithm:**
```python
class LoomingDetector:
    """Locust LGMD-inspired collision detector."""
    
    def __init__(self, threshold_rate=0.05):
        self.prev_areas = {}
        self.threshold_rate = threshold_rate
        
    def detect_looming(self, regions, dt):
        """
        Detect rapidly expanding regions.
        
        Args:
            regions: Dict of region_id -> area (pixels)
            dt: Time delta (seconds)
        
        Returns:
            threats: List of {'region', 'expansion_rate', 'ttc'}
        """
        threats = []
        
        for region_id, area in regions.items():
            if region_id in self.prev_areas:
                prev_area = self.prev_areas[region_id]
                
                # Normalized expansion rate
                dA_dt = (area - prev_area) / dt
                norm_expansion = dA_dt / (prev_area + 1e-6)
                
                if norm_expansion > self.threshold_rate:
                    # Estimate TTC
                    theta = np.sqrt(area)
                    dtheta_dt = dA_dt / (2 * theta + 1e-6)
                    ttc = theta / (dtheta_dt + 1e-6)
                    
                    threats.append({
                        'region': region_id,
                        'expansion_rate': norm_expansion,
                        'ttc': ttc
                    })
            
            self.prev_areas[region_id] = area
        
        return threats
```

**Benefits:**
- Very fast (<1ms per frame)
- Complements optical flow
- Emergency collision detection

**References:**
- Rind & Simmons (1999) "Seeing what is coming: building collision-sensitive neurones"
- Gabbiani et al. (2002) "Multiplicative computation in a visual neuron sensitive to looming"

---

### Phase 6: Reactive Navigation Controller
**Priority:** HIGH | **Effort:** 4-6 hours | **Impact:** ⭐⭐⭐⭐⭐

**Purpose:** Integrate all components into cohesive navigation system.

**Architecture:**
```
Optical Flow → FOE → Heading Detection
             ↓
        Divergence → Obstacle Map
             ↓
         Tau TTC → Collision Timing
             ↓
       Balancing → Centering Commands
             ↓
        Looming → Emergency Stop
             ↓
    Reactive Controller → Flight Commands
```

**Control Strategy:**
1. **Heading Control:** Keep FOE centered (or at target direction)
2. **Lateral Control:** Balance left/right flow for centering
3. **Speed Control:** Reduce speed when expansion increases
4. **Collision Avoidance:** Emergency stop on looming threats
5. **Path Selection:** Choose direction with lowest divergence

**Output Commands:**
- Velocity setpoints (vx, vy, vz, yaw_rate)
- Safety flags (SAFE, CAUTION, DANGER, EMERGENCY)
- Recommended actions (CONTINUE, SLOW, STOP, AVOID_LEFT, etc.)

---

## Implementation Priority

### Immediate (Week 1):
1. ✅ FOE Detection → Heading awareness
2. ✅ Tau-based TTC → Accurate collision timing
3. ✅ Flow Balancing → Basic centering

### Short-term (Week 2):
4. ✅ Proper Divergence → Improved obstacle detection
5. ✅ LGMD Looming → Emergency detection
6. ✅ Reactive Controller → Integration

### Future Enhancements:
- Edge-based flow (3-5x faster on structured scenes)
- Template matching for maneuver recognition
- IMU fusion (if accessible via telemetry)
- Adaptive thresholds based on speed
- Multi-resolution flow pyramid

---

## Performance Targets

**Frame Rate:** Maintain >20 FPS @ 720p
**Latency:** <50ms end-to-end (perception → command)
**Accuracy:** <10% error in TTC estimation
**Reliability:** <1% false positive collision detections

**Resource Budget:**
- FOE: ~2ms per frame
- Divergence: ~1ms per frame
- Balancing: <0.5ms per frame
- Looming: <1ms per frame
- **Total overhead:** ~5ms (25% at 20 FPS)

---

## Testing Strategy

### Unit Tests:
- FOE estimation with synthetic radial flow
- Tau computation with known expansion rates
- Balancing with asymmetric flow patterns
- Looming detection with growing regions

### Integration Tests:
- Corridor navigation (should center automatically)
- Obstacle approach (should slow and stop)
- Moving camera (FOE should track heading)
- Static scene (should report no expansion)

### Real-World Validation:
1. **Stationary test:** Drone on ground, wave hand → detect looming
2. **Forward flight:** FOE should be at frame center
3. **Corridor flight:** Should maintain lateral balance
4. **Approach obstacle:** TTC should decrease linearly
5. **Emergency stop:** Looming trigger → immediate stop command

---

## Key Academic References

**Foundational Papers:**
- Lee (1976) - Tau theory for TTC
- Bruss & Horn (1983) - FOE estimation
- Koenderink & van Doorn (1975) - Divergence fields

**Bio-Inspired Navigation:**
- Srinivasan (2011) - Visual control of navigation in insects
- Franceschini et al. (2007) - Optic flow regulation
- Floreano & Zufferey (2010) - Fly-inspired visual steering

**Collision Detection:**
- Rind & Simmons (1999) - LGMD collision neurons
- Gabbiani et al. (2002) - Looming computation

**Robotics Implementations:**
- Barrows & Neely (2000) - Hardware optic flow sensors
- Ruffier & Franceschini (2005) - Reactive control

---

## Risk Mitigation

**Challenge:** FOE estimation unstable with small motion
**Solution:** Require minimum flow magnitude threshold

**Challenge:** Balancing fails in symmetric environments
**Solution:** Add texture/edge detection, fallback to forward motion

**Challenge:** Tau undefined for non-approaching objects
**Solution:** Check expansion sign, return ∞ for receding objects

**Challenge:** Performance degradation on low-end hardware
**Solution:** Adaptive processing (skip frames if needed)

**Challenge:** False looming detections from camera motion
**Solution:** Ego-motion compensation using FOE

---

## Success Criteria

**Phase 1-3 (Foundation):** Accurate heading and TTC estimation
- FOE within 10 pixels of true heading
- TTC error <15% for approaching obstacles
- Divergence correctly identifies expanding regions

**Phase 4-5 (Behaviors):** Reliable centering and collision detection
- Lateral error <5% of corridor width
- Looming triggers <0.5s before collision
- No false positives in normal flight

**Phase 6 (Integration):** Autonomous obstacle avoidance
- Successfully navigate 10m corridor without collision
- Automatic centering within 20cm
- Emergency stop from 2m/s within 1 meter

---

## Next Steps

1. Create `navigation/` module in python-vision
2. Implement FOE detector in `navigation/foe_detector.py`
3. Add tau TTC in `navigation/ttc_estimator.py`
4. Enhance `obstacle_detector_fast.py` with divergence
5. Create `navigation/flow_balancer.py`
6. Implement `navigation/looming_detector.py`
7. Build `navigation/reactive_controller.py`
8. Integrate into `vision_server.py`
9. Add visualization overlays
10. Test and validate

---

**Estimated Total Time:** 20-30 hours
**Expected Outcome:** Autonomous corridor navigation and obstacle avoidance using only monocular vision

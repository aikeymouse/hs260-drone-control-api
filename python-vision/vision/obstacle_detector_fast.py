#!/usr/bin/env python3
"""
Fast Sparse Optical Flow Obstacle Detection for HS260 Drone.

Uses Lucas-Kanade sparse optical flow for real-time performance.
Much faster than dense Farneback method.
"""

import cv2
import numpy as np


class FastObstacleDetector:
    """Fast obstacle detection using sparse optical flow."""
    
    def __init__(self, grid_size=(4, 3)):
        """
        Initialize obstacle detector.
        
        Args:
            grid_size: (cols, rows) - divide frame into grid for zone analysis
        """
        self.grid_size = grid_size
        self.prev_gray = None
        self.prev_points = None
        
        # Lucas-Kanade parameters (much faster than Farneback)
        self.lk_params = dict(
            winSize=(15, 15),
            maxLevel=2,
            criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 10, 0.03)
        )
        
        # Feature detection parameters
        self.feature_params = dict(
            maxCorners=150,
            qualityLevel=0.01,
            minDistance=10,
            blockSize=7
        )
        
        # Danger thresholds
        self.expansion_threshold = 1.5
        self.ttc_warning = 2.0
        self.ttc_danger = 1.0
        
    def analyze_frame(self, frame):
        """
        Analyze frame for obstacles using sparse optical flow.
        
        Args:
            frame: BGR image
            
        Returns:
            dict with: zones, safe_directions, warnings, flow_magnitude, points
        """
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape
        
        result = {
            'zones': [],
            'safe_directions': {'forward': True, 'left': True, 'right': True, 'up': True, 'down': True},
            'warnings': [],
            'danger_level': 0,
            'flow_magnitude': 0.0,
            'points': []
        }
        
        if self.prev_gray is None:
            self.prev_gray = gray
            # Detect initial features
            self.prev_points = cv2.goodFeaturesToTrack(gray, mask=None, **self.feature_params)
            return result
        
        # Calculate sparse optical flow
        if self.prev_points is not None and len(self.prev_points) > 0:
            next_points, status, err = cv2.calcOpticalFlowPyrLK(
                self.prev_gray, gray, self.prev_points, None, **self.lk_params
            )
            
            # Select good points
            if next_points is not None:
                good_new = next_points[status == 1]
                good_old = self.prev_points[status == 1]
                
                if len(good_new) > 0:
                    # Calculate flow vectors
                    flow_vectors = good_new - good_old
                    
                    # Calculate flow magnitude
                    magnitudes = np.linalg.norm(flow_vectors, axis=1)
                    result['flow_magnitude'] = float(np.mean(magnitudes))
                    
                    # Store points for visualization
                    result['points'] = [(new, old) for new, old in zip(good_new, good_old)]
                    
                    # Analyze grid zones with sparse flow
                    result['zones'] = self._analyze_zones_sparse(good_new, good_old, flow_vectors, w, h)
                    
                    # Update safe directions
                    self._update_safe_directions(result)
        
        # Re-detect features if too few
        if self.prev_points is None or len(self.prev_points) < 50:
            self.prev_points = cv2.goodFeaturesToTrack(gray, mask=None, **self.feature_params)
        else:
            # Update points for next frame
            if 'points' in result and len(result['points']) > 0:
                self.prev_points = np.array([new for new, old in result['points']], dtype=np.float32).reshape(-1, 1, 2)
            else:
                self.prev_points = cv2.goodFeaturesToTrack(gray, mask=None, **self.feature_params)
        
        # Update for next frame
        self.prev_gray = gray
        
        return result
    
    def _analyze_zones_sparse(self, new_points, old_points, flow_vectors, width, height):
        """Analyze zones using sparse flow vectors."""
        cols, rows = self.grid_size
        zone_width = width / cols
        zone_height = height / rows
        
        zones = []
        
        for row in range(rows):
            for col in range(cols):
                # Define zone boundaries
                x1 = int(col * zone_width)
                y1 = int(row * zone_height)
                x2 = int((col + 1) * zone_width)
                y2 = int((row + 1) * zone_height)
                
                # Find points in this zone
                zone_mask = (
                    (new_points[:, 0] >= x1) & (new_points[:, 0] < x2) &
                    (new_points[:, 1] >= y1) & (new_points[:, 1] < y2)
                )
                
                zone_flows = flow_vectors[zone_mask]
                
                zone_data = {
                    'row': row,
                    'col': col,
                    'bounds': (x1, y1, x2, y2),
                    'expansion': 0.0,
                    'ttc': float('inf'),
                    'status': 'clear',
                    'num_points': int(np.sum(zone_mask))
                }
                
                if len(zone_flows) > 3:  # Need at least 3 points
                    # Calculate divergence (expansion)
                    zone_points = new_points[zone_mask]
                    zone_center_x = (x1 + x2) / 2
                    zone_center_y = (y1 + y2) / 2
                    
                    # Vectors from center to points
                    to_center = np.array([zone_center_x - zone_points[:, 0],
                                         zone_center_y - zone_points[:, 1]]).T
                    
                    # Normalize
                    distances = np.linalg.norm(to_center, axis=1, keepdims=True)
                    distances[distances < 1] = 1  # Avoid division by zero
                    to_center_norm = to_center / distances
                    
                    # Dot product: positive = expanding (approaching)
                    divergence = np.sum(zone_flows * to_center_norm, axis=1)
                    expansion = float(np.mean(divergence))
                    
                    zone_data['expansion'] = expansion
                    
                    # Estimate time to collision
                    if expansion > 0.5:
                        # Rough TTC estimate
                        avg_distance = float(np.mean(distances))
                        ttc = avg_distance / (expansion * 30)  # 30 fps assumption
                        zone_data['ttc'] = ttc
                        
                        if ttc < self.ttc_danger:
                            zone_data['status'] = 'danger'
                        elif ttc < self.ttc_warning:
                            zone_data['status'] = 'warning'
                        elif expansion > self.expansion_threshold:
                            zone_data['status'] = 'caution'
                
                zones.append(zone_data)
        
        return zones
    
    def _update_safe_directions(self, result):
        """Update safe flight directions based on zone analysis."""
        cols, rows = self.grid_size
        
        # Reset all to safe
        result['safe_directions'] = {'forward': True, 'left': True, 'right': True, 
                                    'up': True, 'down': True}
        result['warnings'] = []
        result['danger_level'] = 0
        
        for zone in result['zones']:
            if zone['status'] in ['warning', 'danger']:
                row, col = zone['row'], zone['col']
                
                # Map zones to directions
                # Top rows = up, bottom rows = down
                if row == 0:
                    result['safe_directions']['up'] = False
                elif row == rows - 1:
                    result['safe_directions']['down'] = False
                
                # Left cols = left, right cols = right
                if col == 0:
                    result['safe_directions']['left'] = False
                elif col == cols - 1:
                    result['safe_directions']['right'] = False
                
                # Center zones = forward
                if col in range(1, cols - 1) and row in range(1, rows - 1):
                    result['safe_directions']['forward'] = False
                
                # Add warning
                if zone['status'] == 'danger':
                    result['danger_level'] = max(result['danger_level'], 3)
                    result['warnings'].append(f"DANGER: Zone [{row},{col}] TTC={zone['ttc']:.1f}s")
                else:
                    result['danger_level'] = max(result['danger_level'], 2)
                    result['warnings'].append(f"WARNING: Zone [{row},{col}] approaching")
    
    def draw_overlay(self, frame, result):
        """Draw obstacle detection overlay."""
        output = frame.copy()
        
        # Draw flow vectors (sparse points)
        if 'points' in result:
            for new, old in result['points']:
                a, b = new.ravel()
                c, d = old.ravel()
                a, b, c, d = int(a), int(b), int(c), int(d)
                
                # Draw line showing motion
                cv2.line(output, (c, d), (a, b), (0, 255, 0), 1)
                # Draw current point
                cv2.circle(output, (a, b), 3, (0, 255, 0), -1)
        
        # Draw zone grid
        for zone in result['zones']:
            x1, y1, x2, y2 = zone['bounds']
            
            # Color based on status
            if zone['status'] == 'danger':
                color = (0, 0, 255)  # Red
                thickness = 3
            elif zone['status'] == 'warning':
                color = (0, 165, 255)  # Orange
                thickness = 2
            elif zone['status'] == 'caution':
                color = (0, 255, 255)  # Yellow
                thickness = 2
            else:
                color = (0, 255, 0)  # Green
                thickness = 1
            
            cv2.rectangle(output, (x1, y1), (x2, y2), color, thickness)
            
            # Draw point count
            if zone['num_points'] > 0:
                text = f"{zone['num_points']}"
                cv2.putText(output, text, (x1 + 5, y1 + 20),
                           cv2.FONT_HERSHEY_SIMPLEX, 0.4, color, 1)
        
        # Draw safe direction arrows
        h, w = frame.shape[:2]
        arrow_len = 30
        safe = result['safe_directions']
        
        # Forward
        color = (0, 255, 0) if safe.get('forward', True) else (0, 0, 255)
        cv2.arrowedLine(output, (w//2, 50), (w//2, 50 + arrow_len), color, 3, tipLength=0.4)
        
        # Left
        color = (0, 255, 0) if safe.get('left', True) else (0, 0, 255)
        cv2.arrowedLine(output, (50, h//2), (50 - arrow_len, h//2), color, 3, tipLength=0.4)
        
        # Right
        color = (0, 255, 0) if safe.get('right', True) else (0, 0, 255)
        cv2.arrowedLine(output, (w - 50, h//2), (w - 50 + arrow_len, h//2), color, 3, tipLength=0.4)
        
        # Up
        color = (0, 255, 0) if safe.get('up', True) else (0, 0, 255)
        cv2.arrowedLine(output, (w//2, h - 50), (w//2, h - 50 - arrow_len), color, 3, tipLength=0.4)
        
        # Warnings
        if result['warnings']:
            y_offset = 200
            for warning in result['warnings'][:3]:  # Max 3 warnings
                cv2.putText(output, warning, (10, y_offset),
                           cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1)
                y_offset += 25
        
        return output
    
    def get_flight_recommendation(self, result):
        """Get flight command recommendation."""
        if result['danger_level'] >= 3:
            return 'STOP'
        
        safe = result['safe_directions']
        
        if not safe.get('forward', True):
            # Blocked forward - suggest alternative
            if safe.get('up', True):
                return 'UP'
            elif safe.get('left', True):
                return 'LEFT'
            elif safe.get('right', True):
                return 'RIGHT'
            else:
                return 'STOP'
        
        return 'OK'

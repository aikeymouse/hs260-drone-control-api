#!/usr/bin/env python3
"""
Optical Flow Obstacle Detection for HS260 Drone.

Uses dense optical flow to detect approaching obstacles and estimate time-to-collision.
Works with monocular camera only - no depth sensor needed.
"""

import cv2
import numpy as np


class ObstacleDetector:
    """Detect obstacles using optical flow analysis."""
    
    def __init__(self, grid_size=(4, 3)):
        """
        Initialize obstacle detector.
        
        Args:
            grid_size: (cols, rows) - divide frame into grid for zone analysis
        """
        self.grid_size = grid_size
        self.prev_gray = None
        
        # Optical flow parameters
        self.flow_params = {
            'pyr_scale': 0.5,
            'levels': 3,
            'winsize': 15,
            'iterations': 3,
            'poly_n': 5,
            'poly_sigma': 1.2,
            'flags': 0
        }
        
        # Danger thresholds
        self.expansion_threshold = 2.0  # Pixels/frame - object expanding (approaching)
        self.ttc_warning = 2.0          # Seconds - time to collision warning
        self.ttc_danger = 1.0           # Seconds - immediate danger
        
        # Zone safety status
        self.zones = None
        self.safe_directions = {'forward': True, 'left': True, 'right': True, 
                               'up': True, 'down': True}
        
    def analyze_frame(self, frame):
        """
        Analyze frame for obstacles using optical flow.
        
        Args:
            frame: BGR image
            
        Returns:
            dict with: zones, safe_directions, warnings, flow_viz
        """
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape
        
        result = {
            'zones': None,
            'safe_directions': self.safe_directions.copy(),
            'warnings': [],
            'danger_level': 0,  # 0=safe, 1=caution, 2=danger
            'flow_magnitude': 0.0
        }
        
        # Need previous frame for optical flow
        if self.prev_gray is None:
            self.prev_gray = gray
            return result
        
        # Calculate dense optical flow
        flow = cv2.calcOpticalFlowFarneback(
            self.prev_gray, gray, None, **self.flow_params
        )
        
        # Analyze flow in grid zones
        zones = self._analyze_zones(flow, w, h)
        result['zones'] = zones
        
        # Calculate average flow magnitude
        mag, ang = cv2.cartToPolar(flow[..., 0], flow[..., 1])
        avg_flow = np.mean(mag)
        result['flow_magnitude'] = avg_flow
        
        # Detect expansion (approaching obstacles)
        danger_zones = []
        for zone_info in zones:
            if zone_info['expanding'] and zone_info['expansion_rate'] > self.expansion_threshold:
                danger_zones.append(zone_info)
                
                # Estimate time to collision
                if zone_info['expansion_rate'] > 0:
                    # Simple TTC estimate (very rough)
                    ttc = zone_info['avg_size'] / (zone_info['expansion_rate'] * 30)  # assume 30fps
                    zone_info['ttc'] = ttc
                    
                    if ttc < self.ttc_danger:
                        result['warnings'].append(f"DANGER: {zone_info['position']} - TTC {ttc:.1f}s")
                        result['danger_level'] = max(result['danger_level'], 2)
                    elif ttc < self.ttc_warning:
                        result['warnings'].append(f"Caution: {zone_info['position']} - TTC {ttc:.1f}s")
                        result['danger_level'] = max(result['danger_level'], 1)
        
        # Update safe directions
        result['safe_directions'] = self._update_safe_directions(zones)
        
        self.prev_gray = gray
        return result
    
    def _analyze_zones(self, flow, width, height):
        """Analyze optical flow in grid zones."""
        cols, rows = self.grid_size
        zone_w = width // cols
        zone_h = height // rows
        
        zones = []
        
        for row in range(rows):
            for col in range(cols):
                # Extract zone
                x1, x2 = col * zone_w, (col + 1) * zone_w
                y1, y2 = row * zone_h, (row + 1) * zone_h
                zone_flow = flow[y1:y2, x1:x2]
                
                # Analyze flow in this zone
                fx = zone_flow[..., 0]
                fy = zone_flow[..., 1]
                
                mag, ang = cv2.cartToPolar(fx, fy)
                avg_mag = np.mean(mag)
                
                # Detect expansion pattern (diverging flow = approaching)
                center_x, center_y = (x1 + x2) // 2, (y1 + y2) // 2
                
                # Calculate divergence (simplified)
                # Positive divergence = expansion = approaching obstacle
                div = self._calculate_divergence(fx, fy)
                
                # Zone position label
                if row == 0:
                    v_pos = "top"
                elif row == rows - 1:
                    v_pos = "bottom"
                else:
                    v_pos = "middle"
                
                if col == 0:
                    h_pos = "left"
                elif col == cols - 1:
                    h_pos = "right"
                else:
                    h_pos = "center"
                
                position = f"{v_pos}-{h_pos}"
                
                zone_info = {
                    'row': row,
                    'col': col,
                    'position': position,
                    'bounds': (x1, y1, x2, y2),
                    'avg_magnitude': avg_mag,
                    'divergence': div,
                    'expanding': div > 0.5,  # Positive divergence threshold
                    'expansion_rate': div if div > 0 else 0,
                    'avg_size': min(zone_w, zone_h)
                }
                
                zones.append(zone_info)
        
        return zones
    
    def _calculate_divergence(self, fx, fy):
        """Calculate divergence of flow field (simplified)."""
        # Divergence = d(fx)/dx + d(fy)/dy
        # Simplified: just check if flow is outward from center
        
        h, w = fx.shape
        center_x, center_y = w // 2, h // 2
        
        # Sample points around center
        divergence = 0
        samples = 0
        
        for y in range(0, h, max(1, h // 5)):
            for x in range(0, w, max(1, w // 5)):
                # Vector from center to point
                dx = x - center_x
                dy = y - center_y
                
                # Flow at this point
                flow_x = fx[y, x]
                flow_y = fy[y, x]
                
                # Dot product: positive if flow is away from center (expansion)
                if dx != 0 or dy != 0:
                    norm = np.sqrt(dx*dx + dy*dy)
                    dot = (dx * flow_x + dy * flow_y) / norm
                    divergence += dot
                    samples += 1
        
        return divergence / max(1, samples) if samples > 0 else 0
    
    def _update_safe_directions(self, zones):
        """Determine which directions are safe based on zone analysis."""
        safe = {
            'forward': True,
            'left': True,
            'right': True,
            'up': True,
            'down': True
        }
        
        # Map zones to directions
        cols, rows = self.grid_size
        
        for zone in zones:
            if zone['expanding'] and zone['expansion_rate'] > self.expansion_threshold:
                row, col = zone['row'], zone['col']
                
                # Top zones = up direction
                if row == 0:
                    safe['up'] = False
                
                # Bottom zones = down direction
                if row == rows - 1:
                    safe['down'] = False
                
                # Left zones
                if col == 0:
                    safe['left'] = False
                
                # Right zones
                if col == cols - 1:
                    safe['right'] = False
                
                # Center zones = forward
                if col == cols // 2 and row == rows // 2:
                    safe['forward'] = False
        
        return safe
    
    def draw_overlay(self, frame, result):
        """
        Draw obstacle detection overlay on frame.
        
        Args:
            frame: BGR image
            result: Result from analyze_frame()
        """
        output = frame.copy()
        h, w = frame.shape[:2]
        
        # Draw zone grid with danger levels
        if result['zones']:
            for zone in result['zones']:
                x1, y1, x2, y2 = zone['bounds']
                
                # Color based on danger
                if zone['expanding'] and zone['expansion_rate'] > self.expansion_threshold:
                    if zone.get('ttc', 999) < self.ttc_danger:
                        color = (0, 0, 255)  # Red - danger
                        thickness = 3
                    elif zone.get('ttc', 999) < self.ttc_warning:
                        color = (0, 165, 255)  # Orange - caution
                        thickness = 2
                    else:
                        color = (0, 255, 255)  # Yellow - warning
                        thickness = 1
                    
                    cv2.rectangle(output, (x1, y1), (x2, y2), color, thickness)
                    
                    # Show expansion rate
                    text = f"{zone['expansion_rate']:.1f}"
                    cv2.putText(output, text, (x1 + 5, y1 + 20),
                               cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 2)
                else:
                    # Safe zone - subtle grid
                    cv2.rectangle(output, (x1, y1), (x2, y2), (0, 100, 0), 1)
        
        # Draw safe direction indicators
        center_x, center_y = w // 2, h // 2
        indicator_size = 40
        
        directions = {
            'up': (center_x, 50, 0, -indicator_size),
            'down': (center_x, h - 50, 0, indicator_size),
            'left': (50, center_y, -indicator_size, 0),
            'right': (w - 50, center_y, indicator_size, 0),
            'forward': (center_x, center_y, 0, 0)
        }
        
        for direction, (x, y, dx, dy) in directions.items():
            safe = result['safe_directions'].get(direction, True)
            color = (0, 255, 0) if safe else (0, 0, 255)
            
            if direction != 'forward':
                # Draw arrow
                end_x, end_y = x + dx, y + dy
                cv2.arrowedLine(output, (x, y), (end_x, end_y), color, 3, tipLength=0.3)
            else:
                # Draw circle for forward
                cv2.circle(output, (x, y), 20, color, 3)
        
        # Draw warnings at top
        y_offset = 30
        for i, warning in enumerate(result['warnings'][:3]):  # Max 3 warnings
            color = (0, 0, 255) if 'DANGER' in warning else (0, 165, 255)
            cv2.putText(output, warning, (w - 400, y_offset + i * 30),
                       cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)
        
        # Draw status indicator
        danger_level = result['danger_level']
        if danger_level == 0:
            status = "CLEAR"
            status_color = (0, 255, 0)
        elif danger_level == 1:
            status = "CAUTION"
            status_color = (0, 165, 255)
        else:
            status = "DANGER"
            status_color = (0, 0, 255)
        
        cv2.putText(output, f"Obstacle: {status}", (w - 200, h - 20),
                   cv2.FONT_HERSHEY_SIMPLEX, 0.7, status_color, 2)
        
        return output
    
    def get_flight_recommendation(self, result):
        """
        Get flight command recommendation based on obstacle detection.
        
        Returns:
            str: Recommended action ('STOP', 'LEFT', 'RIGHT', 'UP', 'DOWN', 'OK')
        """
        if result['danger_level'] == 2:
            return 'STOP'
        
        if not result['safe_directions']['forward']:
            # Forward blocked - try alternatives
            if result['safe_directions']['left']:
                return 'LEFT'
            elif result['safe_directions']['right']:
                return 'RIGHT'
            elif result['safe_directions']['up']:
                return 'UP'
            elif result['safe_directions']['down']:
                return 'DOWN'
            else:
                return 'STOP'
        
        return 'OK'

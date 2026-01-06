#!/usr/bin/env python3
"""
Focus of Expansion (FOE) Detector

Estimates the point in the image where optical flow vectors radiate from,
indicating the direction of camera motion (heading).

Based on:
- Bruss & Horn (1983) "Passive navigation"
- Nelson & Aloimonos (1988) "Finding motion parameters from spherical motion fields"
"""

import cv2
import numpy as np


class FOEDetector:
    """Detect Focus of Expansion from optical flow field."""
    
    def __init__(self, min_flow_magnitude=1.0, confidence_threshold=0.3):
        """
        Initialize FOE detector.
        
        Args:
            min_flow_magnitude: Minimum flow magnitude to consider (pixels)
            confidence_threshold: Minimum confidence for valid FOE (0-1)
        """
        self.min_flow_magnitude = min_flow_magnitude
        self.confidence_threshold = confidence_threshold
        self.foe = None
        self.confidence = 0.0
        
    def estimate_foe(self, flow, method='weighted_average'):
        """
        Estimate Focus of Expansion from optical flow.
        
        Args:
            flow: Optical flow field (H, W, 2)
            method: 'weighted_average' or 'least_squares'
        
        Returns:
            foe: (x, y) tuple or None if estimation failed
            confidence: 0-1 confidence score
        """
        if flow is None or flow.size == 0:
            return None, 0.0
        
        h, w = flow.shape[:2]
        
        # Compute flow magnitude
        flow_x = flow[..., 0]
        flow_y = flow[..., 1]
        magnitude = np.sqrt(flow_x**2 + flow_y**2)
        
        # Filter by minimum magnitude
        mask = magnitude > self.min_flow_magnitude
        
        if np.sum(mask) < 10:  # Need at least 10 valid points
            return None, 0.0
        
        if method == 'weighted_average':
            foe, confidence = self._weighted_average_foe(flow_x, flow_y, magnitude, mask, w, h)
        elif method == 'least_squares':
            foe, confidence = self._least_squares_foe(flow_x, flow_y, magnitude, mask, w, h)
        else:
            raise ValueError(f"Unknown method: {method}")
        
        self.foe = foe
        self.confidence = confidence
        
        return foe, confidence
    
    def _weighted_average_foe(self, flow_x, flow_y, magnitude, mask, w, h):
        """
        Estimate FOE using weighted average of flow vector origins.
        
        Each flow vector (at position p) with direction v points away from FOE.
        We trace back along the negative flow direction to find candidate FOE positions,
        then compute weighted average.
        """
        # Create coordinate grids
        yy, xx = np.mgrid[0:h, 0:w]
        
        # Get valid flow vectors
        valid_x = xx[mask]
        valid_y = yy[mask]
        valid_flow_x = flow_x[mask]
        valid_flow_y = flow_y[mask]
        valid_mag = magnitude[mask]
        
        # Normalize flow directions
        flow_dx = valid_flow_x / (valid_mag + 1e-6)
        flow_dy = valid_flow_y / (valid_mag + 1e-6)
        
        # For expanding flow (moving forward), flow vectors point outward from FOE
        # So we trace back along negative flow direction
        # Distance to trace back: use a heuristic based on frame size
        trace_distance = min(w, h) / 2
        
        foe_x_candidates = valid_x - flow_dx * trace_distance
        foe_y_candidates = valid_y - flow_dy * trace_distance
        
        # Weight by magnitude (stronger flow = more reliable)
        weights = valid_mag / (np.sum(valid_mag) + 1e-6)
        
        # Compute weighted average
        foe_x = np.sum(foe_x_candidates * weights)
        foe_y = np.sum(foe_y_candidates * weights)
        
        # Confidence based on agreement (low variance = high confidence)
        variance_x = np.sum(weights * (foe_x_candidates - foe_x)**2)
        variance_y = np.sum(weights * (foe_y_candidates - foe_y)**2)
        variance = np.sqrt(variance_x + variance_y)
        
        # Normalize variance to confidence (0-1)
        # Low variance = high confidence
        max_expected_variance = min(w, h) / 2
        confidence = max(0.0, 1.0 - variance / max_expected_variance)
        
        # Check if FOE is within reasonable bounds (allow some outside frame)
        margin = 0.3  # 30% margin outside frame
        if (foe_x < -w*margin or foe_x > w*(1+margin) or 
            foe_y < -h*margin or foe_y > h*(1+margin)):
            confidence *= 0.5  # Reduce confidence for out-of-frame FOE
        
        foe = (int(foe_x), int(foe_y)) if confidence > self.confidence_threshold else None
        
        return foe, confidence
    
    def _least_squares_foe(self, flow_x, flow_y, magnitude, mask, w, h):
        """
        Estimate FOE using least squares intersection of flow lines.
        
        Each flow vector defines a line: p + t*v
        FOE is at intersection of all lines (minimize perpendicular distance).
        """
        # Create coordinate grids
        yy, xx = np.mgrid[0:h, 0:w]
        
        # Get valid flow vectors
        valid_x = xx[mask].astype(np.float32)
        valid_y = yy[mask].astype(np.float32)
        valid_flow_x = flow_x[mask]
        valid_flow_y = flow_y[mask]
        valid_mag = magnitude[mask]
        
        # Normalize directions
        dx = valid_flow_x / (valid_mag + 1e-6)
        dy = valid_flow_y / (valid_mag + 1e-6)
        
        # Line equation: (y - y0) * dx = (x - x0) * dy
        # Rearrange: dx*y - dy*x = dx*y0 - dy*x0
        # A*foe = b where A = [dx, -dy] and b = dx*y0 - dy*x0
        
        # Build least squares system
        A = np.stack([dx, -dy], axis=1)
        b = dx * valid_y - dy * valid_x
        
        # Weight by magnitude
        W = np.diag(valid_mag / (np.sum(valid_mag) + 1e-6))
        
        # Solve: A^T W A foe = A^T W b
        AtWA = A.T @ W @ A
        AtWb = A.T @ W @ b
        
        try:
            foe_xy = np.linalg.solve(AtWA, AtWb)
            foe_x, foe_y = foe_xy[0], foe_xy[1]
        except np.linalg.LinAlgError:
            return None, 0.0
        
        # Compute residuals for confidence
        residuals = np.abs(A @ foe_xy - b)
        mean_residual = np.mean(residuals)
        
        # Normalize to confidence
        max_expected_residual = min(w, h) / 4
        confidence = max(0.0, 1.0 - mean_residual / max_expected_residual)
        
        # Check bounds
        margin = 0.3
        if (foe_x < -w*margin or foe_x > w*(1+margin) or 
            foe_y < -h*margin or foe_y > h*(1+margin)):
            confidence *= 0.5
        
        foe = (int(foe_x), int(foe_y)) if confidence > self.confidence_threshold else None
        
        return foe, confidence
    
    def get_heading_offset(self, frame_shape):
        """
        Get heading offset from frame center.
        
        Args:
            frame_shape: (height, width)
        
        Returns:
            offset: (dx, dy) offset from center, or None if no FOE
            normalized_offset: (-1 to 1, -1 to 1) normalized offset
        """
        if self.foe is None:
            return None, None
        
        h, w = frame_shape[:2]
        center_x, center_y = w // 2, h // 2
        
        offset_x = self.foe[0] - center_x
        offset_y = self.foe[1] - center_y
        
        # Normalize by frame dimensions
        norm_x = offset_x / (w / 2)
        norm_y = offset_y / (h / 2)
        
        return (offset_x, offset_y), (norm_x, norm_y)
    
    def draw_foe(self, frame, color=(0, 255, 255), radius=15):
        """
        Draw FOE on frame.
        
        Args:
            frame: Image to draw on
            color: BGR color tuple
            radius: Circle radius
        
        Returns:
            frame: Image with FOE drawn
        """
        if self.foe is None:
            return frame
        
        output = frame.copy()
        
        # Draw crosshair at FOE
        cv2.circle(output, self.foe, radius, color, 2)
        cv2.circle(output, self.foe, radius//3, color, -1)
        
        # Draw lines
        cv2.line(output, (self.foe[0] - radius*2, self.foe[1]), 
                (self.foe[0] + radius*2, self.foe[1]), color, 2)
        cv2.line(output, (self.foe[0], self.foe[1] - radius*2), 
                (self.foe[0], self.foe[1] + radius*2), color, 2)
        
        # Draw label
        label = f"FOE ({self.confidence:.2f})"
        cv2.putText(output, label, (self.foe[0] + radius + 5, self.foe[1] - 5),
                   cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1)
        
        return output

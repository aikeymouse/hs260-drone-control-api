#!/usr/bin/env python3
"""
Tau-Based Time-to-Contact Estimator

Implements Lee's tau theory for estimating time-to-contact without
requiring distance measurements. Used by birds for landing.

Based on:
- Lee, D.N. (1976) "A Theory of Visual Control of Braking"
- Regan & Vincent (1995) "Visual processing of looming and time to contact"
"""

import numpy as np


class TauTTCEstimator:
    """Estimate time-to-contact using tau theory."""
    
    def __init__(self, min_size=10, min_rate=0.1):
        """
        Initialize tau estimator.
        
        Args:
            min_size: Minimum region size to track (pixels)
            min_rate: Minimum expansion rate to consider (pixels/frame)
        """
        self.min_size = min_size
        self.min_rate = min_rate
        self.region_history = {}  # region_id -> [sizes, times]
        
    def compute_tau(self, region_id, current_size, current_time):
        """
        Compute tau (time-to-contact) for a region.
        
        Lee's tau: τ = θ / (dθ/dt)
        where θ is visual angle (approximated by size)
        
        Args:
            region_id: Unique identifier for region
            current_size: Current size in pixels (area or diameter)
            current_time: Current timestamp (seconds)
        
        Returns:
            tau: Time-to-contact (seconds), or None if not computable
            tau_dot: Rate of change of tau (for constant tau-dot strategy)
            expansion_rate: dθ/dt (pixels per second)
        """
        if current_size < self.min_size:
            return None, None, None
        
        # Initialize history if new region
        if region_id not in self.region_history:
            self.region_history[region_id] = {
                'sizes': [current_size],
                'times': [current_time]
            }
            return None, None, None
        
        history = self.region_history[region_id]
        
        # Add current measurement
        history['sizes'].append(current_size)
        history['times'].append(current_time)
        
        # Keep only recent history (last 10 frames)
        if len(history['sizes']) > 10:
            history['sizes'] = history['sizes'][-10:]
            history['times'] = history['times'][-10:]
        
        # Need at least 2 measurements
        if len(history['sizes']) < 2:
            return None, None, None
        
        # Compute expansion rate using linear regression (more robust)
        sizes = np.array(history['sizes'])
        times = np.array(history['times'])
        
        # Simple finite difference for recent rate
        dt = times[-1] - times[-2]
        if dt < 1e-6:
            return None, None, None
        
        expansion_rate = (sizes[-1] - sizes[-2]) / dt
        
        # Check if expansion is significant
        if abs(expansion_rate) < self.min_rate:
            return None, None, None
        
        # Compute tau
        tau = current_size / expansion_rate
        
        # Only return positive tau (approaching)
        if tau < 0:
            return None, None, expansion_rate
        
        # Compute tau_dot if we have enough history
        tau_dot = None
        if len(history['sizes']) >= 3:
            # Previous tau
            prev_size = sizes[-2]
            prev_rate = (sizes[-2] - sizes[-3]) / (times[-2] - times[-3])
            if abs(prev_rate) >= self.min_rate:
                prev_tau = prev_size / prev_rate
                if prev_tau > 0:
                    tau_dot = (tau - prev_tau) / dt
        
        return tau, tau_dot, expansion_rate
    
    def compute_tau_simple(self, prev_size, curr_size, dt):
        """
        Simple tau computation without history (for single use).
        
        Args:
            prev_size: Previous size (pixels)
            curr_size: Current size (pixels)
            dt: Time delta (seconds)
        
        Returns:
            tau: Time-to-contact (seconds), or None
        """
        if curr_size < self.min_size or prev_size < self.min_size:
            return None
        
        if dt < 1e-6:
            return None
        
        expansion_rate = (curr_size - prev_size) / dt
        
        if abs(expansion_rate) < self.min_rate:
            return None
        
        tau = curr_size / expansion_rate
        
        return tau if tau > 0 else None
    
    def get_danger_level(self, tau, tau_dot=None):
        """
        Classify danger level based on tau.
        
        Args:
            tau: Time-to-contact (seconds)
            tau_dot: Rate of change of tau (optional)
        
        Returns:
            level: 'SAFE', 'CAUTION', 'WARNING', 'DANGER'
            urgency: 0-1 urgency score
        """
        if tau is None or tau <= 0:
            return 'SAFE', 0.0
        
        # Thresholds based on reaction time and stopping distance
        DANGER_THRESHOLD = 0.5    # 0.5s = immediate danger
        WARNING_THRESHOLD = 1.0   # 1.0s = need to act now
        CAUTION_THRESHOLD = 2.0   # 2.0s = start slowing down
        
        if tau < DANGER_THRESHOLD:
            level = 'DANGER'
            urgency = 1.0
        elif tau < WARNING_THRESHOLD:
            level = 'WARNING'
            urgency = 0.7
        elif tau < CAUTION_THRESHOLD:
            level = 'CAUTION'
            urgency = 0.4
        else:
            level = 'SAFE'
            urgency = max(0.0, 1.0 - tau / 10.0)  # Gradually decrease
        
        # Adjust urgency based on tau_dot if available
        if tau_dot is not None:
            # Negative tau_dot means tau is decreasing (accelerating approach)
            if tau_dot < -0.1:
                urgency = min(1.0, urgency * 1.5)
            # Positive tau_dot means tau is increasing (decelerating approach)
            elif tau_dot > 0.1:
                urgency = max(0.0, urgency * 0.7)
        
        return level, urgency
    
    def clear_region(self, region_id):
        """Remove region from history."""
        if region_id in self.region_history:
            del self.region_history[region_id]
    
    def clear_old_regions(self, current_time, max_age=2.0):
        """
        Remove regions that haven't been updated recently.
        
        Args:
            current_time: Current timestamp
            max_age: Maximum age to keep (seconds)
        """
        to_remove = []
        for region_id, history in self.region_history.items():
            if len(history['times']) > 0:
                age = current_time - history['times'][-1]
                if age > max_age:
                    to_remove.append(region_id)
        
        for region_id in to_remove:
            del self.region_history[region_id]
    
    def estimate_from_expansion_field(self, divergence_map, flow_magnitude_map, dt):
        """
        Estimate TTC from divergence field.
        
        For each region, compute average divergence and use it to estimate tau.
        
        Args:
            divergence_map: 2D array of divergence values
            flow_magnitude_map: 2D array of flow magnitudes
            dt: Time delta (seconds)
        
        Returns:
            tau_map: 2D array of tau values (inf where not computable)
        """
        # Avoid division by zero
        safe_div = divergence_map.copy()
        safe_div[np.abs(safe_div) < 0.01] = 0.01
        
        # Tau relates to rate of expansion
        # For uniform expansion: div(v) = k, where k is expansion rate
        # tau ≈ 1 / divergence (for normalized flow)
        
        tau_map = np.full_like(divergence_map, np.inf, dtype=np.float32)
        
        # Only compute where divergence is positive (expansion)
        expanding = divergence_map > 0.01
        if np.any(expanding):
            # Simple approximation: tau ~ 1/divergence
            # Adjust by flow magnitude for better estimate
            tau_map[expanding] = 1.0 / (divergence_map[expanding] + 1e-6)
            
            # Scale by typical flow magnitude (heuristic)
            avg_magnitude = np.mean(flow_magnitude_map[expanding])
            if avg_magnitude > 1.0:
                tau_map[expanding] *= (10.0 / avg_magnitude)
        
        return tau_map

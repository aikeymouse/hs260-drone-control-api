#!/usr/bin/env python3
"""
Flow Balancing for Corridor Navigation

Implements bee-inspired lateral balance control for centering in corridors
and obstacle avoidance. Much simpler and more stable than FOE detection.

Based on:
- Srinivasan et al. (1996) "Honeybee navigation: nature and calibration of the 'odometer'"
- Franceschini et al. (2007) "Optic flow regulation: the key to aircraft automatic guidance"
"""

import numpy as np


class FlowBalancer:
    """Bee-inspired flow balancing for navigation."""
    
    def __init__(self, balance_threshold=0.3, speed_target=5.0):
        """
        Initialize flow balancer.
        
        Args:
            balance_threshold: Acceptable imbalance (0-1)
            speed_target: Target ventral flow for speed control (pixels/frame)
        """
        self.balance_threshold = balance_threshold
        self.speed_target = speed_target
        
    def compute_balance(self, flow_magnitude_map):
        """
        Compute lateral and ventral flow balance.
        
        Args:
            flow_magnitude_map: 2D array of flow magnitudes
        
        Returns:
            dict with lateral_balance, ventral_flow, recommendations
        """
        if flow_magnitude_map is None or flow_magnitude_map.size == 0:
            return self._empty_result()
        
        h, w = flow_magnitude_map.shape
        
        # Lateral balance (left vs right)
        left_region = flow_magnitude_map[:, :w//3]
        right_region = flow_magnitude_map[:, 2*w//3:]
        
        left_flow = np.mean(left_region)
        right_flow = np.mean(right_region)
        
        # Normalized balance: -1 (go right) to +1 (go left)
        total_flow = left_flow + right_flow
        if total_flow > 0.1:
            lateral_balance = (left_flow - right_flow) / total_flow
        else:
            lateral_balance = 0.0
        
        # Ventral flow (bottom third)
        ventral_region = flow_magnitude_map[2*h//3:, :]
        ventral_flow = np.mean(ventral_region)
        
        # Top flow (top third)
        dorsal_region = flow_magnitude_map[:h//3, :]
        dorsal_flow = np.mean(dorsal_region)
        
        # Vertical balance
        total_vertical = ventral_flow + dorsal_flow
        if total_vertical > 0.1:
            vertical_balance = (dorsal_flow - ventral_flow) / total_vertical
        else:
            vertical_balance = 0.0
        
        # Generate recommendations
        recommendations = self._generate_recommendations(
            lateral_balance, ventral_flow, vertical_balance
        )
        
        return {
            'lateral_balance': lateral_balance,
            'ventral_flow': ventral_flow,
            'dorsal_flow': dorsal_flow,
            'vertical_balance': vertical_balance,
            'left_flow': left_flow,
            'right_flow': right_flow,
            'recommendations': recommendations
        }
    
    def _generate_recommendations(self, lateral_balance, ventral_flow, vertical_balance):
        """Generate navigation recommendations from balance."""
        recs = {
            'lateral': 'CENTERED',
            'speed': 'OK',
            'vertical': 'LEVEL',
            'action': 'CONTINUE'
        }
        
        # Lateral correction
        if lateral_balance > self.balance_threshold:
            recs['lateral'] = 'GO_RIGHT'
            recs['action'] = 'ADJUST_RIGHT'
        elif lateral_balance < -self.balance_threshold:
            recs['lateral'] = 'GO_LEFT'
            recs['action'] = 'ADJUST_LEFT'
        
        # Speed control based on ventral flow
        if ventral_flow > self.speed_target * 1.5:
            recs['speed'] = 'TOO_FAST'
            if recs['action'] == 'CONTINUE':
                recs['action'] = 'SLOW_DOWN'
        elif ventral_flow > self.speed_target * 1.2:
            recs['speed'] = 'REDUCE_SPEED'
        elif ventral_flow < self.speed_target * 0.5 and ventral_flow > 0.5:
            recs['speed'] = 'TOO_SLOW'
        
        # Vertical correction
        if vertical_balance > 0.3:
            recs['vertical'] = 'GO_DOWN'
        elif vertical_balance < -0.3:
            recs['vertical'] = 'GO_UP'
        
        return recs
    
    def _empty_result(self):
        """Return empty result when no flow available."""
        return {
            'lateral_balance': 0.0,
            'ventral_flow': 0.0,
            'dorsal_flow': 0.0,
            'vertical_balance': 0.0,
            'left_flow': 0.0,
            'right_flow': 0.0,
            'recommendations': {
                'lateral': 'UNKNOWN',
                'speed': 'UNKNOWN',
                'vertical': 'UNKNOWN',
                'action': 'WAIT'
            }
        }
    
    def get_control_vector(self, balance_result, gain=1.0):
        """
        Convert balance to control vector.
        
        Args:
            balance_result: Result from compute_balance()
            gain: Control gain (0-1)
        
        Returns:
            (lateral_cmd, speed_cmd, vertical_cmd) in range -1 to 1
        """
        lateral_cmd = -balance_result['lateral_balance'] * gain
        
        # Speed command based on ventral flow
        speed_error = (balance_result['ventral_flow'] - self.speed_target) / self.speed_target
        speed_cmd = -np.clip(speed_error, -1.0, 1.0) * gain
        
        # Vertical command
        vertical_cmd = -balance_result['vertical_balance'] * gain
        
        return (
            np.clip(lateral_cmd, -1.0, 1.0),
            np.clip(speed_cmd, -1.0, 1.0),
            np.clip(vertical_cmd, -1.0, 1.0)
        )

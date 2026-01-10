#!/usr/bin/env python3
"""
Autopilot Controller for HS260 Drone

Safe autonomous navigation using vision-based optical flow balancing.
Includes command smoothing, safety checks, and manual confirmation mode.
"""

import time
import numpy as np
from collections import deque
import requests
import json


class AutopilotController:
    """
    Autopilot controller with safety features.
    
    Features:
    - Command smoothing (moving average filter)
    - Safety limits and deadbands
    - Manual confirmation mode
    - Emergency stop
    - State machine for safe transitions
    
    IMPORTANT: HS260 API Limitations
    - API runs on port 9000 (not 3000)
    - Only supports discrete directional commands
    - Does NOT support forward/backward motion
    - Only one command active at a time
    """
    
    def __init__(self, api_url="http://localhost:9000", 
                 confirmation_mode=True,
                 command_history_size=5):
        """
        Initialize autopilot controller.
        
        Args:
            api_url: Base URL for drone control API
            confirmation_mode: If True, require manual confirmation for each command
            command_history_size: Number of commands to average for smoothing
        """
        self.api_url = api_url
        self.confirmation_mode = confirmation_mode
        self.enabled = False
        self.emergency_stop = False
        
        # Command smoothing
        self.command_history_size = command_history_size
        self.vx_history = deque(maxlen=command_history_size)
        self.vy_history = deque(maxlen=command_history_size)
        self.vz_history = deque(maxlen=command_history_size)
        self.yaw_history = deque(maxlen=command_history_size)
        
        # Safety parameters
        self.max_vx = 0.5  # m/s forward (conservative)
        self.max_vy = 0.3  # m/s lateral
        self.max_vz = 0.2  # m/s vertical
        self.max_yaw = 15  # deg/s
        
        self.min_vx = -0.2  # m/s backward (very limited)
        self.deadband = 0.05  # Ignore commands smaller than this
        
        # Control gains (conservative for safety)
        self.balance_gain = 0.3  # Lateral correction gain
        self.speed_gain = 0.2    # Speed regulation gain
        self.obstacle_gain = 0.5 # Obstacle avoidance gain
        
        # Target flow for speed regulation
        self.target_flow = 3.0  # pixels/frame (slow and safe)
        
        # State tracking
        self.last_command_time = 0
        self.command_rate_limit = 0.2  # Minimum seconds between commands
        self.last_command = None
        self.consecutive_stops = 0
        
    def enable(self):
        """Enable autopilot."""
        self.enabled = True
        self.emergency_stop = False
        print("✓ Autopilot ENABLED")
        
    def disable(self):
        """Disable autopilot and stop drone."""
        self.enabled = False
        self._send_stop()
        self._clear_history()
        print("✓ Autopilot DISABLED")
        
    def trigger_emergency_stop(self):
        """Trigger emergency stop."""
        self.emergency_stop = True
        self.enabled = False
        self._send_stop()
        print("🛑 EMERGENCY STOP TRIGGERED")
        
    def _clear_history(self):
        """Clear command history."""
        self.vx_history.clear()
        self.vy_history.clear()
        self.vz_history.clear()
        self.yaw_history.clear()
        
    def compute_control(self, vision_result):
        """
        Compute control commands from vision analysis.
        
        Args:
            vision_result: Output from obstacle detector
            
        Returns:
            dict with 'vx', 'vy', 'vz', 'yaw', 'action'
        """
        if not self.enabled or self.emergency_stop:
            return {'vx': 0, 'vy': 0, 'vz': 0, 'yaw': 0, 'action': 'STOPPED'}
        
        # Extract metrics
        balance = vision_result.get('balance')
        if balance is None:
            return {'vx': 0, 'vy': 0, 'vz': 0, 'yaw': 0, 'action': 'NO_VISION'}
        
        lateral_balance = balance['lateral_balance']
        flow_mag = vision_result['flow_magnitude']
        danger_level = vision_result['danger_level']
        safe_dirs = vision_result['safe_directions']
        
        # Initialize commands
        vx = 0.0  # forward/back
        vy = 0.0  # left/right
        vz = 0.0  # up/down
        yaw = 0.0  # rotation
        action = 'CRUISE'
        
        # Check for emergency situations
        if danger_level >= 3 or not safe_dirs.get('forward', True):
            # EMERGENCY STOP
            self.consecutive_stops += 1
            if self.consecutive_stops >= 3:
                self.trigger_emergency_stop()
                return {'vx': 0, 'vy': 0, 'vz': 0, 'yaw': 0, 'action': 'EMERGENCY'}
            return {'vx': 0, 'vy': 0, 'vz': 0, 'yaw': 0, 'action': 'STOP'}
        
        self.consecutive_stops = 0
        
        # Lateral balance control (bee navigation)
        # Negative balance = right side has more flow -> move LEFT (positive vy)
        # Positive balance = left side has more flow -> move RIGHT (negative vy)
        vy = -lateral_balance * self.balance_gain
        
        # Apply deadband
        if abs(vy) < self.deadband:
            vy = 0.0
        
        # Forward speed control based on flow magnitude
        # If flow is low, we can move forward
        # If flow is high, slow down or stop
        flow_error = (flow_mag - self.target_flow) / (self.target_flow + 0.01)
        
        if flow_mag < self.target_flow * 0.5:
            # Very low flow, safe to move forward at slow speed
            vx = 0.2
            action = 'FORWARD_SLOW'
        elif flow_mag < self.target_flow * 1.2:
            # Good flow, maintain slow forward speed
            vx = 0.15 - (flow_error * self.speed_gain)
            action = 'CRUISE'
        elif flow_mag < self.target_flow * 1.5:
            # Flow getting high, slow down
            vx = max(0, 0.1 - flow_error * self.speed_gain)
            action = 'SLOWING'
        else:
            # Flow too high, stop
            vx = 0.0
            action = 'TOO_FAST'
        
        # Obstacle avoidance adjustments
        if danger_level >= 1:
            # Reduce speed when obstacles detected
            vx *= 0.5
            action = 'OBSTACLE_CAUTION'
            
        if danger_level >= 2:
            # Stop forward motion, focus on avoiding
            vx = 0.0
            action = 'OBSTACLE_AVOID'
            
            # Increase lateral correction
            vy *= 1.5
            
            # If still blocked after correction, try vertical
            if not safe_dirs.get('left', True) and not safe_dirs.get('right', True):
                if safe_dirs.get('up', True):
                    vz = 0.15
                    action = 'OBSTACLE_CLIMB'
                elif safe_dirs.get('down', True):
                    vz = -0.10
                    action = 'OBSTACLE_DESCEND'
        
        # Clamp to safety limits
        vx = np.clip(vx, self.min_vx, self.max_vx)
        vy = np.clip(vy, -self.max_vy, self.max_vy)
        vz = np.clip(vz, -self.max_vz, self.max_vz)
        yaw = np.clip(yaw, -self.max_yaw, self.max_yaw)
        
        return {
            'vx': vx,
            'vy': vy,
            'vz': vz,
            'yaw': yaw,
            'action': action,
            'balance': lateral_balance,
            'flow': flow_mag,
            'danger': danger_level
        }
    
    def _smooth_commands(self, vx, vy, vz, yaw):
        """
        Apply moving average filter to smooth commands.
        
        Args:
            vx, vy, vz, yaw: Raw commands
            
        Returns:
            vx_smooth, vy_smooth, vz_smooth, yaw_smooth: Smoothed commands
        """
        # Add to history
        self.vx_history.append(vx)
        self.vy_history.append(vy)
        self.vz_history.append(vz)
        self.yaw_history.append(yaw)
        
        # Compute moving average
        vx_smooth = np.mean(self.vx_history) if len(self.vx_history) > 0 else 0
        vy_smooth = np.mean(self.vy_history) if len(self.vy_history) > 0 else 0
        vz_smooth = np.mean(self.vz_history) if len(self.vz_history) > 0 else 0
        yaw_smooth = np.mean(self.yaw_history) if len(self.yaw_history) > 0 else 0
        
        return vx_smooth, vy_smooth, vz_smooth, yaw_smooth
    
    def execute_control(self, control_cmd, confirm=None):
        """
        Execute control command (with optional confirmation).
        
        Args:
            control_cmd: Command dict from compute_control()
            confirm: If None, use self.confirmation_mode. If True/False, override.
            
        Returns:
            bool: True if command was sent, False if skipped
        """
        if not self.enabled or self.emergency_stop:
            return False
        
        # Rate limiting
        current_time = time.time()
        if current_time - self.last_command_time < self.command_rate_limit:
            return False
        
        # Extract and smooth commands
        vx, vy, vz, yaw = self._smooth_commands(
            control_cmd['vx'],
            control_cmd['vy'],
            control_cmd['vz'],
            control_cmd['yaw']
        )
        
        # Check if confirmation required
        need_confirm = confirm if confirm is not None else self.confirmation_mode
        
        if need_confirm:
            # Display command and wait for confirmation
            print("\n" + "="*50)
            print(f"📋 AUTOPILOT COMMAND: {control_cmd['action']}")
            print(f"   Forward: {vx:+.2f} m/s")
            print(f"   Lateral: {vy:+.2f} m/s  {'← LEFT' if vy > 0 else '→ RIGHT' if vy < 0 else '⊙ CENTER'}")
            print(f"   Vertical: {vz:+.2f} m/s")
            print(f"   Yaw: {yaw:+.1f} °/s")
            print(f"   Balance: {control_cmd['balance']:+.2f}")
            print(f"   Flow: {control_cmd['flow']:.2f} px/frame")
            print(f"   Danger: {control_cmd['danger']}/5")
            print("="*50)
            
            response = input("Execute? [y/N/stop]: ").strip().lower()
            
            if response == 'stop':
                self.trigger_emergency_stop()
                return False
            elif response != 'y':
                print("⊘ Command skipped")
                return False
        
        # Send command to drone
        success = self._send_velocity_command(vx, vy, vz, yaw)
        
        if success:
            self.last_command_time = current_time
            self.last_command = control_cmd
            
            if not need_confirm:
                # Silent mode - just show brief status
                status = f"✈ {control_cmd['action']:15s} | "
                status += f"Fwd:{vx:+.2f} Lat:{vy:+.2f} | "
                status += f"Bal:{control_cmd['balance']:+.2f} Flow:{control_cmd['flow']:.1f}"
                print(status)
        
        return success
    
    def _send_velocity_command(self, vx, vy, vz, yaw_rate):
        """
        Send velocity command to drone API.
        
        NOTE: The HS260 API doesn't support direct velocity control.
        This method converts velocity commands to discrete directional commands.
        
        Args:
            vx: Forward velocity (m/s) - NOT SUPPORTED, will be 0
            vy: Lateral velocity (m/s) - converted to left/right
            vz: Vertical velocity (m/s) - converted to up/down
            yaw_rate: Yaw rate (deg/s) - converted to yaw left/right
            
        Returns:
            bool: True if successful
        """
        try:
            # HS260 API only supports discrete commands, not continuous velocity
            # We need to send the appropriate directional command based on velocities
            
            commands = []
            
            # Vertical control (priority: altitude changes are most important)
            if abs(vz) > self.deadband:
                if vz > 0:
                    commands.append('move/up')
                else:
                    commands.append('move/down')
            
            # Lateral control (left/right)
            if abs(vy) > self.deadband:
                if vy > 0:
                    commands.append('move/left')
                else:
                    commands.append('move/right')
            
            # Yaw control
            if abs(yaw_rate) > 5.0:  # deg/s threshold
                if yaw_rate > 0:
                    commands.append('yaw/left')
                else:
                    commands.append('yaw/right')
            
            # Note: Forward/backward (vx) not supported by HS260 API
            # The drone can only move laterally and vertically, not forward/backward
            
            # If no movement, send stop
            if not commands:
                response = requests.post(
                    f"{self.api_url}/api/stop",
                    timeout=1.0
                )
                return response.status_code == 200
            
            # Send first command (API only allows one at a time)
            # Priority: vertical > lateral > yaw
            response = requests.post(
                f"{self.api_url}/api/{commands[0]}",
                timeout=1.0
            )
            
            if response.status_code == 200:
                return True
            else:
                print(f"✗ API error: {response.status_code}")
                return False
                
        except requests.exceptions.Timeout:
            print("✗ API timeout")
            return False
        except requests.exceptions.ConnectionError:
            print("✗ Cannot connect to API (port 9000) - is Android app running?")
            print("   Run: adb forward tcp:9000 tcp:9000")
            return False
        except Exception as e:
            print(f"✗ Command error: {e}")
            return False
    
    def _send_stop(self):
        """Send stop command to drone."""
        try:
            requests.post(
                f"{self.api_url}/api/drone/stop",
                timeout=1.0
            )
        except:
            pass
    
    def get_status(self):
        """Get autopilot status."""
        return {
            'enabled': self.enabled,
            'emergency_stop': self.emergency_stop,
            'confirmation_mode': self.confirmation_mode,
            'last_command': self.last_command,
            'command_count': len(self.vx_history)
        }

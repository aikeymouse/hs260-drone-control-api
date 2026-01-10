#!/usr/bin/env python3
"""
Autopilot Runner - Interactive autopilot control for HS260

Connects to vision server and runs autopilot with manual confirmation mode.
"""

import sys
import time
import threading
from autopilot import AutopilotController

# Shared vision result (will be updated from vision_server)
latest_vision_result = None
result_lock = threading.Lock()


def main():
    print("="*60)
    print("  HS260 AUTOPILOT CONTROLLER")
    print("="*60)
    print()
    print("This script demonstrates safe autopilot navigation using")
    print("vision-based optical flow balancing.")
    print()
    print("SAFETY FEATURES:")
    print("  • Manual confirmation required for each command")
    print("  • Command smoothing (5-step moving average)")
    print("  • Rate limiting (max 1 command per 0.2s)")
    print("  • Emergency stop on consecutive failures")
    print("  • Conservative speed limits")
    print()
    
    # Get API URL
    api_url = input("Enter drone API URL [http://localhost:3000]: ").strip()
    if not api_url:
        api_url = "http://localhost:3000"
    
    # Create autopilot controller
    print(f"\n✓ Connecting to API: {api_url}")
    autopilot = AutopilotController(
        api_url=api_url,
        confirmation_mode=True,  # Require manual confirmation
        command_history_size=5   # Smooth over 5 commands
    )
    
    print("\n" + "="*60)
    print("  AUTOPILOT CONFIGURATION")
    print("="*60)
    print(f"  Max forward speed:  {autopilot.max_vx} m/s")
    print(f"  Max lateral speed:  {autopilot.max_vy} m/s")
    print(f"  Max vertical speed: {autopilot.max_vz} m/s")
    print(f"  Target flow:        {autopilot.target_flow} px/frame")
    print(f"  Balance gain:       {autopilot.balance_gain}")
    print(f"  Speed gain:         {autopilot.speed_gain}")
    print(f"  Confirmation mode:  {'ON' if autopilot.confirmation_mode else 'OFF'}")
    print("="*60)
    
    print("\nNOTE: This script requires vision_server.py to be running")
    print("      and providing vision analysis results.")
    print()
    print("For this demo, we'll simulate vision data.")
    print("In production, integrate with vision_server.py")
    print()
    
    # Demo mode - simulate vision data
    response = input("Run demo with simulated vision data? [y/N]: ").strip().lower()
    if response != 'y':
        print("\nTo integrate with real vision:")
        print("  1. Modify vision_server.py to expose obstacle_result")
        print("  2. Add autopilot.compute_control(obstacle_result)")
        print("  3. Add autopilot.execute_control(control_cmd)")
        print()
        return
    
    print("\n" + "="*60)
    print("  STARTING DEMO MODE")
    print("="*60)
    
    # Enable autopilot
    autopilot.enable()
    
    # Simulate vision scenarios
    scenarios = [
        {
            'name': 'Scenario 1: Centered, low flow',
            'vision': {
                'balance': {'lateral_balance': 0.05},
                'flow_magnitude': 1.5,
                'danger_level': 0,
                'safe_directions': {'forward': True, 'left': True, 'right': True, 'up': True, 'down': True}
            }
        },
        {
            'name': 'Scenario 2: Drifting right (balance -0.45)',
            'vision': {
                'balance': {'lateral_balance': -0.45},
                'flow_magnitude': 4.8,
                'danger_level': 0,
                'safe_directions': {'forward': True, 'left': True, 'right': True, 'up': True, 'down': True}
            }
        },
        {
            'name': 'Scenario 3: Obstacle ahead',
            'vision': {
                'balance': {'lateral_balance': 0.10},
                'flow_magnitude': 6.5,
                'danger_level': 2,
                'safe_directions': {'forward': False, 'left': True, 'right': True, 'up': True, 'down': True}
            }
        },
        {
            'name': 'Scenario 4: Corridor too narrow',
            'vision': {
                'balance': {'lateral_balance': -0.20},
                'flow_magnitude': 8.2,
                'danger_level': 3,
                'safe_directions': {'forward': False, 'left': False, 'right': False, 'up': True, 'down': True}
            }
        },
    ]
    
    try:
        for i, scenario in enumerate(scenarios, 1):
            print(f"\n{'='*60}")
            print(f"  {scenario['name']}")
            print(f"{'='*60}")
            
            # Compute control from simulated vision
            control_cmd = autopilot.compute_control(scenario['vision'])
            
            # Execute with confirmation
            success = autopilot.execute_control(control_cmd)
            
            if not success:
                print("\n⚠ Command not executed")
                
                if autopilot.emergency_stop:
                    print("\n🛑 EMERGENCY STOP ACTIVE - Exiting demo")
                    break
            else:
                print("\n✓ Command sent to drone")
            
            # Wait a bit between scenarios
            if i < len(scenarios):
                print("\nPress Enter for next scenario...")
                input()
    
    except KeyboardInterrupt:
        print("\n\n⊘ Demo interrupted by user")
    
    finally:
        autopilot.disable()
        print("\n✓ Autopilot disabled")
        print("\nDemo complete!")


if __name__ == "__main__":
    main()

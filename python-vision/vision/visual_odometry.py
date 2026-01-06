#!/usr/bin/env python3
"""
Visual Odometry for HS260 Drone.

Tracks ORB features across frames to estimate camera motion and build trajectory.
"""

import cv2
import numpy as np
from collections import deque


class VisualOdometry:
    """Monocular visual odometry using ORB features."""
    
    def __init__(self, focal_length=800, pp=(640, 360)):
        """
        Initialize visual odometry.
        
        Args:
            focal_length: Camera focal length in pixels (estimated)
            pp: Principal point (cx, cy) - image center
        """
        # Camera intrinsics (estimated for 1280x720)
        self.focal_length = focal_length
        self.pp = pp
        self.K = np.array([
            [focal_length, 0, pp[0]],
            [0, focal_length, pp[1]],
            [0, 0, 1]
        ], dtype=np.float32)
        
        # ORB detector and matcher
        self.orb = cv2.ORB_create(nfeatures=1000, scaleFactor=1.2, nlevels=8)
        self.matcher = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=False)
        
        # Previous frame data
        self.prev_frame = None
        self.prev_kp = None
        self.prev_desc = None
        
        # Trajectory tracking
        self.trajectory = deque(maxlen=500)  # Store last 500 positions
        self.current_pos = np.zeros(3)  # [x, y, z]
        self.current_rot = np.eye(3)
        
        # Scale (unknown in monocular - start with estimate)
        self.scale = 1.0
        
        # Motion thresholds (filter noise when stationary)
        self.min_translation = 0.5   # Minimum translation to accept (very strict)
        self.min_rotation = 0.15     # Minimum rotation to accept (very strict)
        self.min_inliers = 30        # Minimum RANSAC inliers for valid motion
        
        # Stats
        self.total_frames = 0
        self.good_frames = 0
        self.stationary_frames = 0
        
    def process_frame(self, frame):
        """
        Process new frame and estimate motion.
        
        Args:
            frame: BGR image (720p)
            
        Returns:
            dict with: matches, motion, position, trajectory_overlay
        """
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        
        # Detect features
        kp, desc = self.orb.detectAndCompute(gray, None)
        
        result = {
            'keypoints': kp,
            'matches': [],
            'motion': None,
            'position': self.current_pos.copy(),
            'num_features': len(kp),
            'frame_ready': False
        }
        
        # First frame - just store
        if self.prev_frame is None:
            self.prev_frame = gray
            self.prev_kp = kp
            self.prev_desc = desc
            self.trajectory.append(self.current_pos.copy())
            return result
        
        # Match features with previous frame
        if desc is not None and self.prev_desc is not None and len(desc) > 0 and len(self.prev_desc) > 0:
            try:
                matches = self.matcher.knnMatch(self.prev_desc, desc, k=2)
            except cv2.error:
                # Not enough descriptors
                self.prev_frame = gray
                self.prev_kp = kp
                self.prev_desc = desc
                self.total_frames += 1
                return result
            
            # Lowe's ratio test
            good_matches = []
            for match_pair in matches:
                if len(match_pair) == 2:
                    m, n = match_pair
                    if m.distance < 0.75 * n.distance:
                        good_matches.append(m)
            
            result['matches'] = good_matches
            result['num_matches'] = len(good_matches)
            
            # Need at least 8 points for essential matrix
            if len(good_matches) >= 8:
                # Extract matched point coordinates
                pts1 = np.float32([self.prev_kp[m.queryIdx].pt for m in good_matches])
                pts2 = np.float32([kp[m.trainIdx].pt for m in good_matches])
                
                # Estimate essential matrix
                E, mask = cv2.findEssentialMat(pts2, pts1, self.K, 
                                              method=cv2.RANSAC, 
                                              prob=0.999, 
                                              threshold=1.0)
                
                if E is not None:
                    # Recover pose
                    _, R, t, mask = cv2.recoverPose(E, pts2, pts1, self.K, mask=mask)
                    
                    inlier_count = int(mask.sum())
                    
                    # Check if motion is significant (filter noise)
                    translation_magnitude = np.linalg.norm(t)
                    rotation_magnitude = np.linalg.norm(R - np.eye(3))
                    
                    is_moving = (translation_magnitude > self.min_translation or 
                                rotation_magnitude > self.min_rotation) and \
                               inlier_count >= self.min_inliers
                    
                    if is_moving:
                        # Update position (scale unknown - use constant)
                        self.current_pos += self.current_rot.dot(t.ravel()) * self.scale
                        self.current_rot = R.dot(self.current_rot)
                        
                        self.trajectory.append(self.current_pos.copy())
                        
                        result['motion'] = {
                            'rotation': R,
                            'translation': t,
                            'inliers': inlier_count,
                            'moving': True,
                            't_mag': translation_magnitude,
                            'r_mag': rotation_magnitude
                        }
                        result['frame_ready'] = True
                        self.good_frames += 1
                    else:
                        # Stationary - don't update position
                        result['motion'] = {
                            'rotation': R,
                            'translation': t,
                            'inliers': inlier_count,
                            'moving': False,
                            't_mag': translation_magnitude,
                            'r_mag': rotation_magnitude
                        }
                        result['frame_ready'] = True
                        self.stationary_frames += 1
        
        # Update previous frame
        self.prev_frame = gray
        self.prev_kp = kp
        self.prev_desc = desc
        self.total_frames += 1
        
        return result
    
    def draw_trajectory(self, frame, scale=10, offset=(100, 500)):
        """
        Draw 2D trajectory overlay on frame.
        
        Args:
            frame: BGR image to draw on
            scale: Pixels per unit distance
            offset: (x, y) offset for trajectory center
        """
        if len(self.trajectory) < 2:
            return frame
        
        # Draw trajectory path
        for i in range(1, len(self.trajectory)):
            p1 = self.trajectory[i-1]
            p2 = self.trajectory[i]
            
            # Convert 3D position to 2D screen coords (top-down view: x, z)
            pt1 = (int(offset[0] + p1[0] * scale), 
                   int(offset[1] - p1[2] * scale))
            pt2 = (int(offset[0] + p2[0] * scale), 
                   int(offset[1] - p2[2] * scale))
            
            # Color gradient (newer = brighter green)
            alpha = i / len(self.trajectory)
            color = (0, int(100 + 155 * alpha), 0)
            
            cv2.line(frame, pt1, pt2, color, 2)
        
        # Draw current position
        curr_pt = (int(offset[0] + self.current_pos[0] * scale),
                   int(offset[1] - self.current_pos[2] * scale))
        cv2.circle(frame, curr_pt, 5, (0, 255, 255), -1)
        
        # Draw reference grid
        cv2.line(frame, (offset[0]-50, offset[1]), 
                (offset[0]+50, offset[1]), (100, 100, 100), 1)
        cv2.line(frame, (offset[0], offset[1]-50), 
                (offset[0], offset[1]+50), (100, 100, 100), 1)
        
        return frame
    
    def draw_matches(self, frame, result):
        """
        Draw feature matches as motion vectors.
        
        Args:
            frame: BGR image
            result: Result dict from process_frame()
        """
        if not result.get('matches') or self.prev_kp is None:
            return frame
        
        if not result.get('keypoints') or len(result['keypoints']) == 0:
            return frame
        
        # Draw subset of matches (avoid clutter)
        step = max(1, len(result['matches']) // 50)
        
        for i, match in enumerate(result['matches'][::step]):
            try:
                pt1 = tuple(map(int, self.prev_kp[match.queryIdx].pt))
                pt2 = tuple(map(int, result['keypoints'][match.trainIdx].pt))
                
                # Draw motion vector
                cv2.arrowedLine(frame, pt1, pt2, (0, 255, 255), 1, tipLength=0.3)
            except (IndexError, AttributeError):
                # Skip invalid matches
                continue
        
        return frame
    
    def get_stats(self):
        """Get odometry statistics."""
        return {
            'total_frames': self.total_frames,
            'good_frames': self.good_frames,
            'stationary_frames': self.stationary_frames,
            'success_rate': self.good_frames / max(1, self.total_frames),
            'trajectory_length': len(self.trajectory),
            'distance_traveled': np.linalg.norm(self.current_pos),
            'position': self.current_pos.tolist()
        }

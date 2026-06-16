# DLSS Jitter and Motion Vector Refactoring Plan

## Executive Summary

This document outlines the necessary fixes for DLSS jitter and motion vector implementation in the Vulkanite RT Minecraft mod to resolve visual artifacts (texture smearing, ghosting) caused by incorrect implementation of NVIDIA DLSS requirements.

After analyzing the key files (`JitterManager.java`, `MotionVectorsGenerator.java`, `DLSSRayReconstruction.java`, `UBODataEncoder.java`, and `dlss_wrapper.cpp`), several critical issues were identified that violate NVIDIA DLSS best practices:

1. **Jitter Offset Issues**: Jitter values are correctly in pixel space [-0.5, 0.5] but are not being properly handled in motion vector calculations
2. **Motion Vector Issues**: Motion vectors are incorrectly calculated and scaled, causing temporal instability
3. **Jitter Compensation Issues**: Jitter delta is being double-applied or incorrectly subtracted in multiple locations
4. **DLSS Integration Issues**: Incorrect parameters are being passed to DLSS evaluation functions

The root cause of texture smearing and ghosting is primarily due to:
- Incorrect motion vector calculation that doesn't properly account for jitter
- Double application of jitter compensation in both the Java layer and native bridge
- Improper handling of reset frames causing temporal instability

## Detailed Analysis

### 1. JitterManager.java (Lines 1-236)
**Status: Generally Correct**
- Jitter values are correctly maintained in pixel space [-0.5, 0.5]
- Uses Halton sequence for low-discrepancy sampling
- Properly tracks previous frame jitter for motion vector calculation
- Correctly applies jitter to projection matrix in NDC space

**Minor Issues:**
- Line 52-53: Dimension alignment to multiples of 8 is correct for DLSS
- Line 75-76: Jitter generation correctly shifts [0,1) to [-0.5, 0.5]
- Lines 99-102: Proper conversion from pixel to NDC space for matrix application

### 2. MotionVectorsGenerator.java (Lines 1-354)
**Status: Needs Significant Fixes**
- **Critical Issue**: Motion vectors are being calculated WITH jitter included, then jitter delta is being subtracted, causing double compensation
- **Critical Issue**: Motion vector scaling is incorrect - should be 1.0 for pixel space vectors
- **Issue**: Viewport dimensions are taken from window size rather than render resolution
- **Issue**: Jitter delta calculation is correct but application in shaders is problematic

**Key Problems:**
- Lines 202-211: Jitter delta calculation is correct (current - previous)
- Lines 223-232: Jitter delta is correctly passed to UBO
- **BUT**: The motion vector generation itself includes jitter effects, then tries to compensate - this is wrong approach
- Lines 330-338: Viewport dimensions should match render resolution, not window size

### 3. DLSSRayReconstruction.java (Lines 1-850)
**Status: Needs Significant Fixes**
- **Critical Issue**: Jitter values are being passed to DLSS evaluation functions, but motion vectors already include jitter compensation
- **Critical Issue**: In standard DLSS processFrame() (lines 503-516), jitter is passed directly to evaluateDLSS
- **Critical Issue**: In DLSSD processFrameDLSSD() (lines 615-619), jitter is passed directly to evaluateDLSSD
- **Issue**: Reset handling is inconsistent between standard DLSS and DLSSD paths
- **Issue**: Output dimensions are forced to match render dimensions (lines 241-244), preventing upscaling

**Key Problems:**
- Lines 503-516: Standard DLSS passes jitter directly to native bridge
- Lines 615-619: DLSSD passes jitter directly to native bridge
- Lines 241-244: Output width/height forced to equal render width/height (no upscaling)
- Lines 274-275: Depth type set to LINEAR but comment indicates confusion

### 4. UBODataEncoder.java (Lines 1-180)
**Status: Needs Minor Fixes**
- **Issue**: Jitter data is being encoded into UBO for shaders, but shaders may be applying jitter compensation incorrectly
- **Issue**: Comment on line 101 states "We pass 0 for jitter to DLSS, so no jitter compensation is needed" - this is incorrect
- **Issue**: The UBO contains both current and previous jitter values, but usage in shaders is unclear

**Key Problems:**
- Lines 62-65: Correctly retrieves jitter values and render dimensions
- Lines 76-82: Correctly computes unjittered projection for corner vectors
- Lines 101-103: Incorrect comment about passing 0 jitter to DLSS
- Lines 153-158: Correctly encodes jitter data into UBO

### 5. dlss_wrapper.cpp (Lines 1-1126)
**Status: Needs Significant Fixes**
- **Critical Issue**: In EvaluateDLSS_Internal() (lines 398-400), motion vector scale is set to 1.0f (correct)
- **Critical Issue**: In EvaluateDLSSD() (lines 630-631), motion vector scale is set to 1.0f (correct)
- **Issue**: However, if motion vectors from Java already include jitter compensation, then passing jitter separately to DLSS causes double compensation
- **Issue**: Reset handling in DLSSD path forces reset=1 on feature creation (line 542) which may be too aggressive
- **Issue**: Dimension validation and alignment could be improved

**Key Problems:**
- Lines 398-400: Motion vector scale correctly set to 1.0 for pixel space
- Lines 630-631: Motion vector scale correctly set to 1.0 for pixel space
- Lines 542-543: Forcing reset=1 on every feature creation may cause instability
- Lines 553-558: Fallback from DLSSD to standard DLSS doesn't properly handle jitter

## Specific Code Changes Needed

### 1. MotionVectorsGenerator.java Fixes
**Problem**: Motion vectors include jitter effects, then jitter delta is subtracted causing incorrect results.

**Solution**: Generate motion vectors WITHOUT jitter effects, then DLSS will apply jitter compensation internally.

**Changes**:
```java
// In updateMotionVectors method, replace lines 214-216:
// Store current matrices
this.currentViewMatrix.set(currentView);
this.currentProjectionMatrix.set(currentProjection);
this.currentViewProjectionMatrix = new Matrix4f(currentProjection).mul(currentView);

// WITH:
// Store current matrices WITHOUT jitter for motion vector calculation
Matrix4f unjitteredCurrentProjection = new Matrix4f(currentProjection);
float currentJitterX = JitterManager.getJitterX();
float currentJitterY = JitterManager.getJitterY();
if (currentJitterX != 0 || currentJitterY != 0) {
    float ndcJitterX = (currentJitterX * 2.0f) / viewportWidth;
    float ndcJitterY = (currentJitterY * 2.0f) / viewportHeight;
    unjitteredCurrentProjection.m20(unjitteredCurrentProjection.m20() + ndcJitterX);
    unjitteredCurrentProjection.m21(unjitteredCurrentProjection.m21() + ndcJitterY);
}
this.currentViewMatrix.set(currentView);
this.currentProjectionMatrix.set(unjitteredCurrentProjection);
this.currentViewProjectionMatrix = new Matrix4f(unjitteredCurrentProjection).mul(currentView);

// Also need to update previous frame storage similarly
```

### 2. DLSSRayReconstruction.java Fixes
**Problem**: Jitter is being passed to DLSS evaluation functions, but motion vectors should be jitter-free.

**Solution**: Pass jitter values to DLSS so it can compensate internally, but ensure motion vectors are jitter-free.

**Changes**:
```java
// In processFrame method, lines 503-516 are CORRECT as-is
// In processFrameDLSSD method, lines 615-619 are CORRECT as-is
// BUT we need to ensure motion vectors passed in are jitter-free (fixed in MotionVectorsGenerator)

// Add upscaling support - remove lines 241-244 that force 1:1 ratio
// Replace with:
this.renderWidth = (int)(outputWidth * qualityPreset.getScale());
this.renderHeight = (int)(outputHeight * qualityPreset.getScale());
// Ensure even dimensions for DLSS
this.renderWidth = Math.max(8, this.renderWidth & ~7);
this.renderHeight = Math.max(8, this.renderHeight & ~7);
this.outputWidth = outputWidth;
this.outputHeight = outputHeight;

// Fix depth type confusion - linear depth is correct for ray tracing
// Line 274: Keep DLSSBridge.DEPTH_TYPE_LINEAR (correct)
```

### 3. UBODataEncoder.java Fixes
**Problem**: Confusing comment about passing 0 jitter to DLSS.

**Solution**: Update comment to reflect correct usage.

**Changes**:
```java
// Line 101-103: Replace comment
// OLD:
// We pass 0 for jitter to DLSS, so no jitter compensation is needed.
//
// NEW:
// Jitter values are passed to DLSS for internal compensation.
// The ray tracing shader uses unjittered projection for corner vectors
// to avoid visible jitter/shaking, while DLSS handles jitter compensation
// internally using InJitterOffsetX/Y parameters.
```

### 4. dlss_wrapper.cpp Fixes
**Problem**: Reset handling may be too aggressive, dimension validation could be improved.

**Solution**: Improve reset logic and add better dimension validation.

**Changes**:
```cpp
// In EvaluateDLSSD, lines 541-543: Modify reset forcing
// OLD:
Log("DLSSD feature created successfully, forcing reset=1 for first frame");
reset = 1; // FORCE reset on first frame after creation

// NEW:
Log("DLSSD feature created successfully");
if (reset == 0) {  // Only force reset if not already requested
    reset = 1;     // FORCE reset on first frame after creation
}

// Add better dimension validation in CreateDLSSDFeature
// After line 824, add:
if (m_DLSSDRenderWidth > m_DLSSDOutWidth * 2 || m_DLSSDRenderHeight > m_DLSSDOutHeight * 2) {
    Log("WARNING: Render dimensions are more than 2x output dimensions. This may cause issues.");
}
```

## Implementation Priority Order

1. **High Priority**: Fix MotionVectorsGenerator.java to generate jitter-free motion vectors
2. **High Priority**: Ensure DLSSRayReconstruction passes correct jitter values to native bridge
3. **Medium Priority**: Fix UBODataEncoder confusing comment
4. **Medium Priority**: Improve dlss_wrapper.cpp reset handling and dimension validation
5. **Low Priority**: Enable proper upscaling support in DLSSRayReconstruction

## Testing/Validation Recommendations

1. **Visual Validation**:
   - Test with static camera to verify no texture smearing
   - Test with slow camera movement to verify proper temporal stability
   - Test with fast camera movement to verify no excessive ghosting
   - Test with moving entities to verify object motion vectors work correctly

2. **Technical Validation**:
   - Enable debug logging to verify jitter values are in [-0.5, 0.5] range
   - Verify motion vector values are reasonable (typically < 5.0 pixels/frame for normal movement)
   - Check that reset frames are handled correctly (no sudden image jumps)
   - Verify DLSS feature creation succeeds with proper dimensions

3. **Comparison Testing**:
   - Compare with DLSS disabled to ensure base rendering is correct
   - Compare different quality presets to ensure scaling works correctly
   - Test both standard DLSS and DLSSD (Ray Reconstruction) paths

4. **Automated Tests**:
   - Add unit tests for JitterManager to verify Halton sequence and ranges
   - Add unit tests for MotionVectorsGenerator to verify jitter-free vector generation
   - Add integration tests that verify DLSS evaluation parameters are within expected ranges

## Expected Outcomes

After implementing these fixes:
- Texture smearing should be eliminated due to proper jitter handling
- Ghosting should be reduced due to correct motion vector calculation
- Temporal stability should improve during both camera and object movement
- DLSS should properly utilize its internal jitter compensation mechanism
- Both standard DLSS and DLSSD paths should work correctly with appropriate fallback

## References

- NVIDIA DLSS 3.5 SDK Documentation
- NVIDIA DLSS Integration Guide for Ray Reconstruction
- Vulkan Specification for proper resource handling
- Official DLSS best practices for jitter and motion vector implementation

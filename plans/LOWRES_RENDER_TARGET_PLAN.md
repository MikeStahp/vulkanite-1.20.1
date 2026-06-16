# Low-Resolution Render Target Solution for DLSS

## Current Architecture Analysis

### Render Target Creation Flow
Based on analysis of the Iris mixins (`MixinRenderTarget.java` and `MixinRenderTargets.java`):

1. **Render Target Index Tracking**: 
   - `MixinRenderTargets` sets/gets the current render target index via `IRenderTargetVkGetter`
   - Index 0 = Output buffer (colortex0)
   - Indices 1-5 = G-buffers (colortex1-5)

2. **Resolution Determination** (`MixinRenderTarget.setupTextures()`):
   - For G-buffers (indices 1-5) with DLSS scaling active: Uses scaled resolution from `ResolutionScaleManager`
   - For output buffer (index 0) and other buffers: Uses full window resolution
   - Scaled resolution is calculated as: `(window_dimension * scale) & ~7` (aligned to 8 pixels)

3. **ResolutionScaleManager**:
   - Manages DLSS scaling based on quality presets (QUALITY = 0.667, etc.)
   - Provides `getRenderWidth()`/`getRenderHeight()` for scaled dimensions
   - Provides `getOutputWidth()`/`getOutputHeight()` for full dimensions

### DLSS Pipeline Integration
From `DLSSRayReconstruction.java`:

1. **Input Requirements**:
   - Noisy ray-traced input: Render resolution (scaled)
   - Motion vectors: Render resolution (scaled)
   - Depth buffer: Render resolution (scaled)
   - G-buffers (for DLSSD): Render resolution (scaled)
   - Output: Full resolution (for display)

2. **Buffer Creation** (`createBuffers()`):
   - All internal DLSS buffers created at render resolution
   - Output buffer created at full resolution

3. **Processing Methods**:
   - `processFrame()`: Standard DLSS (inputs: noisy input, motion vectors, depth)
   - `processFrameDLSSD()`: DLSS Ray Reconstruction (adds G-buffer inputs)

### Identified Issue
Despite the Iris mixins correctly creating G-buffers at scaled resolution when DLSS is active, the task states that "render passes are created at window resolution instead of the lower resolution needed for DLSS upscaling."

This suggests one of two problems:
1. The ray tracing pass generating the noisy input, motion vectors, and depth is not using the scaled G-buffer resolutions
2. The noisy ray-traced output is being written to the full-resolution output buffer (colortex0) instead of a scaled G-buffer

## Proposed Solution

### Core Concept
Ensure that all DLSS input buffers (noisy ray-traced output, motion vectors, depth, and G-buffers) are created and used at the scaled render resolution, while maintaining the final output at full resolution for display.

### Specific Changes Needed

#### 1. Verify and Fix Render Target Usage
**File**: `src/main/java/me/cortex/vulkanite/mixin/iris/MixinRenderTarget.java`
- Confirm that ray tracing outputs are directed to G-buffers (indices 1-5) rather than output buffer (index 0)
- Ensure that when DLSS is active, the ray tracing pass binds to the scaled G-buffer textures

#### 2. Ensure Consistent Resolution Usage
**Files to check** (need to examine):
- Ray tracing pass implementation to verify it uses G-buffer render targets
- Motion vectors generation to verify it writes to scaled resolution
- Depth buffer creation for ray tracing to verify scaled resolution

#### 3. Maintain Backward Compatibility
- When DLSS is disabled, all render targets should use full resolution
- The solution should not break existing non-DLSS rendering paths

### Integration Points with Existing DLSS Pipeline

#### Input Flow to DLSS:
1. **Ray Tracing Pass**:
   - Renders to G-buffers (colortex1-5) at scaled resolution when DLSS active
   - Outputs noisy ray-traced image to a designated G-buffer (e.g., colortex1)
   - Writes motion vectors and depth to their respective targets at scaled resolution

2. **DLSS Processing**:
   - `DLSSRayReconstruction.processFrame()` or `processFrameDLSSD()` receives:
     - Noisy input: From ray tracing output G-buffer (scaled resolution)
     - Motion vectors: From motion vectors target (scaled resolution)
     - Depth: From depth target (scaled resolution)
     - G-buffers: From colortex1-5 (scaled resolution) [for DLSSD]
   - Produces denoised output at full resolution

3. **Output Usage**:
   - DLSS output (full resolution) used by Iris for final compositing to screen
   - Replaces or supplements the traditional colortex0 output

### Code Changes Needed

#### Primary Files to Modify:
1. **MixinRenderTarget.java** (verify/correct):
   - Ensure proper texture binding for ray tracing pass
   - Confirm G-buffer indices are used for DLSS-relevant targets

2. **Ray Tracing Implementation** (examine):
   - Verify render target bindings use G-buffer textures
   - Check that render resolution matches scaled dimensions when DLSS active

3. **Motion Vectors Generator** (examine):
   - Confirm output target uses scaled resolution when DLSS active

4. **Depth Buffer Creation** (examine):
   - Verify ray tracing depth buffer uses scaled resolution when DLSS active

#### No New Files Required:
The solution leverages existing infrastructure:
- `ResolutionScaleManager` for dimension calculations
- `MixinRenderTarget` for render target creation
- `DLSSRayReconstruction` for DLSS processing
- Existing G-buffer system (colortex1-5)

### Testing Approach

#### 1. Validation Tests:
- Verify G-buffer dimensions are scaled when DLSS enabled
- Verify output buffer dimensions remain full resolution
- Confirm ray tracing outputs match scaled G-buffer dimensions

#### 2. Integration Tests:
- Check that DLSS receives correctly scaled input buffers
- Validate that DLSS output is full resolution
- Ensure final displayed image is correct

#### 3. Regression Tests:
- Confirm non-DLSS rendering still works correctly
- Verify image quality and performance in both modes

#### 4. Debugging Enhancements:
Add diagnostic logging to track:
- Render target creation dimensions
- DLSS input/output buffer dimensions
- Resolution scale factor application

## Summary

The current architecture in `MixinRenderTarget.java` already implements the core concept of creating G-buffers at scaled resolution when DLSS is active. The issue likely lies in ensuring that the ray tracing pass and related rendering steps actually use these scaled G-buffer targets for their outputs, rather than inadvertently using full-resolution targets.

The solution involves verifying and potentially correcting the render target bindings in the ray tracing and related passes to ensure they consume and produce data at the appropriate scaled resolution when DLSS is enabled, while maintaining full-resolution output for final display.
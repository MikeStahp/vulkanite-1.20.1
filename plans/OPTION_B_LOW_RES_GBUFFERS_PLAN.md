# Implementation Plan for Option B: Render G-Buffers at Low Resolution

## Architecture Overview

Vulkanite RT combines Iris Shaders (OpenGL) for G-buffer rendering with Vulkan Ray Tracing for global illumination and DLSSD for AI denoising/upscaling. The current implementation renders G-buffers at full resolution and then downscaling them for DLSSD input, which is inefficient.

Option B aims to render G-buffers directly at the render resolution (scaled when DLSS is active) to eliminate downscaling overhead and ensure consistent quality across all DLSSD inputs.

### Current Working Architecture (Based on Analysis)

From examining the code, particularly `MixinRenderTarget.java`, the system already has the foundation for rendering G-buffers at scaled resolution:

1. **ResolutionScaleManager** - Provides scaled render dimensions when DLSS is active
2. **MixinRenderTarget** - Already implements logic to create G-buffers (colortex1-5) at scaled resolution when DLSS scaling is active
3. **IRenderTargetVkGetter** - Tracks render target index to distinguish between output buffer (index 0) and G-buffers (indices 1-5)

However, the issue identified in the task description suggests that while G-buffers are created at scaled resolution, the ray tracing pass and related rendering steps may not be using these scaled G-buffer targets correctly.

### Target Architecture for Option B

With Option B fully implemented:
- **G-buffers (colortex1-5)**: Created and used at render resolution (scaled when DLSS active)
- **Ray tracing output**: Written to G-buffer at render resolution
- **Motion vectors**: Generated at render resolution
- **Depth buffer for DLSS**: Created at render resolution
- **DLSSD inputs**: All consume render resolution buffers directly (no downscaling needed)
- **DLSSD output**: Full resolution (for display)
- **Final composite**: Uses DLSSD output at full resolution

## Specific Code Changes Needed

Based on the analysis, the core infrastructure for low-resolution G-buffer rendering is already in place in `MixinRenderTarget.java`. The main work involves verifying and ensuring consistent usage of these scaled buffers throughout the pipeline.

### 1. Verify and Fix Render Target Usage (MixinRenderTarget.java)

**File**: `src/main/java/me/cortex/vulkanite/mixin/iris/MixinRenderTarget.java`

**Current State**: The code already implements conditional resolution scaling:
- G-buffers (indices 1-5) use scaled resolution when DLSS scaling is active
- Output buffer (index 0) and other buffers use full resolution

**Verification Needed**:
- Confirm that the ray tracing pass binds to G-buffer textures (colortex1-5) rather than the output buffer
- Ensure that when DLSS is active, the ray tracing pass uses the scaled G-buffer textures

**Changes**: Primarily verification and diagnostic logging. No functional changes likely needed if the current implementation is correct.

### 2. Ensure Ray Tracing Pass Uses Correct Render Targets

**Files to Examine**:
- `src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java`
- `src/main/java/me/cortex/vulkanite/client/rendering/RenderPassExecutor.java`

**Current State**: 
- In `VulkanPipeline.java`, the ray tracing pass creates a `scaledOutputImage` at render resolution (binding 12)
- G-buffer views are passed to the ray tracing pass via `gbufferViews` parameter
- The system appears to be designed for hybrid rendering where ray tracing reads from G-buffers

**Issue Identified**: 
The task description indicates that "render passes are created at window resolution instead of the lower resolution needed for DLSS upscaling." This suggests that despite G-buffers being created at scaled resolution, the ray tracing pass might be writing to full-resolution targets.

**Changes Needed**:
1. Verify in `VulkanPipeline.java` that the ray tracing shader writes its output to a G-buffer (not the Iris output target) when DLSS is active
2. Ensure that the `scaledOutputImage` (used as ray tracing output) is correctly bound as a G-buffer equivalent
3. Check that motion vector generation writes to a render resolution target

### 3. Motion Vector Generation Verification

**File**: `src/main/java/me/cortex/vulkanite/client/rendering/MotionVectorsGenerator.java` (not in open tabs but referenced in plans)

**Current State**: Based on the DLSS_JITTER_MOTION_FIX.md plan, motion vectors should be generated at render resolution.

**Verification Needed**:
- Confirm motion vector image creation uses `ResolutionScaleManager.getRenderWidth()/Height()` when DLSS active
- Ensure motion vectors represent geometric motion at render resolution

### 4. Depth Buffer for DLSS Verification

**File**: `src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java` (look for depth image creation)

**Current State**: 
- `dlssDepthImage` is obtained from `dlssdProcessor.getDepthImage()`
- Need to verify this is created at render resolution when DLSS active

**Changes Needed**:
- Verify depth buffer initialization in `DLSSDProcessor` uses render resolution
- Ensure depth buffer passed to DLSSD matches render resolution

### 5. Shader Changes (VulkaniteRT Shader Pack)

**Location**: `run/shaderpacks/VulkaniteRT/shaders/`

**Analysis Needed**:
- Check if shaders have hardcoded resolution assumptions
- Verify that texture sampling in ray tracing shaders accounts for render resolution vs output resolution
- Check that any resolution-dependent calculations use the correct dimensions

**Specific Files to Check**:
- `ray0.rgen` - Ray generation shader
- `gbuffers_terrain.vsh`/`gbuffers_terrain.fsh` - G-buffer shaders
- `lib/rt/` files - Ray tracing utility shaders

**Likely Changes**: 
If the Java side correctly manages resolutions, shader changes may be minimal. However, need to verify:
- UV coordinate calculations account for render resolution
- Any screen-space effects use correct resolution
- G-buffer sampling in ray tracing shaders uses render resolution textures

### 6. Ensure Consistent Resolution Usage Across Pipeline

**Key Integration Points**:
1. **Jitter Calculation** - Must use render resolution (already handled by JitterManager via ResolutionScaleManager?)
2. **Motion Vector Calculation** - Must use render resolution
3. **Ray Tracing Launch Dimensions** - Must match render resolution
4. **Final Composite to Backbuffer** - Uses DLSSD output at full resolution

**Files to Check**:
- `JitterManager.java` - Verify it uses render resolution for jitter calculations
- `UBODataEncoder.java` - Verify it passes correct resolution to shaders
- `VulkanPipeline.java` - Verify ray tracing dispatch uses render dimensions

## Detailed Implementation Steps

### Phase 1: Verification and Diagnostics

1. **Add Diagnostic Logging** to verify G-buffer dimensions:
   - Enhance logging in `MixinRenderTarget.setupTextures()` to confirm G-buffer dimensions
   - Add logging in `VulkanPipeline.renderPostShadows()` to verify ray tracing input/output dimensions
   - Add logging to confirm motion vector and depth buffer dimensions

2. **Verify Current Behavior**:
   - Run with DLSS enabled and check if G-buffers are actually created at scaled resolution
   - Verify that ray tracing pass reads from these scaled G-buffers
   - Check if motion vectors and depth buffer are at render resolution

### Phase 2: Fix Identification and Resolution

Based on verification, identify any mismatches:

1. **If Ray Tracing Writes to Wrong Target**:
   - Modify `VulkanPipeline.java` to ensure ray tracing output binds to a G-buffer equivalent
   - Possibly repurpose one of the G-buffer slots for ray tracing output when DLSS active

2. **If Motion Vectors at Wrong Resolution**:
   - Modify `MotionVectorsGenerator.java` to use render resolution
   - Ensure `VulkanPipeline.java` passes correct dimensions

3. **If Depth Buffer at Wrong Resolution**:
   - Verify `DLSSDProcessor.initialize()` uses correct dimensions
   - Check depth image creation in native bridge

### Phase 3: Shader Pack Verification

1. **Review VulkaniteRT Shaders**:
   - Check for any hardcoded full-resolution assumptions
   - Verify texture sampling uses correct texture dimensions
   - Ensure any resolution-dependent math accounts for render vs output resolution

2. **Specific Checks**:
   - In `ray0.rgen`: Verify that any screen-space calculations use render resolution
   - In G-buffer shaders: Verify they write to correct render resolution targets
   - In ray tracing utility shaders: Verify UV and coordinate calculations

## Testing Strategy

### 1. Unit/Component Tests

- **ResolutionScaleManager Tests**: Verify scaling calculations produce correct aligned dimensions
- **MixinRenderTarget Tests**: Confirm G-buffers created at correct resolution when DLSS active
- **Dimension Consistency Tests**: Verify all DLSS-related buffers use consistent dimensions

### 2. Integration Tests

- **DLSS Input Validation**: 
  - Verify all inputs to DLSSD (noisy image, motion vectors, depth, G-buffers) are at same resolution
  - Confirm this resolution matches `ResolutionScaleManager.getRenderWidth()/Height()`
  
- **Output Validation**:
  - Verify DLSSD output is at full resolution
  - Verify final displayed image uses full resolution output

- **Quality Preset Testing**:
  - Test all DLSS quality presets (ULTRA_PERFORMANCE, PERFORMANCE, BALANCED, QUALITY, NATIVE)
  - Ensure scaling behaves correctly for each

### 3. Regression Tests

- **Non-DLSS Mode**: Verify rendering works correctly when DLSS disabled (all buffers at full resolution)
- **Visual Quality**: Ensure no degradation in image quality compared to downscaling approach
- **Performance**: Verify elimination of downscaling overhead improves performance

### 4. Debugging Enhancements

Add diagnostic modes to verify:
- G-buffer creation dimensions
- Ray tracing input/output buffer dimensions
- Motion vector buffer dimensions
- Depth buffer dimensions
- DLSS input/output buffer dimensions

## Potential Risks and Mitigations

### Risk 1: Inconsistent Resolution Usage
**Description**: Some pipeline components use render resolution while others use output resolution, causing mismatches.
**Mitigation**: 
- Centralize resolution logic in ResolutionScaleManager
- Add validation checks that assert dimension consistency
- Comprehensive logging to detect mismatches early

### Risk 2: Shader Compatibility Issues
**Description**: Shaders may have hardcoded assumptions about resolution or texture dimensions.
**Mitigation**:
- Audit all shaders for resolution-dependent code
- Make shaders resolution-agnostic where possible
- Pass resolution constants as uniforms when needed
- Test with various resolutions and scaling factors

### Risk 3: Performance Regression
**Description**: Changes might inadvertently increase memory bandwidth or reduce cache efficiency.
**Mitigation**:
- Profile memory usage before and after changes
- Verify that reduced G-buffer resolution actually saves bandwidth
- Ensure render target switches don't introduce pipeline stalls

### Risk 4: Incorrect Motion Vectors
**Description**: Motion vectors calculated at wrong resolution or with incorrect jitter handling.
**Mitigation**:
- Follow DLSS_JITTER_MOTION_FIX.md recommendations for motion vector generation
- Validate motion vectors represent pure geometric motion
- Test with static and moving cameras

### Risk 5: DLSSD Initialization Failures
**Description**: DLSSD might fail to initialize with render resolution buffers.
**Mitigation**:
- Verify DLSSD supports required resolutions and formats
- Ensure proper image usage flags (VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
- Test DLSSD initialization independently

## Implementation Plan Summary

### Core Principle
Leverage existing infrastructure in `MixinRenderTarget.java` that already creates G-buffers at scaled resolution when DLSS is active. The main work is ensuring all pipeline components consistently use these scaled buffers.

### Key Files to Focus On
1. `src/main/java/me/cortex/vulkanite/mixin/iris/MixinRenderTarget.java` - Verify G-buffer resolution logic
2. `src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java` - Verify ray tracing uses correct targets
3. `src/main/java/me/cortex/vulkanite/client/rendering/RenderPassExecutor.java` - Verify descriptor set bindings
4. `src/main/java/me/cortex/vulkanite/client/rendering/MotionVectorsGenerator.java` - Verify motion vector resolution
5. `src/main/java/me/cortex/vulkanite/client/rendering/UBODataEncoder.java` - Verify resolution and jitter handling
6. `run/shaderpacks/VulkaniteRT/shaders/` - Verify shader compatibility

### Success Criteria
- All DLSS inputs (noisy image, motion vectors, depth, G-buffers) use render resolution when DLSS active
- DLSSD output remains at full resolution for display
- No downscaling of G-buffers is needed in `DLSSBufferConverter`
- Image quality is maintained or improved
- Performance is improved due to eliminated downscaling overhead
- System works correctly in both DLSS and non-DLSS modes

This plan builds upon the existing LOWRES_RENDER_TARGET_PLAN.md but focuses on ensuring the implementation is complete and consistent across the entire pipeline, rather than just creating the low-resolution targets.
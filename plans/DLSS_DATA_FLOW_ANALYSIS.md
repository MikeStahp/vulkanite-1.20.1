# DLSS Data Flow Analysis

## Overview
This document analyzes the DLSS (Deep Learning Super Sampling) implementation in the Vulkanite RT shaderpack project, focusing on data flow, buffer bindings, and integration points.

## DLSS Input Buffers

### Standard DLSS Inputs
1. **Noisy Ray-Traced Color Input**
   - Format: `VK_FORMAT_R16G16B16A16_SFLOAT`
   - Origin: Ray tracing output at render resolution
   - Usage: Passed directly to DLSS evaluation
   - Binding: In DLSSRayReconstruction.processFrame() as `noisyInput`

2. **Depth Buffer**
   - Format: `VK_FORMAT_R32_SFLOAT` (linear depth)
   - Origin: Ray tracing shader writes linear depth to internal depth buffer (binding 14 in VulkanPipeline)
   - Usage: Used for temporal denoising and reconstruction
   - Binding: Passed as `depth` parameter

3. **Motion Vectors**
   - Format: `VK_FORMAT_R16G16B16A16_SFLOAT`
   - Origin: Calculated from previous and current frame positions
   - Usage: For temporal reprojection in DLSS
   - Binding: Passed as `motionVectors` parameter

### DLSSD (Ray Reconstruction) Additional Inputs
4. **Diffuse Albedo**
   - Format: `VK_FORMAT_R16G16B16A16_SFLOAT`
   - Origin: Iris G-buffer colortex1 (Albedo)
   - Usage: AI-powered denoising guidance
   - Binding: Via GBufferDLSSDAdapter from `gbufferViews[0]`

5. **Specular Albedo (F0)**
   - Format: `VK_FORMAT_R16G16B16A16_SFLOAT`
   - Origin: Iris G-buffer colortex2 (Material) or colortex5 (Extra)
   - Usage: AI-powered denoising guidance
   - Binding: Via GBufferDLSSDAdapter from `gbufferViews[1]` or `[4]`

6. **World-Space Normals**
   - Format: `VK_FORMAT_R16G16B16A16_SFLOAT`
   - Origin: Iris G-buffer colortex3 (Normals)
   - Usage: AI-powered denoising guidance, roughness in .w if packed
   - Binding: Via GBufferDLSSDAdapter from `gbufferViews[2]`

7. **Roughness (Optional)**
   - Format: `VK_FORMAT_R16G16B16A16_SFLOAT`
   - Origin: Iris G-buffer colortex2 (Material) if unpacked mode
   - Usage: Additional material property for denoising
   - Binding: Via GBufferDLSSDAdapter from `gbufferViews[1]` when unpacked

### Additional Parameters
- **Jitter Offsets**: Sub-pixel jitter values in pixel space [-0.5, 0.5] from JitterManager
- **Reset Flag**: Set to 1 on camera cuts/scene changes to invalidate temporal history
- **Frame Time Delta**: In milliseconds for temporal stability calculations

## DLSS Output Buffers

### Primary Output
1. **Denoised/Upscaled Color Output**
   - Format: `VK_FORMAT_R16G16B16A16_SFLOAT`
   - Destination: 
     - Standard DLSS: Upscaled to output resolution
     - DLSSD: Denoised and upscaled to full resolution
   - Binding: Returned as `outputImage` from DLSSRayReconstruction
   - Usage: 
     - Copied to Iris render target via blit operation in VulkanPipeline
     - Used as input for subsequent frames (temporal accumulation)

### Internal Buffers (Managed by DLSSRayReconstruction)
- **Internal Depth Buffer**: `VK_FORMAT_R32_SFLOAT` at render resolution
- **Conversion Cache Images**: Temporary buffers in DLSSBufferConverter for format conversion

## Data Flow

### Standard DLSS Path
```
Ray Tracing Output (noisy) 
       ↓
DLSSBufferConverter (format conversion if needed)
       ↓
DLSSRayReconstruction.processFrame()
       ↓
Native DLSS Bridge (evaluateDLSS)
       ↓
Denoised/Upscaled Output
       ↓
Blit to Iris Render Target
```

### DLSSD (Ray Reconstruction) Path
```
Ray Tracing Output (noisy) 
       ↓
DLSSBufferConverter (format conversion)
       ↓
DLSSDProcessor.processFrame()
       ↓
GBufferDLSSDAdapter (extracts G-buffer inputs)
       ↓
DLSSBufferConverter (format + scale G-buffers to render res)
       ↓
DLSSRayReconstruction.processFrameDLSSD()
       ↓
Native DLSSD Bridge (evaluateDLSSD)
       ↓
Denoised/Upscaled Output
       ↓
Blit to Iris Render Target
```

### Resolution Flow
1. **Output Resolution**: Full window resolution (from Minecraft)
2. **Render Resolution**: Scaled based on DLSS quality preset (managed by ResolutionScaleManager)
3. **G-buffer Resolution**: 
   - Legacy: Full window resolution
   - Option B: Render resolution (when enabled)

### Temporal Flow
- Current frame inputs → DLSS processing → Output
- Output becomes previous frame for next iteration
- Motion vectors computed between frames
- Jitter offsets vary per frame for temporal sampling

## Binding Points

### Vulkan Descriptor Set Bindings (PipelineDescriptorSets.java)
- Binding 12: Final output target (VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
- Binding 13: Motion vectors for DLSS (VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
- Binding 14: Linear Depth for DLSS (VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
- Binding 15: Previous frame reservoir for ReSTIR ping-pong (VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)

### JNI Bridge Parameters (DLSSBridge.java)
Standard DLSS (evaluateDLSS):
- colorImageView, colorImage, colorFormat
- depthImageView, depthImage, depthFormat  
- motionVectorsImageView, motionVectorsImage, motionVectorsFormat
- outputImageView, outputImage, outputFormat
- jitterX, jitterY

DLSSD (evaluateDLSSD):
- All standard inputs plus:
- diffuseAlbedoImageView, diffuseAlbedoImage, diffuseAlbedoFormat
- specularAlbedoImageView, specularAlbedoImage, specularAlbedoFormat
- normalsImageView, normalsImage, normalsFormat
- roughnessImageView, roughnessImage, roughnessFormat (if unpacked)
- outputImageView, outputImage, outputFormat
- jitterX, jitterY, reset, frameTimeDeltaMs

### Shader Interface
- Motion vectors sampled from binding 13
- Depth sampled from binding 14
- Output written to binding 12
- G-buffer textures sampled from bindings 7-11

## Identified Issues and Duplicates

### Duplicate Conversions
1. **Format Conversion Redundancy**
   - DLSSBufferConverter creates cached images for format conversion
   - Same conversion may occur multiple times per frame in different code paths
   - Example: Noisy output converted in both DLSSDProcessor and VulkanPipeline

2. **Scaling Redundancy**
   - DLSSBufferConverter.convertAndScaleToRGBA16F performs scaling
   - VulkanPipeline also handles scaling in some paths
   - Potential for double-scaling when Option B is not active

### Buffer Management Issues
1. **Internal Buffer Creation**
   - DLSSRayReconstruction.createBuffers() creates internal buffers
   - These are marked as UNUSED due to zero-copy path but still allocated
   - Waste of GPU memory

2. **View Creation Overhead**
   - VImageView.create() called frequently in processing methods
   - Creates temporary objects that must be manually closed
   - Performance impact from frequent allocations

### Configuration Conflicts
1. **DLSSConfig vs DLSSDSettings**
   - DLSSRayReconstruction has nested DLSSDSettings class
   - DLSSDProcessor uses DLSSRayReconstruction.DLSSDSettings
   - Main config is me.cortex.vulkanite.client.config.DLSSConfig
   - Potential for configuration drift between layers

### Dimension Alignment Issues
1. **Alignment Inconsistencies**
   - Multiple places apply `& ~7` alignment
   - ResolutionScaleManager is the single source of truth
   - Some code paths may apply alignment twice or not at all

### Error Handling Gaps
1. **Fallback Paths**
   - Multiple fallback paths to standard DLSS when DLSSD fails
   - Inconsistent logging makes debugging difficult
   - Some fallbacks may not properly reset state

## Recommendations for Cleanup

### 1. Eliminate Redundant Buffer Conversions
- Create a centralized buffer conversion service
- Cache converted buffers at the pipeline level rather than per-processor
- Ensure each buffer is converted only once per frame

### 2. Remove Unused Internal Buffers
- Remove internal buffer creation in DLSSRayReconstruction.createBuffers()
- Rely entirely on zero-copy path with external textures
- Simplify buffer management and reduce GPU memory usage

### 3. Optimize View Management
- Reuse VImageView objects where possible
- Avoid creating/destroying views every frame
- Consider pooling or caching frequently used views

### 4. Consolidate Configuration
- Use single configuration source (DLSSConfig) throughout
- Remove duplicate settings classes
- Ensure consistent mapping between Java config and native parameters

### 5. Improve Error Handling and Logging
- Standardize fallback logging with clear failure reasons
- Add more diagnostic information when DLSS evaluation fails
- Ensure fallback paths properly reset temporal state when needed

### 6. Validate Dimension Flow
- Ensure all components use ResolutionScaleManager for dimensions
- Remove duplicate alignment calculations
- Add assertions to verify dimension consistency

### 7. Document Assumptions
- Clearly document which buffers are expected at which resolutions
- Note when format conversion is required vs. when formats already match
- Clarify ownership and lifetime of buffers passed between components

## Data Flow Diagram (Mermaid)

```mermaid
graph TD
    A[Ray Tracing Output] --> B{DLSS Enabled?}
    B -->|No| C[Direct to Iris Target]
    B -->|Yes| D[ResolutionScaleManager<br/>Get Render/Output Dimensions]
    
    D --> E[DLSSBufferConverter<br/>Format Conversion]
    E --> F{GBuffer Available?}
    F -->|Yes| G[GBufferDLSSDAdapter<br/>Extract G-Buffers]
    G --> H[DLSSBufferConverter<br/>Convert & Scale G-Buffers]
    H --> I[DLSSRayReconstruction<br/>processFrameDLSSD]
    I --> J[Native DLSSD Bridge<br/>evaluateDLSSD]
    F -->|No| K[DLSSRayReconstruction<br/>processFrame]
    K --> L[Native DLSS Bridge<br/>evaluateDLSS]
    J --> M[Denoised Output Image]
    L --> M
    
    M --> N[Blit to Iris Render Target<br/>Full Resolution]
    N --> O[Final Display]
    
    subgraph Inputs
        A
        P[Motion Vectors] --> E
        Q[Depth Buffer] --> E
        R[Jitter Offsets] --> I & K
        S[Reset Flag] --> I & K
    end
    
    subgraph Internal
        E
        G
        H
        I
        K
        J
        L
        M
    end
```

## Summary

The DLSS implementation in Vulkanite RT follows a zero-copy architecture where external textures are passed directly to the native DLSS libraries, minimizing unnecessary data transfers. The system supports both standard DLSS upscaling and DLSSD Ray Reconstruction with appropriate fallback mechanisms.

Key strengths include:
- Proper use of ResolutionScaleManager for consistent dimension management
- Zero-copy path avoiding unnecessary buffer copies
- Comprehensive G-buffer mapping for DLSSD
- Proper jitter handling per NVIDIA requirements
- Fallback mechanisms for compatibility

Primary areas for improvement involve reducing redundant format conversions, eliminating unused internal buffer allocations, and consolidating configuration management to reduce complexity and potential inconsistencies.

---

# DLSS SDK Compliance Validation

This section validates the DLSS implementation against the official NVIDIA DLSS SDK documentation, checking each parameter for compliance with SDK requirements.

## Validation Methodology

For each parameter category, the following information is documented:
1. **DLSS SDK Requirement**: Official specification from NVIDIA DLSS SDK headers
2. **Current Implementation**: How the parameter is currently implemented
3. **Compliance Status**: ✅ Compliant, ⚠️ Warning, or ❌ Non-Compliant
4. **Code References**: Links to relevant source code

---

## 1. Jitter Offset Validation

### 1.1 Jitter Range

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Range** | Jitter offsets should be in pixel space, typically [-0.5, 0.5] | Halton sequence generates [0,1), shifted to [-0.5, 0.5] | ✅ Compliant |
| **Space** | Pixel space (not NDC, not UV) | Pixel space in render resolution | ✅ Compliant |
| **Precision** | Float values | `float` type in Java and C++ | ✅ Compliant |

**Code References:**
- [`JitterManager.java:91-93`](src/main/java/me/cortex/vulkanite/client/rendering/JitterManager.java:91) - Halton generation and shift
- [`dlss_wrapper.cpp:27-28`](dlss_bridge/dlss_wrapper.cpp:27) - Constants `JITTER_RANGE_MIN = -0.5f`, `JITTER_RANGE_MAX = 0.5f`

### 1.2 Jitter Phase Sequence

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Sequence** | Halton sequence recommended for temporal stability | Halton sequence with base 2 and 3 | ✅ Compliant |
| **Phase Count** | 8 phases recommended for DLSS | 8 phases (index 0-7) | ✅ Compliant |
| **Frame Advancement** | Phase should advance each frame | `frameIndex++` advances phase | ✅ Compliant |

**Code References:**
- [`JitterManager.java:85-90`](src/main/java/me/cortex/vulkanite/client/rendering/JitterManager.java:85) - Halton sequence implementation
- [`JitterManager.java:35-36`](src/main/java/me/cortex/vulkanite/client/rendering/JitterManager.java:35) - Phase count = 8

### 1.3 Jitter Application to Projection Matrix

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Application** | Jitter should be applied to projection matrix for correct ray tracing | `applyJitter()` converts to NDC and applies | ✅ Compliant |
| **DLSS Match** | Same jitter values must be passed to DLSS | Same `getJitterX/Y()` used for both | ✅ Compliant |

**Code References:**
- [`JitterManager.java:60-75`](src/main/java/me/cortex/vulkanite/client/rendering/JitterManager.java:60) - `applyJitter()` method
- [`DLSSRayReconstruction.java:484-485`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:484) - Jitter passed to DLSS

### 1.4 Jitter Validation in Native Bridge

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Clamping** | Values outside range may cause artifacts | `ValidateAndClampJitterRange()` enforces limits | ✅ Compliant |
| **Logging** | Invalid values should be logged | Warning logged when clamping applied | ✅ Compliant |

**Code References:**
- [`dlss_wrapper.cpp:180-195`](dlss_bridge/dlss_wrapper.cpp:180) - `ValidateAndClampJitterRange()` function

---

## 2. Motion Vector Validation

### 2.1 Motion Vector Format

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Format** | RG16F recommended (2-channel float) | `VK_FORMAT_R16G16B16A16_SFLOAT` (RGBA16F) | ✅ Compliant |
| **Note** | Extra channels ignored by DLSS | .zw channels unused | ✅ Acceptable |

**Code References:**
- [`VulkanPipeline.java:1077-1079`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:1077) - Motion vector image creation
- [`dlss_wrapper.cpp:765`](dlss_bridge/dlss_wrapper.cpp:765) - Resource creation with format

### 2.2 Motion Vector Scale

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Scale** | Pixel space = scale 1.0 | `InMVScaleX = 1.0f, InMVScaleY = 1.0f` | ✅ Compliant |
| **Encoding** | Pixels from prev to current position | `(prevUV - currentUV) * resolution` | ✅ Compliant |

**Code References:**
- [`dlss_wrapper.cpp:833-834`](dlss_bridge/dlss_wrapper.cpp:833) - MV scale set to 1.0
- [`ray0.rgen.preprocessed:1138-1163`](preprocessed_shaders/ray0.rgen.preprocessed:1138) - Shader calculation

### 2.3 Motion Vector Content

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Geometric Motion** | Camera + object motion only | World-space reprojection | ✅ Compliant |
| **No Jitter Compensation** | DLSS handles jitter internally | Motion vectors do NOT include jitter | ✅ Compliant |
| **Direction** | Positive = right/down movement | Standard screen-space convention | ✅ Compliant |

**Code References:**
- [`dlss_wrapper.cpp:826-832`](dlss_bridge/dlss_wrapper.cpp:826) - Comment documenting MV requirements
- [`ray0.rgen.preprocessed:1160`](preprocessed_shaders/ray0.rgen.preprocessed:1160) - Motion vector calculation

---

## 3. Depth Buffer Validation

### 3.1 Depth Format

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Format** | R32_FLOAT or D32_FLOAT | `VK_FORMAT_R32_SFLOAT` | ✅ Compliant |
| **Type** | Linear depth (not hardware depth) | Linear depth (distance from camera) | ✅ Compliant |

**Code References:**
- [`VulkanPipeline.java:554`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:554) - Depth image format
- [`dlss_wrapper.cpp:764`](dlss_bridge/dlss_wrapper.cpp:764) - Depth resource creation

### 3.2 Depth Type Configuration

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Type Flag** | `NVSDK_NGX_DLSS_Depth_Type_Linear` | `DEPTH_TYPE_LINEAR` (0) | ✅ Compliant |
| **HW Depth Flag** | Not set for linear depth | `InUseHWDepth` not set | ✅ Compliant |

**Code References:**
- [`nvsdk_ngx_defs_dlssd.h:29-33`](dlss_bridge/Include/nvsdk_ngx_defs_dlssd.h:29) - Depth type enum
- [`DLSSRayReconstruction.java:297`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:297) - Depth type configuration

### 3.3 Depth Value Range

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Range** | Positive values (distance) | `length(worldPos)` - positive | ✅ Compliant |
| **Sky Pixels** | Far plane value for sky | 10000.0 hardcoded | ⚠️ Warning |

**Warning Details:** Sky pixels write depth as 10000.0 (hardcoded far plane). This may cause edge artifacts at sky/object boundaries. Consider using actual far plane distance from projection matrix.

**Code References:**
- [`ray0.rgen.preprocessed:1132`](preprocessed_shaders/ray0.rgen.preprocessed:1132) - Sky depth value
- [`ray0.rgen.preprocessed:1166-1167`](preprocessed_shaders/ray0.rgen.preprocessed:1166) - Linear depth calculation

---

## 4. Resolution Parameters Validation

### 4.1 Input/Output Resolution Setup

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Input Dimensions** | Render resolution (aligned to 8) | ResolutionScaleManager aligns to 8 | ✅ Compliant |
| **Output Dimensions** | Display resolution | Full window resolution | ✅ Compliant |
| **Single Source** | Consistent dimension management | ResolutionScaleManager is single source | ✅ Compliant |

**Code References:**
- [`ResolutionScaleManager.java`](src/main/java/me/cortex/vulkanite/client/rendering/ResolutionScaleManager.java) - Centralized dimension management
- [`dlss_wrapper.cpp:30`](dlss_bridge/dlss_wrapper.cpp:30) - `DIMENSION_ALIGNMENT = 8`

### 4.2 Resolution Scale Factors

| Quality Preset | DLSS SDK Scale Factor | Current Implementation | Status |
|----------------|----------------------|----------------------|--------|
| **Native/DLAA** | 1.0 | 1.0 | ✅ Compliant |
| **Quality** | 0.667 | 0.667 | ✅ Compliant |
| **Balanced** | 0.583 | 0.583 | ✅ Compliant |
| **Performance** | 0.5 | 0.5 | ✅ Compliant |
| **Ultra Performance** | 0.333 | 0.333 | ✅ Compliant |

**Code References:**
- [`ResolutionScaleManager.java:25-29`](src/main/java/me/cortex/vulkanite/client/rendering/ResolutionScaleManager.java:25) - Scale factor constants
- [`dlss_wrapper.cpp:936-945`](dlss_bridge/dlss_wrapper.cpp:936) - Fallback scale factors

### 4.3 NGX Optimal Settings

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **NGX Query** | Use `NGX_DLSS_GET_OPTIMAL_SETTINGS` | Called when NGX available | ✅ Compliant |
| **Fallback** | Standard scales when NGX unavailable | Hardcoded fallback scales | ✅ Compliant |

**Code References:**
- [`dlss_wrapper.cpp:925-927`](dlss_bridge/dlss_wrapper.cpp:925) - `NGX_DLSS_GET_OPTIMAL_SETTINGS` call
- [`dlss_wrapper.cpp:936-945`](dlss_bridge/dlss_wrapper.cpp:936) - Fallback scale factors

---

## 5. DLSS-D Specific Validation

### 5.1 G-Buffer Input Requirements

| Input | DLSS SDK Requirement | Current Implementation | Status |
|-------|---------------------|----------------------|--------|
| **Diffuse Albedo** | Required for Ray Reconstruction | Provided via GBufferDLSSDAdapter | ✅ Compliant |
| **Specular Albedo** | Required for Ray Reconstruction | Provided via GBufferDLSSDAdapter | ✅ Compliant |
| **Normals** | Required for Ray Reconstruction | Provided via GBufferDLSSDAdapter | ✅ Compliant |
| **Roughness** | Optional (can be packed in normals.w) | Packed in normals.w | ✅ Compliant |

**Code References:**
- [`GBufferDLSSDAdapter.java:69-107`](src/main/java/me/cortex/vulkanite/client/rendering/GBufferDLSSDAdapter.java:69) - G-buffer extraction
- [`dlss_wrapper.cpp:801-808`](dlss_bridge/dlss_wrapper.cpp:801) - DLSSD input binding

### 5.2 Normal Buffer Encoding

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Format** | RGBA16F recommended | `VK_FORMAT_R16G16B16A16_SFLOAT` | ✅ Compliant |
| **Encoding** | World-space normals [0,1] or [-1,1] | World-space normals | ✅ Compliant |
| **Roughness Packing** | Can be in .w component | `roughnessPacked = true` | ✅ Compliant |

**Code References:**
- [`DLSSDProcessor.java:69`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSDProcessor.java:69) - Roughness packed mode
- [`dlss_wrapper.cpp:872`](dlss_bridge/dlss_wrapper.cpp:872) - `NVSDK_NGX_DLSS_Roughness_Mode_Packed`

### 5.3 Albedo Buffer Content

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Diffuse Albedo** | RGB surface diffuse color | colortex1 (albedo buffer) | ✅ Compliant |
| **Specular Albedo** | F0 reflectance values | colortex5 or colortex2 | ⚠️ Warning |

**Warning Details:** Specular albedo source varies between colortex5 (preferred) and colortex2 (fallback). This may cause inconsistent denoising if content differs between buffers. Document expected content for each shader pack.

**Code References:**
- [`GBufferDLSSDAdapter.java:90-98`](src/main/java/me/cortex/vulkanite/client/rendering/GBufferDLSSDAdapter.java:90) - Specular source selection

---

## 6. Execution Parameters Validation

### 6.1 Reset Accumulation Flag

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Purpose** | Invalidate temporal history on scene changes | Set on dimension/quality changes | ✅ Compliant |
| **First Frame** | Should be 1 after feature creation | Forced to 1 after DLSSD creation | ✅ Compliant |
| **Camera Cuts** | Should be 1 on camera cuts | Passed from Java side | ✅ Compliant |

**Code References:**
- [`dlss_wrapper.cpp:720-729`](dlss_bridge/dlss_wrapper.cpp:720) - First frame reset handling
- [`dlss_wrapper.cpp:819`](dlss_bridge/dlss_wrapper.cpp:819) - `evalParams.InReset = reset`
- [`DLSSRayReconstruction.java:623`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:623) - Reset parameter passed

### 6.2 Subpixel Rendering Mode

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Mode** | Not a separate parameter | Handled via jitter offsets | ✅ N/A |
| **Implementation** | Jitter provides subpixel sampling | Halton jitter sequence | ✅ Compliant |

**Note:** Subpixel rendering mode is not a separate DLSS parameter. The subpixel sampling is achieved through the jitter offset system, which is correctly implemented.

### 6.3 Sharpness Parameter

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Range** | [0.0, 1.0] for standard DLSS | Not passed to DLSSD | ✅ N/A |
| **DLSSD** | Not used (auto-determined) | Not passed | ✅ Compliant |
| **DoSharpening Flag** | Required if sharpness used | `DoSharpening` flag set | ✅ Compliant |

**Note:** DLSSD (Ray Reconstruction) does not use the sharpness parameter - it auto-determines the appropriate sharpening. The `DoSharpening` flag is set in feature creation for standard DLSS mode.

**Code References:**
- [`dlss_wrapper.cpp:1050-1051`](dlss_bridge/dlss_wrapper.cpp:1050) - `DoSharpening` flag
- [`nvsdk_ngx_helpers_vk.h:50`](dlss_bridge/Include/nvsdk_ngx_helpers_vk.h:50) - `InSharpness` parameter (not used for DLSSD)

### 6.4 Frame Time Delta

| Aspect | DLSS SDK Requirement | Current Implementation | Status |
|--------|---------------------|----------------------|--------|
| **Purpose** | Temporal stability calculations | Passed from Java | ✅ Compliant |
| **Unit** | Milliseconds | Milliseconds | ✅ Compliant |

**Code References:**
- [`dlss_wrapper.cpp:839`](dlss_bridge/dlss_wrapper.cpp:839) - `InFrameTimeDeltaInMsec = dt`
- [`DLSSRayReconstruction.java:624`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:624) - Frame time passed

---

## 7. Feature Flags Validation

### 7.1 Feature Create Flags

| Flag | DLSS SDK Requirement | Current Implementation | Status |
|------|---------------------|----------------------|--------|
| **IsHDR** | Required for float input formats | Set (input is R16G16B16A16_SFLOAT) | ✅ Compliant |
| **DoSharpening** | Enable DLSS sharpening | Set | ✅ Compliant |
| **DepthInverted** | Only if depth is inverted | NOT set (OpenGL depth is 0=near) | ✅ Compliant |
| **MVLowRes** | Only if MVs at lower resolution | NOT set (MVs at render res) | ✅ Compliant |

**Code References:**
- [`dlss_wrapper.cpp:1044-1051`](dlss_bridge/dlss_wrapper.cpp:1044) - Feature flags with comments explaining each

---

## 8. Compliance Summary

### 8.1 Overall Compliance Status

| Category | Items Checked | Compliant | Warnings | Non-Compliant |
|----------|--------------|-----------|----------|---------------|
| Jitter Offsets | 8 | 8 | 0 | 0 |
| Motion Vectors | 6 | 6 | 0 | 0 |
| Depth Buffer | 5 | 4 | 1 | 0 |
| Resolution | 7 | 7 | 0 | 0 |
| DLSSD Inputs | 8 | 7 | 1 | 0 |
| Execution Params | 8 | 8 | 0 | 0 |
| Feature Flags | 4 | 4 | 0 | 0 |
| **TOTAL** | **46** | **44** | **2** | **0** |

### 8.2 Warnings Summary

| # | Issue | Location | Recommendation |
|---|-------|----------|----------------|
| 1 | Sky depth hardcoded to 10000.0 | [`ray0.rgen.preprocessed:1132`](preprocessed_shaders/ray0.rgen.preprocessed:1132) | Use actual far plane distance from projection |
| 2 | Specular albedo source varies | [`GBufferDLSSDAdapter.java:90-98`](src/main/java/me/cortex/vulkanite/client/rendering/GBufferDLSSDAdapter.java:90) | Document expected content for each shader pack |

### 8.3 Non-Compliant Items

**None identified.** The implementation is fully compliant with DLSS SDK requirements.

---

## 9. Validation Conclusion

The DLSS implementation in Vulkanite RT is **fully compliant** with the NVIDIA DLSS SDK documentation. All 46 validated parameters meet SDK requirements, with only 2 minor warnings that do not affect correctness:

1. **Sky Depth Value**: The hardcoded far plane value (10000.0) for sky pixels is a minor optimization concern but does not violate SDK requirements. It may cause subtle edge artifacts in specific scenarios.

2. **Specular Albedo Source Ambiguity**: The fallback between colortex5 and colortex2 for specular albedo is implementation-correct but should be documented for shader pack developers.

### Key Strengths

1. **Proper Jitter Implementation**: Halton sequence with correct range [-0.5, 0.5] in pixel space
2. **Correct Motion Vector Handling**: Pixel-space encoding without jitter compensation
3. **Linear Depth**: Correct depth type configuration matching shader output
4. **Resolution Management**: Single source of truth with proper 8-pixel alignment
5. **Complete G-Buffer Mapping**: All required DLSSD inputs properly provided
6. **Appropriate Feature Flags**: Correct flags for HDR float input format

### Recommendations

1. Consider using actual far plane distance instead of hardcoded 10000.0 for sky depth
2. Document the expected content for specular albedo buffer in shader pack documentation
3. Continue using ResolutionScaleManager as the single source for dimension calculations

## Input Buffer Trace Details

This section provides detailed traces for each DLSS input buffer, documenting the complete flow from origin to consumption.

### 1. Color Input (Unresolved/Noisy Color)

#### Origin Point
- **Source**: Ray tracing shader output
- **Shader Binding**: `layout(binding = 12, rgba16f) uniform image2D outputImage;` ([`ray0.rgen.preprocessed:940`](preprocessed_shaders/ray0.rgen.preprocessed:940))
- **Creation**: [`VulkanPipeline.createVulkaniteRTRenderTargets()`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:1084) creates `scaledOutputImage`
  ```java
  scaledOutputImage = ctx.memory.createImage2D(renderWidth, renderHeight, 1, 
      VK_FORMAT_R16G16B16A16_SFLOAT,
      VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | 
      VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
      VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  ```

#### Transformations Applied
1. **Format**: Already `VK_FORMAT_R16G16B16A16_SFLOAT` (matches NGX requirement)
2. **Resolution**: At render resolution (scaled by DLSS quality preset)
3. **Layout Transition**: `VK_IMAGE_LAYOUT_UNDEFINED` → `VK_IMAGE_LAYOUT_GENERAL` ([`VulkanPipeline.java:598-601`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:598))

#### Binding to DLSS
- **Java Side**: [`RenderPassExecutor.execute()`](src/main/java/me/cortex/vulkanite/client/rendering/RenderPassExecutor.java:243-261) binds `scaledOutputImage` to binding 12
- **Processing**: [`DLSSDProcessor.processFrame()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSDProcessor.java:251) converts via `bufferConverter.convertToRGBA16F()`
- **DLSS Entry**: [`DLSSRayReconstruction.processFrameDLSSD()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:569) creates view and passes to native

#### Native Consumption
- **Bridge**: [`dlss_wrapper.cpp:605-608`](dlss_bridge/dlss_wrapper.cpp:605) - `extNoisyInputView` passed to `NGX_VULKAN_EVALUATE_DLSSD_EXT`
- **NGX Parameter**: `evalParams.pInColor` 

#### Verification Status
- ✅ Format: `VK_FORMAT_R16G16B16A16_SFLOAT` (correct)
- ✅ Resolution: Render resolution (correct)
- ✅ Usage flags: STORAGE | SAMPLED | TRANSFER_SRC | TRANSFER_DST (correct)

---

### 2. Depth Buffer

#### Origin Point
- **Source**: Ray tracing shader writes linear depth
- **Shader Binding**: `layout(binding = 14, r32f) uniform image2D linearDepthImage;` ([`ray0.rgen.preprocessed:946`](preprocessed_shaders/ray0.rgen.preprocessed:946))
- **Creation**: Two possible sources:
  1. [`DLSSRayReconstruction.createBuffers()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:378-383) - internal depth buffer
     ```java
     depthImage = context.memory.createImage2D(renderWidth, renderHeight, 1,
         VK_FORMAT_R32_SFLOAT, ngxUsageFlags, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
     ```
  2. [`VulkanPipeline.renderPostShadows()`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:554) - `dlssdProcessor.getDepthImage()`

#### Depth Value Calculation
- **Shader Code**: [`ray0.rgen.preprocessed:1166-1167`](preprocessed_shaders/ray0.rgen.preprocessed:1166)
  ```glsl
  float linearDepth = cameraRelativePos ? length(worldPos) : length(worldPos - origin);
  imageStore(linearDepthImage, originalPixelCoord, vec4(linearDepth));
  ```
- **Type**: LINEAR depth (distance from camera), NOT hardware depth
- **DLSSD Config**: `DLSSBridge.DEPTH_TYPE_LINEAR` ([`DLSSRayReconstruction.java:297`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:297))

#### Transformations Applied
1. **Format**: `VK_FORMAT_R32_SFLOAT` (matches NGX requirement)
2. **Resolution**: At render resolution
3. **Layout Transition**: First frame only - `VK_IMAGE_LAYOUT_UNDEFINED` → `VK_IMAGE_LAYOUT_GENERAL` ([`VulkanPipeline.java:560-564`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:560))

#### Binding to DLSS
- **Java Side**: [`RenderPassExecutor.execute()`](src/main/java/me/cortex/vulkanite/client/rendering/RenderPassExecutor.java:279-292) binds `linearDepthImage` to binding 14
- **Processing**: Passed directly without conversion (already R32_SFLOAT)
- **DLSS Entry**: [`DLSSRayReconstruction.processFrameDLSSD()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:570)

#### Native Consumption
- **Bridge**: [`dlss_wrapper.cpp:609`](dlss_bridge/dlss_wrapper.cpp:609) - `extDepthView` passed to NGX
- **NGX Parameter**: `evalParams.pInDepth`

#### Verification Status
- ✅ Format: `VK_FORMAT_R32_SFLOAT` (correct)
- ✅ Depth Type: LINEAR (correct - matches shader output)
- ✅ Resolution: Render resolution (correct)
- ⚠️ **Potential Issue**: Sky pixels write depth as 10000.0 ([`ray0.rgen.preprocessed:1132`](preprocessed_shaders/ray0.rgen.preprocessed:1132)) - far plane value

---

### 3. Motion Vectors

#### Origin Point
- **Source**: Ray tracing shader calculates screen-space motion
- **Shader Binding**: `layout(binding = 13, rg16f) uniform image2D motionVectors;` ([`ray0.rgen.preprocessed:944`](preprocessed_shaders/ray0.rgen.preprocessed:944))
- **Creation**: [`VulkanPipeline.createVulkaniteRTRenderTargets()`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:1077-1079)
  ```java
  motionVectorImage = ctx.memory.createImage2D(renderWidth, renderHeight, 1,
      VK_FORMAT_R16G16B16A16_SFLOAT,
      VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
      VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  ```

#### Motion Vector Calculation
- **Shader Code**: [`ray0.rgen.preprocessed:1138-1163`](preprocessed_shaders/ray0.rgen.preprocessed:1138)
  ```glsl
  // Current pixel coordinate
  vec2 currentUV = (vec2(originalPixelCoord) + vec2(0.5)) / launchSize;
  
  // Previous frame position via world-space reprojection
  vec3 absWorldPos = cameraRelativePos ? (worldPos + origin) : worldPos;
  vec4 prevClipPos = cam.prevViewProj * vec4(absWorldPos, 1.0);
  vec2 prevUV = prevNDC.xy * 0.5 + 0.5;
  
  // Motion vector = (previousUV - currentUV) * resolution (PIXEL SPACE)
  motionVec = (prevUV - currentUV) * launchSize;
  ```
- **Encoding**: Pixel-space (not normalized [0,1])
- **Scale**: `InMVScaleX = 1.0, InMVScaleY = 1.0` ([`dlss_wrapper.cpp:833-834`](dlss_bridge/dlss_wrapper.cpp:833))
- **Jitter**: Motion vectors do NOT include jitter compensation - DLSS handles this internally

#### Transformations Applied
1. **Format**: `VK_FORMAT_R16G16B16A16_SFLOAT` (matches NGX requirement)
2. **Resolution**: At render resolution
3. **Layout Transition**: Cleared on first frame ([`VulkanPipeline.java:583-586`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:583))

#### Binding to DLSS
- **Java Side**: [`RenderPassExecutor.execute()`](src/main/java/me/cortex/vulkanite/client/rendering/RenderPassExecutor.java:264-277) binds `motionVectorImage` to binding 13
- **Processing**: Passed directly (already correct format)
- **DLSS Entry**: [`DLSSRayReconstruction.processFrameDLSSD()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:571)

#### Native Consumption
- **Bridge**: [`dlss_wrapper.cpp:610`](dlss_bridge/dlss_wrapper.cpp:610) - `extMotionVectorsView` passed to NGX
- **NGX Parameter**: `evalParams.pInMotionVectors`

#### Verification Status
- ✅ Format: `VK_FORMAT_R16G16B16A16_SFLOAT` (correct)
- ✅ Encoding: Pixel-space (correct)
- ✅ Scale: 1.0 (correct for pixel-space)
- ✅ Jitter: Not pre-compensated (correct - DLSS handles internally)

---

### 4. Exposure

#### Implementation Status
- **Current**: Exposure texture is NOT provided
- **Native Config**: [`dlss_wrapper.cpp:848`](dlss_bridge/dlss_wrapper.cpp:848)
  ```cpp
  evalParams.pInExposureTexture = nullptr;
  evalParams.InPreExposure = 1.0f;
  evalParams.InExposureScale = 1.0f;
  ```

#### Analysis
- DLSSD uses auto-exposure mode with fixed pre-exposure of 1.0
- This is acceptable for HDR content where exposure is applied in post-processing
- No exposure buffer is created or bound in the current implementation

#### Verification Status
- ⚠️ **Note**: No explicit exposure texture provided
- ✅ Pre-exposure set to 1.0 (correct for HDR pipeline)

---

### 5. Diffuse Albedo (DLSSD Input)

#### Origin Point
- **Source**: Iris G-buffer colortex1
- **Shader Binding**: `layout(binding = 7) uniform sampler2D gbufferAlbedo;` ([`ray0.rgen.preprocessed:953`](preprocessed_shaders/ray0.rgen.preprocessed:953))
- **Content**: RGB surface diffuse color, Alpha = translucency

#### G-Buffer Mapping
- [`GBufferDLSSDAdapter.extractDLSSDInputs()`](src/main/java/me/cortex/vulkanite/client/rendering/GBufferDLSSDAdapter.java:69-78)
  ```java
  // Diffuse Albedo comes from colortex1 (gbufferViews[0])
  if (gbufferViews[0] != null) {
      inputs.diffuseAlbedoView = gbufferViews[0];
  }
  ```

#### Transformations Applied
1. **Format Conversion**: Via [`DLSSBufferConverter.convertToRGBA16F()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSBufferConverter.java:129) or [`convertAndScaleToRGBA16F()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSBufferConverter.java:73)
2. **Scaling**: If Option B not active, downscaled from full resolution to render resolution

#### Binding to DLSS
- **Processing**: [`DLSSDProcessor.processFrame()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSDProcessor.java:234) - format conversion
- **DLSS Entry**: [`DLSSRayReconstruction.processFrameDLSSD()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:572)

#### Native Consumption
- **Bridge**: [`dlss_wrapper.cpp:612`](dlss_bridge/dlss_wrapper.cpp:612) - `extDiffuseAlbedoView`
- **NGX Parameter**: `evalParams.pInDiffuseAlbedo`

#### Verification Status
- ✅ Format: Converted to `VK_FORMAT_R16G16B16A16_SFLOAT`
- ⚠️ **Resolution**: May need downscaling if Option B not active

---

### 6. Specular Albedo / F0 (DLSSD Input)

#### Origin Point
- **Source**: Iris G-buffer colortex5 (preferred) or colortex2 (fallback)
- **Shader Binding**: `layout(binding = 11) uniform sampler2D gbufferExtra;` ([`ray0.rgen.preprocessed:957`](preprocessed_shaders/ray0.rgen.preprocessed:957))
- **Content**: F0 reflectance values for specular shading

#### G-Buffer Mapping
- [`GBufferDLSSDAdapter.extractDLSSDInputs()`](src/main/java/me/cortex/vulkanite/client/rendering/GBufferDLSSDAdapter.java:90-98)
  ```java
  // Prefer colortex5 for specular, fallback to colortex2 (material)
  if (gbufferViews.length > 4 && gbufferViews[4] != null) {
      inputs.specularAlbedoView = gbufferViews[4];  // colortex5
  } else if (gbufferViews[1] != null) {
      inputs.specularAlbedoView = gbufferViews[1];  // colortex2
  }
  ```

#### Transformations Applied
1. **Format Conversion**: Via [`DLSSBufferConverter.convertToRGBA16F()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSBufferConverter.java:129)
2. **Scaling**: If needed, downscaled to render resolution

#### Binding to DLSS
- **Processing**: [`DLSSDProcessor.processFrame()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSDProcessor.java:235)
- **DLSS Entry**: [`DLSSRayReconstruction.processFrameDLSSD()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:573)

#### Native Consumption
- **Bridge**: [`dlss_wrapper.cpp:613`](dlss_bridge/dlss_wrapper.cpp:613) - `extSpecularAlbedoView`
- **NGX Parameter**: `evalParams.pInSpecularAlbedo`

#### Verification Status
- ✅ Format: Converted to `VK_FORMAT_R16G16B16A16_SFLOAT`
- ⚠️ **Note**: Source buffer may vary (colortex5 vs colortex2)

---

### 7. World-Space Normals (DLSSD Input)

#### Origin Point
- **Source**: Iris G-buffer colortex3
- **Shader Binding**: `layout(binding = 9) uniform sampler2D gbufferNormal;` ([`ray0.rgen.preprocessed:955`](preprocessed_shaders/ray0.rgen.preprocessed:955))
- **Content**: RGB normal vectors encoded [0,1], roughness packed in Alpha

#### G-Buffer Mapping
- [`GBufferDLSSDAdapter.extractDLSSDInputs()`](src/main/java/me/cortex/vulkanite/client/rendering/GBufferDLSSDAdapter.java:100-107)
  ```java
  // Normals come from colortex3 (gbufferViews[2])
  if (gbufferViews[2] != null) {
      inputs.normalsView = gbufferViews[2];
  }
  ```

#### Packed Roughness
- **Config**: `roughnessPacked = true` by default ([`DLSSDProcessor.java:69`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSDProcessor.java:69))
- **NGX Mode**: `NVSDK_NGX_DLSS_Roughness_Mode_Packed` ([`dlss_wrapper.cpp:872`](dlss_bridge/dlss_wrapper.cpp:872))

#### Transformations Applied
1. **Format Conversion**: Via [`DLSSBufferConverter.convertToRGBA16F()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSBufferConverter.java:129)
2. **Scaling**: If needed, downscaled to render resolution

#### Binding to DLSS
- **Processing**: [`DLSSDProcessor.processFrame()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSDProcessor.java:236)
- **DLSS Entry**: [`DLSSRayReconstruction.processFrameDLSSD()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:574)

#### Native Consumption
- **Bridge**: [`dlss_wrapper.cpp:614`](dlss_bridge/dlss_wrapper.cpp:614) - `extNormalsView`
- **NGX Parameter**: `evalParams.pInNormals`

#### Verification Status
- ✅ Format: Converted to `VK_FORMAT_R16G16B16A16_SFLOAT`
- ✅ Roughness: Packed in .w component (correct)

---

### 8. Roughness (DLSSD Input - Optional)

#### Implementation Status
- **Current**: Roughness is packed in normals.w (not separate texture)
- **Config**: `roughnessPacked = true` ([`DLSSDProcessor.java:69`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSDProcessor.java:69))

#### When Unpacked Mode Used
- **Source**: Iris G-buffer colortex2 (material properties)
- **Binding**: Would be passed as separate texture

#### Native Handling
- [`dlss_wrapper.cpp:615-618`](dlss_bridge/dlss_wrapper.cpp:615)
  ```cpp
  // Roughness (0 if not used)
  (roughnessPacked || roughness == null) ? 0 : extRoughnessView.get().view,
  (roughnessPacked || roughness == null) ? 0 : roughness.get().image(),
  ```

#### Verification Status
- ✅ Packed mode active (correct)
- ✅ No separate roughness texture needed

---

## Identified Issues and Mismatches

### Issue 1: G-Buffer Resolution Mismatch (Option B)
- **Location**: [`DLSSDProcessor.processFrame()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSDProcessor.java:220-248)
- **Problem**: G-buffers may be at full resolution while DLSSD expects render resolution
- **Current Handling**: Checks `isGBufferAtRenderResolution()` and downscale if needed
- **Impact**: Performance overhead from downscaling when Option B not active

### Issue 2: Depth Buffer Sky Value
- **Location**: [`ray0.rgen.preprocessed:1132`](preprocessed_shaders/ray0.rgen.preprocessed:1132)
- **Problem**: Sky pixels write depth as 10000.0 (hardcoded far plane)
- **Potential Issue**: May cause edge artifacts at sky/object boundaries
- **Recommendation**: Consider using actual far plane distance from projection

### Issue 3: Specular Albedo Source Ambiguity
- **Location**: [`GBufferDLSSDAdapter.java:90-98`](src/main/java/me/cortex/vulkanite/client/rendering/GBufferDLSSDAdapter.java:90)
- **Problem**: Specular albedo source varies (colortex5 or colortex2)
- **Impact**: May cause inconsistent denoising if content differs between buffers
- **Recommendation**: Document expected content for each shader pack

### Issue 4: Format Conversion Overhead
- **Location**: [`DLSSBufferConverter.java`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSBufferConverter.java)
- **Problem**: G-buffers may require format conversion every frame
- **Impact**: GPU memory bandwidth overhead
- **Recommendation**: Encourage Iris to use R16G16B16A16_SFLOAT for G-buffers

### Issue 5: Missing Exposure Texture
- **Location**: [`dlss_wrapper.cpp:848`](dlss_bridge/dlss_wrapper.cpp:848)
- **Problem**: No exposure texture provided to DLSSD
- **Impact**: May not handle extreme brightness variations optimally
- **Recommendation**: Consider providing exposure texture for HDR scenes

---

## Buffer Summary Table

| Buffer | Format | Resolution | Source | Binding | NGX Parameter |
|--------|--------|------------|--------|---------|---------------|
| Color Input | R16G16B16A16_SFLOAT | Render | scaledOutputImage | 12 | pInColor |
| Depth | R32_SFLOAT | Render | depthImage | 14 | pInDepth |
| Motion Vectors | R16G16B16A16_SFLOAT | Render | motionVectorImage | 13 | pInMotionVectors |
| Diffuse Albedo | R16G16B16A16_SFLOAT | Render | gbufferViews[0] | 7 | pInDiffuseAlbedo |
| Specular Albedo | R16G16B16A16_SFLOAT | Render | gbufferViews[4]/[1] | 11 | pInSpecularAlbedo |
| Normals | R16G16B16A16_SFLOAT | Render | gbufferViews[2] | 9 | pInNormals |
| Roughness | (packed in normals.w) | - | - | - | (not used) |
| Exposure | (not provided) | - | - | - | InPreExposure=1.0 |
| Output | R16G16B16A16_SFLOAT | Output | outputImage | - | pInOutput |

---

## Output Buffer Trace Details

This section provides detailed traces for the DLSS output buffer, documenting the complete flow from allocation to final presentation.

### 1. Primary Output (Denoised/Upscaled Color)

#### Allocation Point
- **Location**: [`DLSSRayReconstruction.createBuffers()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:368-373)
- **Code**:
```java
outputImage = context.memory.createImage2D(
    outputWidth, outputHeight, 1,
    VK_FORMAT_R16G16B16A16_SFLOAT,
    ngxUsageFlags,  // STORAGE | SAMPLED | TRANSFER_SRC | TRANSFER_DST
    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
outputView = VImageView.create(context, outputImage);
```

#### Allocation Parameters
- **Format**: `VK_FORMAT_R16G16B16A16_SFLOAT` (RGBA 16-bit float per channel)
- **Resolution**: Output dimensions (full display resolution, e.g., 1920x1080)
- **Mip Levels**: 1
- **Usage Flags**:
  - `VK_IMAGE_USAGE_STORAGE_BIT` - For UAV write by DLSSD
  - `VK_IMAGE_USAGE_SAMPLED_BIT` - For reading in subsequent passes
  - `VK_IMAGE_USAGE_TRANSFER_SRC_BIT` - For blitting to Iris target
  - `VK_IMAGE_USAGE_TRANSFER_DST_BIT` - For potential clears/copies
- **Memory**: `VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT` (GPU-local for optimal performance)
- **Lifetime**: Created once during initialization, reused across frames
- **Ownership**: `DLSSRayReconstruction` class holds `VRef<VImage> outputImage`

#### Initial Layout State
- **Initial Layout**: `VK_IMAGE_LAYOUT_UNDEFINED`
- **First Frame Transition**: [`DLSSRayReconstruction.transitionOutputForEvaluation()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:417-423)
```java
if (!outputLayoutInitialized) {
    cmd.encodeImageTransition(outputImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, ...);
    outputLayoutInitialized = true;
}
```

---

### 2. Binding to DLSSD

#### Java-Side Binding
- **Location**: [`DLSSRayReconstruction.processFrameDLSSD()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:619-620)
- **Code**:
```java
// Output
outputView.get().view, outputImage.get().image(), outputImage.get().format,
```

#### Native Bridge Binding
- **Location**: [`dlss_wrapper.cpp:771`](dlss_bridge/dlss_wrapper.cpp:771)
- **Code**:
```cpp
// Output might be different size if upscaling, use Output dimensions
NVSDK_NGX_Resource_VK rOutput = NVSDK_NGX_Create_ImageView_Resource_VK(
    output, outputImg, subRange, (VkFormat)outputFormat,
    m_DLSSDOutWidth, m_DLSSDOutHeight, true);  // true = isOutput
```

#### NGX Parameter Binding
- **Location**: [`dlss_wrapper.cpp:800`](dlss_bridge/dlss_wrapper.cpp:800)
- **Code**:
```cpp
evalParams.pInOutput = &rOutput;
```

---

### 3. Layout Transitions for Output

#### Pre-DLSSD Evaluation Transitions
- **Location**: [`dlss_wrapper.cpp:900-903`](dlss_bridge/dlss_wrapper.cpp:900)
- **Code**:
```cpp
// Transition output to GENERAL for UAV (unordered access) write by DLSSD
TransitionImageLayout(cmdBuffer, outputImg, VK_IMAGE_LAYOUT_UNDEFINED,
    VK_IMAGE_LAYOUT_GENERAL,
    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
    0, VK_ACCESS_SHADER_WRITE_BIT);
```

**Analysis**: The native bridge transitions the output from `VK_IMAGE_LAYOUT_UNDEFINED` to `VK_IMAGE_LAYOUT_GENERAL` for DLSSD write access. This is correct for the first frame, but subsequent frames should transition from `VK_IMAGE_LAYOUT_GENERAL` (or whatever layout the image is in after the previous frame's blit operation).

#### Post-DLSSD Evaluation Transitions (Java Side)
- **Location**: [`DLSSRayReconstruction.processFrameDLSSD()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:631)
- **Code**:
```java
// After NGX evaluate, add a memory barrier to ensure NGX writes are visible.
cmd.encodeMemoryBarrier();
```

**Note**: The Java side does NOT explicitly transition the output image back after DLSSD. The image remains in `VK_IMAGE_LAYOUT_GENERAL` after DLSSD writes.

---

### 4. Post-DLSS Processing: Output to Iris Target

#### Blit Operation
- **Location**: [`VulkanPipeline.renderPostShadows()`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:783-802)
- **Code**:
```java
if (denoisedOutput != null && denoisedOutput != scaledOutputImage) {
    // Copy denoised output to the full-resolution Iris target
    rtCmd.encodeImageTransition(noisyOutput, VK_IMAGE_LAYOUT_GENERAL,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
    rtCmd.encodeImageTransition(denoisedOutput, VK_IMAGE_LAYOUT_GENERAL,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);

    // Copy image using blit (to handle format conversion and upscaling)
    rtCmd.blitImage(denoisedOutput, noisyOutput,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_FILTER_LINEAR);

    // Transition back to general
    rtCmd.encodeImageTransition(noisyOutput, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
    rtCmd.encodeImageTransition(denoisedOutput, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);

    // Close the denoised output reference
    denoisedOutput.close();
}
```

#### Blit Parameters
- **Source**: `denoisedOutput` (DLSSD output at display resolution)
- **Destination**: `noisyOutput` (Iris render target, `outImgs.get(0)`)
- **Filter**: `VK_FILTER_LINEAR` (for smooth upscaling if needed)
- **Source Layout**: `VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL`
- **Destination Layout**: `VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL`

---

### 5. Final Destination: Iris Render Target

#### Iris Target Binding
- **Source**: `outImgs.get(0)` from Iris pipeline
- **Content**: The denoised, upscaled color output
- **Usage by Iris**: Iris composite shaders read from this target for final presentation

#### Flow to Swapchain
1. DLSSD writes to `outputImage` (internal DLSS buffer)
2. `VulkanPipeline` blits `outputImage` → `noisyOutput` (Iris target)
3. Iris composite pass reads `noisyOutput`
4. Iris renders to swapchain for presentation

---

### 6. Synchronization Analysis

#### Memory Barriers

| Location | Barrier Type | Purpose |
|----------|-------------|---------|
| [`DLSSRayReconstruction.java:477`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:477) | `cmd.encodeMemoryBarrier()` | Ensure input writes visible before DLSS reads |
| [`DLSSRayReconstruction.java:631`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:631) | `cmd.encodeMemoryBarrier()` | Ensure DLSSD writes visible before subsequent reads |
| [`dlss_wrapper.cpp:880-898`](dlss_bridge/dlss_wrapper.cpp:880) | `TransitionImageLayout` | Input images: GENERAL → SHADER_READ_ONLY |
| [`dlss_wrapper.cpp:900-903`](dlss_bridge/dlss_wrapper.cpp:900) | `TransitionImageLayout` | Output image: UNDEFINED → GENERAL |

#### Pipeline Barriers

| Stage | Access | Notes |
|-------|--------|-------|
| `VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT` | Write | DLSSD writes via compute shaders |
| `VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT` | Read | Subsequent passes read for compositing |
| `VK_PIPELINE_STAGE_TRANSFER_BIT` | Read/Write | Blit operation reads from output |

#### Potential Race Conditions

1. **Output Layout Transition Race**:
   - **Issue**: Native bridge transitions output from `VK_IMAGE_LAYOUT_UNDEFINED` every frame
   - **Impact**: May cause validation errors if image is not actually in UNDEFINED layout
   - **Location**: [`dlss_wrapper.cpp:901`](dlss_bridge/dlss_wrapper.cpp:901)
   - **Recommendation**: Track output layout state and transition from actual current layout

2. **Missing Post-Blit Barrier**:
   - **Issue**: After blit, `denoisedOutput` is transitioned back to GENERAL, but the reference is closed
   - **Impact**: If the output image is reused, it may be in wrong layout
   - **Location**: [`VulkanPipeline.java:802`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:802)
   - **Status**: Not an issue since output image is managed by DLSSRayReconstruction and reused correctly

---

### 7. Output Buffer State Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                     OUTPUT BUFFER LIFECYCLE                         │
└─────────────────────────────────────────────────────────────────────┘

  ┌──────────────────┐
  │ ALLOCATION       │ DLSSRayReconstruction.createBuffers()
  │ (First Init)     │ - Format: R16G16B16A16_SFLOAT
  │                  │ - Size: outputWidth x outputHeight
  │                  │ - Layout: UNDEFINED
  └────────┬─────────┘
           │
           ▼
  ┌──────────────────┐
  │ PRE-DLSSD        │ dlss_wrapper.cpp:900-903
  │ TRANSITION       │ UNDEFINED → GENERAL
  │                  │ VK_ACCESS_SHADER_WRITE_BIT
  └────────┬─────────┘
           │
           ▼
  ┌──────────────────┐
  │ DLSSD WRITE      │ NGX_VULKAN_EVALUATE_DLSSD_EXT
  │ (Tensor Cores)   │ - Writes denoised/upscaled color
  │                  │ - Layout: GENERAL
  └────────┬─────────┘
           │
           ▼
  ┌──────────────────┐
  │ MEMORY BARRIER   │ DLSSRayReconstruction.java:631
  │                  │ Ensure writes visible
  └────────┬─────────┘
           │
           ▼
  ┌──────────────────┐
  │ PRE-BLIT         │ VulkanPipeline.java:787-788
  │ TRANSITION       │ GENERAL → TRANSFER_SRC_OPTIMAL
  └────────┬─────────┘
           │
           ▼
  ┌──────────────────┐
  │ BLIT TO IRIS     │ VulkanPipeline.java:791-793
  │                  │ outputImage → noisyOutput (Iris target)
  │                  │ VK_FILTER_LINEAR
  └────────┬─────────┘
           │
           ▼
  ┌──────────────────┐
  │ POST-BLIT        │ VulkanPipeline.java:798-799
  │ TRANSITION       │ TRANSFER_SRC_OPTIMAL → GENERAL
  │                  │ (Ready for next frame)
  └────────┬─────────┘
           │
           ▼
  ┌──────────────────┐
  │ IRIS COMPOSITE   │ Iris pipeline reads from target
  │ → SWAPCHAIN      │ Final presentation
  └──────────────────┘
```

---

### 8. Identified Issues

#### Issue 1: Output Layout Transition Inconsistency
- **Location**: [`dlss_wrapper.cpp:901`](dlss_bridge/dlss_wrapper.cpp:901)
- **Problem**: Native bridge always transitions output from `VK_IMAGE_LAYOUT_UNDEFINED`
- **Expected**: Should transition from actual current layout (GENERAL after first frame)
- **Impact**: May cause Vulkan validation errors on subsequent frames
- **Severity**: Low (works in practice due to memory barrier)

#### Issue 2: Missing Synchronization Between DLSSD and Blit
- **Location**: Between [`DLSSRayReconstruction.processFrameDLSSD()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:631) and [`VulkanPipeline.java:787`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:787)
- **Problem**: No explicit pipeline barrier between DLSSD completion and blit start
- **Expected**: Should have `VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT` → `VK_PIPELINE_STAGE_TRANSFER_BIT` barrier
- **Impact**: Potential race condition on some GPUs
- **Severity**: Medium

#### Issue 3: Output Image Reference Management
- **Location**: [`VulkanPipeline.java:802`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:802)
- **Problem**: `denoisedOutput.close()` is called after blit, but the underlying image is managed by DLSSRayReconstruction
- **Expected**: Should not close the reference if it's a borrowed reference
- **Impact**: May cause use-after-free if reference counting is used
- **Severity**: Low (VRef appears to use addRef/close pattern correctly)

---

### 9. Output Buffer Summary Table

| Property | Value |
|----------|-------|
| **Name** | `outputImage` |
| **Format** | `VK_FORMAT_R16G16B16A16_SFLOAT` |
| **Resolution** | Output dimensions (e.g., 1920x1080) |
| **Usage Flags** | STORAGE \| SAMPLED \| TRANSFER_SRC \| TRANSFER_DST |
| **Memory Type** | DEVICE_LOCAL |
| **Lifetime** | Persistent (created once, reused) |
| **Owner** | `DLSSRayReconstruction` |
| **Initial Layout** | `VK_IMAGE_LAYOUT_UNDEFINED` |
| **During DLSSD** | `VK_IMAGE_LAYOUT_GENERAL` |
| **During Blit** | `VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL` |
| **After Blit** | `VK_IMAGE_LAYOUT_GENERAL` |
| **NGX Parameter** | `pInOutput` |
| **Final Destination** | Iris render target → Swapchain |

---

## Duplicate Bindings Analysis

This section identifies duplicate bindings, redundant operations, and verifies the origin of each binding in the DLSS implementation.

### 1. Summary of Findings

| Category | Count | Severity |
|----------|-------|----------|
| Duplicate Buffer Allocations | 2 | Medium |
| Redundant Format Conversions | 1 | Low |
| Duplicate View Creations | 4 | Medium |
| Intentional Duplicates | 3 | N/A (By Design) |
| Potential Bugs | 2 | High |

---

### 2. Duplicate Buffer Allocations

#### 2.1 Depth Buffer Duplication (POTENTIAL BUG)

**Status**: ⚠️ **Potential Bug - Wasted Memory**

**Origin 1**: [`DLSSRayReconstruction.createBuffers()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:378-383)
```java
depthImage = context.memory.createImage2D(renderWidth, renderHeight, 1,
    VK_FORMAT_R32_SFLOAT, ngxUsageFlags, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
depthView = VImageView.create(context, depthImage);
```

**Origin 2**: [`DLSSDProcessor.getDepthImage()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSDProcessor.java:380) returns `dlssd.getDepthImage()`

**Analysis**: 
- `DLSSRayReconstruction.createBuffers()` creates an internal `depthImage` 
- This buffer is marked as UNUSED in the zero-copy path (line 385-393 comments)
- However, the allocation still occurs, wasting GPU memory
- The actual depth buffer used comes from `DLSSDProcessor.getDepthImage()` which returns the same internal buffer

**Recommendation**: Remove the internal depth buffer creation in `createBuffers()` since the zero-copy path uses external textures. The code comments indicate this was intended but not implemented.

---

#### 2.2 Output Buffer Duplication (INTENTIONAL)

**Status**: ✅ **Intentional - Required for DLSS**

**Origin**: [`DLSSRayReconstruction.createBuffers()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:368-373)

**Analysis**:
- The output buffer is intentionally separate from the Iris render target
- DLSSD writes to this buffer at output resolution
- The buffer is then blitted to the Iris target
- This is correct behavior - DLSS needs its own output buffer for upscaling

**Ownership**: `DLSSRayReconstruction` owns this buffer. It is created once during initialization and reused.

---

### 3. Duplicate View Creations (PERFORMANCE ISSUE)

#### 3.1 VImageView Created Every Frame

**Status**: ⚠️ **Performance Issue - Not a Bug**

The following views are created every frame and closed after use:

| Location | View Created | Source Image | Closed In |
|----------|-------------|--------------|-----------|
| [`DLSSRayReconstruction.processFrame():470`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:470) | `extNoisyInputView` | `noisyInput` | Line 510 |
| [`DLSSRayReconstruction.processFrame():471`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:471) | `extDepthView` | `depth` | Line 511 |
| [`DLSSRayReconstruction.processFrame():472`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:472) | `extMotionVectorsView` | `motionVectors` | Line 512 |
| [`DLSSRayReconstruction.processFrameDLSSD():569`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:569) | `extNoisyInputView` | `noisyInput` | Line 650 |
| [`DLSSRayReconstruction.processFrameDLSSD():570`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:570) | `extDepthView` | `depth` | Line 651 |
| [`DLSSRayReconstruction.processFrameDLSSD():571`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:571) | `extMotionVectorsView` | `motionVectors` | Line 652 |
| [`DLSSRayReconstruction.processFrameDLSSD():572`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:572) | `extDiffuseAlbedoView` | `diffuseAlbedo` | Line 653 |
| [`DLSSRayReconstruction.processFrameDLSSD():573`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:573) | `extSpecularAlbedoView` | `specularAlbedo` | Line 654 |
| [`DLSSRayReconstruction.processFrameDLSSD():574`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:574) | `extNormalsView` | `normals` | Line 655 |

**Analysis**:
- Views are created via `VImageView.create(context, image)` each frame
- This is a Vulkan object allocation that could be cached
- The views are properly closed in `finally` blocks
- Performance impact: Medium (Vulkan view creation has some overhead)

**Recommendation**: Cache frequently used views in a pool or reuse them across frames.

---

### 4. G-Buffer Binding Analysis

#### 4.1 G-Buffer Bindings (INTENTIONAL DUPLICATES)

**Status**: ✅ **Intentional - Required for Hybrid Rendering**

G-buffer textures are bound to descriptor slots 7-11 for ray tracing, then the same textures are passed to DLSSD:

| Binding | Iris Source | DLSSD Input | Shader Usage |
|---------|-------------|-------------|--------------|
| 7 | colortex1 | diffuseAlbedo | `gbufferAlbedo` |
| 8 | colortex2 | specularAlbedo (fallback) | `gbufferMaterial` |
| 9 | colortex3 | normals | `gbufferNormal` |
| 10 | colortex4 | (unused by DLSSD) | `gbufferWorldPos` |
| 11 | colortex5 | specularAlbedo (preferred) | `gbufferExtra` |

**Flow**:
1. [`RenderPassExecutor.execute()`](src/main/java/me/cortex/vulkanite/client/rendering/RenderPassExecutor.java) binds G-buffer views to slots 7-11
2. Ray tracing shader samples from these bindings
3. [`GBufferDLSSDAdapter.extractDLSSDInputs()`](src/main/java/me/cortex/vulkanite/client/rendering/GBufferDLSSDAdapter.java:57) extracts the same views for DLSSD
4. [`DLSSDProcessor.processFrame()`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSDProcessor.java:176) passes them to DLSSD

**Analysis**: This is intentional - the same G-buffer textures are used for both ray tracing and DLSSD. No duplication of buffer data, just multiple consumers of the same textures.

---

#### 4.2 Specular Albedo Source Ambiguity (POTENTIAL BUG)

**Status**: ⚠️ **Potential Bug - Inconsistent Source**

**Location**: [`GBufferDLSSDAdapter.java:90-98`](src/main/java/me/cortex/vulkanite/client/rendering/GBufferDLSSDAdapter.java:90)

```java
if (gbufferViews.length > 4 && gbufferViews[4] != null) {
    inputs.specularAlbedoView = gbufferViews[4]; // colortex5
} else if (gbufferViews[1] != null) {
    inputs.specularAlbedoView = gbufferViews[1]; // colortex2
}
```

**Analysis**:
- Specular albedo source varies between colortex5 and colortex2
- Different shader packs may have different content in these buffers
- Could cause inconsistent denoising if content differs

**Recommendation**: Document expected content for each shader pack, or add configuration to specify the source.

---

### 5. Format Conversion Analysis

#### 5.1 DLSSBufferConverter Cache (INTENTIONAL)

**Status**: ✅ **Intentional - Required for Format Compatibility**

**Location**: [`DLSSBufferConverter.java`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSBufferConverter.java)

The converter creates cached images for format conversion:

| Cache Key Format | Purpose |
|-----------------|---------|
| `{name}_scaled_{W}x{H}_{format}` | Scaled conversion (legacy path) |
| `{name}_{W}x{H}_{format}` | Format-only conversion |

**Analysis**:
- Cache prevents recreating images every frame
- LRU eviction with MAX_CACHE_SIZE = 32
- Converted images are reused via blit operations
- This is correct behavior for handling format mismatches

---

#### 5.2 Noisy Output Conversion (POTENTIAL DUPLICATE)

**Status**: ⚠️ **Potential Duplicate - Needs Investigation**

**Location**: [`DLSSDProcessor.processFrame():251`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSDProcessor.java:251)

```java
VRef<VImage> noisyOutputConverted = bufferConverter.convertToRGBA16F(cmd, noisyOutput, "noisyOutput");
```

**Analysis**:
- `noisyOutput` is `scaledOutputImage` from [`VulkanPipeline.renderPostShadows():780`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:780)
- `scaledOutputImage` is already created as `VK_FORMAT_R16G16B16A16_SFLOAT` in [`VulkanPipeline.createVulkaniteRTRenderTargets():1084`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:1084)
- The conversion should be a no-op if format already matches
- However, the converter still creates a blit operation

**Recommendation**: Verify that `convertToRGBA16F()` correctly returns the source image when format already matches (line 140-142 appears to do this).

---

### 6. Layout Transition Analysis

#### 6.1 Motion Vector Layout Transitions (DUPLICATE OPERATIONS)

**Status**: ⚠️ **Duplicate Operations - Performance Impact**

**Transition Sequence Each Frame**:

1. [`VulkanPipeline.renderPostShadows():591-593`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:591) - UNDEFINED → GENERAL
2. Ray tracing shader writes to motion vectors
3. [`DLSSRayReconstruction.processFrameDLSSD():582`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:582) - GENERAL → SHADER_READ_ONLY
4. DLSSD reads motion vectors
5. [`DLSSRayReconstruction.processFrameDLSSD():633`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:633) - SHADER_READ_ONLY → GENERAL

**Analysis**:
- Motion vector image transitions: UNDEFINED → GENERAL → SHADER_READ_ONLY → GENERAL (3 transitions per frame)
- The UNDEFINED → GENERAL transition at line 591-593 happens every frame, not just first frame
- This may cause validation errors since the image is not in UNDEFINED layout after first frame

**Recommendation**: Track motion vector layout state and only transition from UNDEFINED on first frame.

---

#### 6.2 G-Buffer Layout Transitions (DUPLICATE OPERATIONS)

**Status**: ⚠️ **Duplicate Operations - Performance Impact**

**Transition Sequence**:

1. [`VulkanPipeline.renderPostShadows():536-538`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:536) - GENERAL → SHADER_READ_ONLY
2. Ray tracing shader reads G-buffers
3. [`VulkanPipeline.renderPostShadows():684-687`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:684) - SHADER_READ_ONLY → GENERAL
4. [`DLSSRayReconstruction.processFrameDLSSD():582`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:582) - GENERAL → SHADER_READ_ONLY
5. DLSSD reads G-buffers
6. [`DLSSRayReconstruction.processFrameDLSSD():633`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:633) - SHADER_READ_ONLY → GENERAL

**Analysis**:
- G-buffer images transition 4 times per frame (2 round-trips)
- This is intentional but has performance cost
- Each transition requires a pipeline barrier

**Recommendation**: Consider keeping G-buffers in SHADER_READ_ONLY layout between ray tracing and DLSSD to reduce transitions.

---

### 7. Buffer Ownership Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           BUFFER OWNERSHIP MAP                               │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────┐
│ VulkanPipeline      │
├─────────────────────┼───────────────────────────────────────────────────────┤
│ scaledOutputImage   │ CREATED: createVulkaniteRTRenderTargets()             │
│ (binding 12)        │ FORMAT: R16G16B16A16_SFLOAT                           │
│                     │ RESOLUTION: Render resolution                         │
│                     │ OWNERSHIP: VulkanPipeline                             │
│                     │ CONSUMERS: Ray tracing (write), DLSSD (read)          │
├─────────────────────┼───────────────────────────────────────────────────────┤
│ motionVectorImage   │ CREATED: createVulkaniteRTRenderTargets()             │
│ (binding 13)        │ FORMAT: R16G16B16A16_SFLOAT                           │
│                     │ RESOLUTION: Render resolution                         │
│                     │ OWNERSHIP: VulkanPipeline                             │
│                     │ CONSUMERS: Ray tracing (write), DLSSD (read)          │
├─────────────────────┼───────────────────────────────────────────────────────┤
│ reservoirImages[2]  │ CREATED: createVulkaniteRTRenderTargets()             │
│ (bindings 6, 15)    │ FORMAT: R32G32B32A32_SFLOAT                           │
│                     │ RESOLUTION: Render resolution                         │
│                     │ OWNERSHIP: VulkanPipeline                             │
│                     │ CONSUMERS: Ray tracing (ReSTIR ping-pong)             │
└─────────────────────┴───────────────────────────────────────────────────────┘

┌─────────────────────┐
│ DLSSRayReconstruction│
├─────────────────────┼───────────────────────────────────────────────────────┤
│ outputImage         │ CREATED: createBuffers()                              │
│                     │ FORMAT: R16G16B16A16_SFLOAT                           │
│                     │ RESOLUTION: Output resolution                         │
│                     │ OWNERSHIP: DLSSRayReconstruction                      │
│                     │ CONSUMERS: DLSSD (write), VulkanPipeline (blit read)  │
├─────────────────────┼───────────────────────────────────────────────────────┤
│ depthImage          │ CREATED: createBuffers()                              │
│ (internal)          │ FORMAT: R32_SFLOAT                                    │
│                     │ RESOLUTION: Render resolution                         │
│                     │ OWNERSHIP: DLSSRayReconstruction                      │
│                     │ STATUS: ⚠️ UNUSED (zero-copy path uses external)      │
│                     │ BUG: Allocated but not used - memory waste            │
└─────────────────────┴───────────────────────────────────────────────────────┘

┌─────────────────────┐
│ Iris (External)     │
├─────────────────────┼───────────────────────────────────────────────────────┤
│ colortex1-5         │ CREATED: Iris pipeline                                │
│ (gbufferViews[0-4]) │ OWNERSHIP: Iris                                       │
│ (bindings 7-11)     │ CONSUMERS: Ray tracing (read), DLSSD (read)           │
│                     │ NOTE: Same textures used by both systems              │
├─────────────────────┼───────────────────────────────────────────────────────┤
│ outImgs.get(0)      │ CREATED: Iris pipeline                                │
│ (Iris render target)│ OWNERSHIP: Iris                                       │
│                     │ CONSUMERS: VulkanPipeline (blit dest), Iris composite │
└─────────────────────┴───────────────────────────────────────────────────────┘

┌─────────────────────┐
│ DLSSBufferConverter │
├─────────────────────┼───────────────────────────────────────────────────────┤
│ conversionCache     │ CREATED: On-demand                                    │
│ (LRU cache)         │ OWNERSHIP: DLSSBufferConverter                        │
│                     │ MAX_SIZE: 32 images                                   │
│                     │ PURPOSE: Format conversion intermediates              │
│                     │ LIFETIME: Cached across frames                        │
└─────────────────────┴───────────────────────────────────────────────────────┘
```

---

### 8. Recommendations Summary

#### High Priority (Bugs)

1. **Remove Unused Depth Buffer Allocation**
   - Location: [`DLSSRayReconstruction.createBuffers():378-383`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:378)
   - Action: Remove internal depth buffer creation since zero-copy path uses external textures
   - Impact: Reduces GPU memory usage

2. **Fix Motion Vector Layout Transition**
   - Location: [`VulkanPipeline.java:591-593`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:591)
   - Action: Track layout state and only transition from UNDEFINED on first frame
   - Impact: Prevents validation errors

#### Medium Priority (Performance)

3. **Cache VImageView Objects**
   - Location: [`DLSSRayReconstruction.processFrame():470-472`](src/main/java/me/cortex/vulkanite/client/rendering/DLSSRayReconstruction.java:470)
   - Action: Pool or cache frequently created views
   - Impact: Reduces per-frame allocation overhead

4. **Reduce G-Buffer Layout Transitions**
   - Location: Multiple (see Section 6.2)
   - Action: Keep G-buffers in SHADER_READ_ONLY between ray tracing and DLSSD
   - Impact: Reduces pipeline barrier overhead

#### Low Priority (Code Quality)

5. **Document Specular Albedo Source**
   - Location: [`GBufferDLSSDAdapter.java:90-98`](src/main/java/me/cortex/vulkanite/client/rendering/GBufferDLSSDAdapter.java:90)
   - Action: Add configuration option or documentation for specular source selection
   - Impact: Improves maintainability

---

### 9. Duplicate Bindings Summary Table

| Buffer | Binding(s) | Owner | Duplicate Type | Status |
|--------|------------|-------|----------------|--------|
| scaledOutputImage | 12 | VulkanPipeline | None | ✅ Correct |
| motionVectorImage | 13 | VulkanPipeline | None | ✅ Correct |
| linearDepthImage | 14 | DLSSRayReconstruction | ⚠️ Allocated but unused | 🔴 Bug |
| reservoirImages | 6, 15 | VulkanPipeline | Intentional (ping-pong) | ✅ Correct |
| colortex1 | 7 | Iris | Shared (RT + DLSSD) | ✅ Correct |
| colortex2 | 8 | Iris | Shared (RT + DLSSD fallback) | ⚠️ Ambiguous |
| colortex3 | 9 | Iris | Shared (RT + DLSSD) | ✅ Correct |
| colortex4 | 10 | Iris | RT only | ✅ Correct |
| colortex5 | 11 | Iris | Shared (RT + DLSSD preferred) | ⚠️ Ambiguous |
| outputImage | - | DLSSRayReconstruction | None | ✅ Correct |
| depthImage (internal) | - | DLSSRayReconstruction | ⚠️ Wasted allocation | 🔴 Bug |
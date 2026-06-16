# DLSS Ray Reconstruction Implementation Plan

## Current State Analysis

Based on the analysis of `DLSSBridge.java`, `DLSSDProcessor.java`, and `DLSSRayReconstruction.java`, here is the current state of the DLSS Ray Reconstruction (DLSSD) implementation:

### 1. `DLSSBridge.java` (JNA Bindings)
- **Implemented:**
  - JNA bindings for standard DLSS (`initDLSS`, `evaluateDLSS`, `destroyDLSS`).
  - JNA bindings for DLSSD (`initDLSSD`, `evaluateDLSSD`, `destroyDLSSD`).
  - Query functions (`isDLSSDAvailable`, `getDLSSDRenderResolution`).
  - Constants for DLSSD parameters (Denoise mode, Roughness mode, Depth type, Quality presets).
- **Status:** Looks complete and well-structured for interfacing with the native C++ bridge.

### 2. `DLSSDProcessor.java` (High-Level Integration)
- **Implemented:**
  - Manages the lifecycle of `DLSSRayReconstruction`.
  - Handles configuration loading from `DLSSConfig`.
  - `processFrame` method that extracts G-buffer inputs using `GBufferDLSSDAdapter` and calls the underlying `DLSSRayReconstruction` evaluation.
  - Fallback mechanism to standard DLSS if G-buffer inputs are invalid or DLSSD evaluation fails.
- **Status:** Good high-level wrapper. It relies on `GBufferDLSSDAdapter` (which wasn't analyzed but is assumed to exist and function) to extract Iris G-buffer data.

### 3. `DLSSRayReconstruction.java` (Core Logic)
- **Implemented:**
  - Buffer creation for all required DLSSD inputs (Noisy Input, Depth, Motion Vectors, Diffuse Albedo, Specular Albedo, Normals, Roughness) and Output.
  - Initialization logic (`initialize`) that handles both standard DLSS and DLSSD, including fallback if DLSSD fails to initialize.
  - Frame processing logic (`processFrame` for standard, `processFrameDLSSD` for RR).
  - Handles image layout transitions and blitting (format conversion) before passing to the native bridge.
- **Areas of Concern / Refactoring Needs:**
  - **Redundancy in Buffer Creation:** The class creates its own internal Vulkan images (`noisyInputImage`, `depthImage`, etc.) and blits the external inputs into them every frame. This is highly inefficient. If the external inputs (from the rendering pipeline) already match the required formats and usage flags, they should be used directly to avoid the overhead of blitting and extra memory allocation.
  - **Format Hardcoding:** The internal buffers are hardcoded to specific formats (e.g., `VK_FORMAT_R16G16B16A16_SFLOAT` for color/albedo/normals, `VK_FORMAT_R32_SFLOAT` for depth). While NGX might require these, we should check if the source images already match or can be created with compatible formats in the main pipeline.
  - **Depth Format:** The code explicitly mentions using `VK_FORMAT_R32_SFLOAT` for linear depth. We need to ensure the main pipeline's depth buffer is compatible or if this blit is strictly necessary.
  - **Roughness Handling:** The code supports both packed (in normals.w) and unpacked roughness. `DLSSDProcessor` currently forces `roughnessPacked = true`. This should ideally be dynamic based on the shader pack.
  - **Memory Barriers:** There are explicit `cmd.encodeMemoryBarrier()` calls. We should ensure these are optimal and not causing unnecessary pipeline stalls. More fine-grained barriers (e.g., `vkCmdPipelineBarrier` with specific access masks and pipeline stages) might be better.

## Refactoring Plan

To ensure high visual quality and no redundancies, the following steps should be taken:

### Phase 1: Eliminate Redundant Blitting (Zero-Copy Path)
The biggest performance and memory overhead currently is the blitting of every input buffer into an internal buffer before calling DLSS.

1.  **Analyze Source Image Formats:** Investigate the formats of the images passed into `processFrameDLSSD` (from the Iris G-buffer and Vulkanite RT pipeline).
2.  **Direct Image Usage:** Modify `DLSSRayReconstruction.java` to use the provided `VRef<VImage>` directly if their formats and usage flags are compatible with NGX requirements.
    *   NGX typically requires `VK_IMAGE_USAGE_STORAGE_BIT` and `VK_IMAGE_USAGE_SAMPLED_BIT`.
    *   If the source images lack these flags, modify the pipeline where they are created to include them.
3.  **Conditional Blitting:** Only perform the blit if the source image format is strictly incompatible with NGX (e.g., UNORM when SFLOAT is required) and cannot be changed in the main pipeline.
4.  **Remove Internal Buffers:** If all inputs can be used directly, remove the internal `VImage` allocations (`noisyInputImage`, `depthImage`, etc.) and the associated blit logic.

### Phase 2: Optimize Image Transitions
1.  **Fine-Grained Barriers:** Replace generic `cmd.encodeMemoryBarrier()` with specific `cmd.encodeImageTransition` or `vkCmdPipelineBarrier` calls that specify the exact source and destination access masks and pipeline stages.
2.  **Layout Tracking:** Ensure the image layouts are correctly tracked and transitioned back to their expected state after DLSS evaluation so the rest of the pipeline doesn't break.

### Phase 3: Configuration and Flexibility
1.  **Dynamic Roughness Packing:** Update `DLSSDProcessor.java` to dynamically determine if roughness is packed in the normals buffer based on the active shader pack, rather than hardcoding `this.config.roughnessPacked = true`.
2.  **Depth Buffer Compatibility:** Verify the depth buffer format. The comments mention `ray0.rgen` writes linear depth. Ensure this is correctly passed and interpreted by DLSSD.

### Phase 4: Validation and Testing
1.  **Visual Quality Check:** Ensure that removing the blits doesn't introduce visual artifacts (e.g., due to format mismatches).
2.  **Performance Profiling:** Measure the performance gain from removing the redundant blits and memory allocations.

## Next Steps (Actionable Todos)

1.  **Investigate G-buffer Formats:** Check `GBufferDLSSDAdapter.java` and the main rendering pipeline to determine the exact Vulkan formats and usage flags of the images passed to `DLSSDProcessor.processFrame`.
2.  **Refactor `DLSSRayReconstruction.java`:** Implement the "Zero-Copy Path" by attempting to use the input images directly in `evaluateDLSSD`.
3.  **Update Pipeline Image Creation:** If necessary, modify the creation of the G-buffer and RT output images to include `VK_IMAGE_USAGE_STORAGE_BIT` so they can be bound as UAVs by DLSS.
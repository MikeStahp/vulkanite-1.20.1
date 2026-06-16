package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.config.DLSSConfig;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized manager for DLSS resolution scaling.
 * 
 * This class provides a single source of truth for render/output dimensions
 * when DLSS is enabled, ensuring all components (G-buffers, ray tracing targets,
 * jitter calculations, UBO data) use consistent scaled dimensions.
 * 
 * DLSS Quality Mode Scaling:
 * - NATIVE: 1.0 (100% - no scaling)
 * - QUALITY: 0.667 (66.67% - e.g., 848x480 -> 565x320)
 * - BALANCED: 0.583 (58.3%)
 * - PERFORMANCE: 0.5 (50%)
 * - ULTRA_PERFORMANCE: 0.333 (33.3%)
 */
public class ResolutionScaleManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResolutionScaleManager.class);

    // Singleton instance with volatile for thread-safe double-checked locking
    private static volatile ResolutionScaleManager INSTANCE;

    // Cached dimensions to avoid repeated calculations
    // All cached fields are volatile to ensure visibility across threads
    private volatile int cachedOutputWidth = 0;
    private volatile int cachedOutputHeight = 0;
    private volatile int cachedRenderWidth = 0;
    private volatile int cachedRenderHeight = 0;
    private volatile float cachedScale = 1.0f;
    private volatile boolean cachedDLSSEnabled = false;

    private ResolutionScaleManager() {
    }

    /**
     * Get the singleton instance using double-checked locking for thread safety.
     */
    public static ResolutionScaleManager getInstance() {
        if (INSTANCE == null) {
            synchronized (ResolutionScaleManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ResolutionScaleManager();
                }
            }
        }
        return INSTANCE;
    }
    
    /**
     * Update the resolution cache. Should be called once per frame.
     * This method is synchronized to ensure thread-safe updates to cached dimensions.
     *
     * @param outputWidth The output/display width
     * @param outputHeight The output/display height
     */
    public synchronized void update(int outputWidth, int outputHeight) {
        int alignedOutputWidth = Math.max(8, outputWidth & ~7);
        int alignedOutputHeight = Math.max(8, outputHeight & ~7);

        DLSSConfig config = DLSSConfig.load();
        // Enable DLSS scaling for both DLSS and DLSS_RR (Ray Reconstruction) modes
        String denoiser = config.getDenoiser();
        boolean dlssEnabled = config.isEnabled() && ("DLSS".equals(denoiser) || "DLSS_RR".equals(denoiser));
        float scale = 1.0f;

        if (dlssEnabled) {
            // Get scale from quality preset
            scale = config.getQualityPreset().getScale();
        }

        // Check if dimensions changed
        boolean dimensionsChanged = (alignedOutputWidth != cachedOutputWidth ||
                alignedOutputHeight != cachedOutputHeight ||
                scale != cachedScale ||
                dlssEnabled != cachedDLSSEnabled);

        if (dimensionsChanged) {
            cachedOutputWidth = alignedOutputWidth;
            cachedOutputHeight = alignedOutputHeight;
            cachedScale = scale;
            cachedDLSSEnabled = dlssEnabled;

            // Calculate render dimensions
            // IMPORTANT: Align to multiple of 8 for DLSS compatibility
            //
            // FIX: Align OUTPUT dimensions first, then calculate render dimensions.
            // This ensures consistent alignment between render and output dimensions,
            // preventing temporal buffer misalignment in DLSSD that causes bottom-left
            // quadrant artifacting.
            //
            // Previous behavior (caused artifacts):
            //   Output: 1920x1080 (unaligned)
            //   Quality mode (0.667): Expected 1280x720 -> Actually 1272x712 (8-pixel difference)
            //   This mismatch caused DLSSD's internal temporal buffer to misalign
            //
            // New behavior (fixed):
            //   Output: 1920x1080 -> Aligned to 1920x1080 (already aligned)
            //   Quality mode (0.667): 1920 * 0.667 = 1280 -> Aligned to 1280x720
            //   Both dimensions are now consistently aligned
            if (dlssEnabled && scale < 1.0f) {
                cachedRenderWidth = ((int)(alignedOutputWidth * scale)) & ~7;
                cachedRenderHeight = ((int)(alignedOutputHeight * scale)) & ~7;
            } else {
                // No scaling - render at full resolution (still aligned for GPU optimization)
                cachedRenderWidth = alignedOutputWidth;
                cachedRenderHeight = alignedOutputHeight;
            }

            // Ensure minimum dimensions
            cachedRenderWidth = Math.max(8, cachedRenderWidth);
            cachedRenderHeight = Math.max(8, cachedRenderHeight);

            LOGGER.info("[ResolutionScaleManager] Updated: output={}x{}, render={}x{}, scale={}, dlssEnabled={}",
                    cachedOutputWidth, cachedOutputHeight, cachedRenderWidth, cachedRenderHeight, scale, dlssEnabled);
        }
    }
    
    /**
     * Get the output (display) width.
     * This is the final presentation resolution.
     */
    public int getOutputWidth() {
        return cachedOutputWidth;
    }
    
    /**
     * Get the output (display) height.
     * This is the final presentation resolution.
     */
    public int getOutputHeight() {
        return cachedOutputHeight;
    }
    
    /**
     * Get the render width.
     * When DLSS is enabled with a quality preset, this is the scaled resolution.
     * When DLSS is disabled, this equals the output width.
     */
    public int getRenderWidth() {
        return cachedRenderWidth;
    }
    
    /**
     * Get the render height.
     * When DLSS is enabled with a quality preset, this is the scaled resolution.
     * When DLSS is disabled, this equals the output height.
     */
    public int getRenderHeight() {
        return cachedRenderHeight;
    }
    
    /**
     * Get the current scale factor.
     * Returns 1.0 if DLSS is disabled.
     */
    public float getScale() {
        return cachedScale;
    }
    
    /**
     * Check if DLSS scaling is active.
     * Returns true only if DLSS is enabled AND scale < 1.0.
     */
    public boolean isScalingActive() {
        return cachedDLSSEnabled && cachedScale < 1.0f;
    }
    
    /**
     * Check if DLSS is enabled.
     */
    public boolean isDLSSEnabled() {
        return cachedDLSSEnabled;
    }
    
    /**
     * Get the render dimensions as an aligned pair.
     * Ensures dimensions are aligned to multiple of 8.
     * 
     * @param width The width to align
     * @param height The height to align
     * @return int[2] with aligned width and height
     */
    public static int[] alignDimensions(int width, int height) {
        return new int[] { 
            Math.max(8, width & ~7), 
            Math.max(8, height & ~7) 
        };
    }
    
    /**
     * Calculate scaled render dimensions for a given output size.
     * Does NOT modify cache - just calculates.
     *
     * FIX: Align output dimensions first, then calculate render dimensions.
     * This ensures consistent alignment between render and output dimensions,
     * preventing temporal buffer misalignment in DLSSD.
     *
     * @param outputWidth The output width
     * @param outputHeight The output height
     * @param scale The scale factor (e.g., 0.667 for Quality mode)
     * @return int[2] with render width and height (aligned to 8)
     */
    public static int[] calculateRenderDimensions(int outputWidth, int outputHeight, float scale) {
        // Align output dimensions first for DLSSD compatibility
        int alignedOutputWidth = outputWidth & ~7;
        int alignedOutputHeight = outputHeight & ~7;
        // Then calculate and align render dimensions
        int renderWidth = ((int)(alignedOutputWidth * scale)) & ~7;
        int renderHeight = ((int)(alignedOutputHeight * scale)) & ~7;
        return new int[] {
            Math.max(8, renderWidth),
            Math.max(8, renderHeight)
        };
    }
    
	/**
	 * Force a cache invalidation. Call when DLSS config changes.
	 */
	public void invalidate() {
		cachedOutputWidth = 0;
		cachedOutputHeight = 0;
		cachedRenderWidth = 0;
		cachedRenderHeight = 0;
	}

	/**
	 * Check if the current configuration would result in native resolution mode.
	 * Native resolution mode (render dimensions == output dimensions) is NOT supported
	 * by DLSSD (Ray Reconstruction) - it requires actual upscaling to function.
	 *
	 * @return true if render dimensions equal output dimensions
	 */
	public boolean isNativeResolution() {
		return cachedRenderWidth == cachedOutputWidth && cachedRenderHeight == cachedOutputHeight;
	}

	/**
	 * Check if DLSSD (Ray Reconstruction) would be supported with current settings.
	 * DLSSD requires actual upscaling (render dimensions < output dimensions).
	 *
	 * @return true if DLSSD would be supported (non-native resolution with DLSS enabled)
	 */
	public boolean isDLSSDSupported() {
		return cachedDLSSEnabled && !isNativeResolution();
	}

	/**
	 * Get a description of why DLSSD might not be supported.
	 * Useful for user-facing error messages.
	 *
	 * @return A human-readable explanation, or null if DLSSD is supported
	 */
	public String getDLSSDUnsupportedReason() {
		if (!cachedDLSSEnabled) {
			return "DLSS is not enabled";
		}
		if (isNativeResolution()) {
			return "Native resolution mode (100% scale) is not supported by DLSSD Ray Reconstruction. " +
				   "DLSSD requires upscaling - please select Quality, Balanced, Performance, or Ultra Performance mode.";
		}
		return null; // DLSSD is supported
	}
}

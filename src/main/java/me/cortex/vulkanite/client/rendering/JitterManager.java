package me.cortex.vulkanite.client.rendering;

import org.joml.Matrix4f;

/**
 * Manages subpixel jitter for DLSS temporal stability.
 *
 * <p>This class provides a centralized jitter management system for DLSS temporal
 * anti-aliasing. It generates Halton-sequence jitter patterns and applies them
 * to the projection matrix for temporal sampling.</p>
 *
 * <h2>NVIDIA DLSS Requirements</h2>
 * <ol>
 *   <li>Jitter offsets MUST be in pixel units with range [-0.5, 0.5]</li>
 *   <li>Jitter MUST be applied to the projection matrix for temporal sampling</li>
 *   <li>Jitter offsets are passed to DLSS via InJitterOffsetX/Y parameters</li>
 *   <li>Motion vectors must NOT include jitter - DLSS handles compensation internally</li>
 * </ol>
 *
 * <h2>How Jitter Works</h2>
 * <p>DLSS is a temporal upscaler that needs different sub-pixel samples each frame.
 * The engine applies jitter to the 3D camera (via projection matrix), and DLSS uses
 * the jitter offset values to shift the image back and accumulate details from
 * multiple frames.</p>
 *
 * <h2>Coordinate Space</h2>
 * <p>The jitter values stored here are in PIXEL SPACE (typically [-0.5, 0.5] pixels).
 * When applying to the projection matrix, they must be converted to NDC space using
 * the render resolution.</p>
 *
 * <h2>DLSS Quality Mode Scaling</h2>
 * <p>When DLSS is enabled with a quality preset, the render resolution is scaled
 * (e.g., Quality mode = 66.67% of output). Jitter must be calculated based on
 * the RENDER resolution, not the output resolution. The {@link ResolutionScaleManager}
 * provides the correct scaled dimensions.</p>
 *
 * @see ResolutionScaleManager
 * @see DLSSRayReconstruction
 */
public class JitterManager {
private static int frameIndex = 0;
private static int frameCounter = 0;
private static int sequenceIndex = 0;
private static float jitterX = 0; // Pixel space jitter X
private static float jitterY = 0; // Pixel space jitter Y
private static int phaseCount = 0; // Radiance-style unbounded Halton sequence (no modulo phase cycle)
private static boolean isEnabled = true;

// Track if DLSS is actively processing frames
// Jitter should only be applied when DLSS is running to stabilize the image
private static boolean dlssActive = false;

// Track if this is the first frame after DLSS becomes active
// Used to skip temporal operations on the first frame
private static boolean firstFrameAfterActivation = true;

// Previous frame jitter for DLSS motion vector calculation
private static float prevJitterX = 0;
private static float prevJitterY = 0;

// Current render resolution (for NDC conversion)
// These are the SCALED dimensions when DLSS is active
private static int currentRenderWidth = 1920;
private static int currentRenderHeight = 1080;

// Call this once per frame before rendering
// IMPORTANT: Pass the OUTPUT (full) resolution, not the scaled render resolution.
// The ResolutionScaleManager will be used to get the correct scaled dimensions.
public static void updateJitter(int outputWidth, int outputHeight) {
frameCounter++;
// Store previous jitter before updating (for DLSS motion vectors)
prevJitterX = jitterX;
prevJitterY = jitterY;

// Update the ResolutionScaleManager with current output dimensions
// This ensures all components use consistent scaled dimensions
ResolutionScaleManager scaleManager = ResolutionScaleManager.getInstance();
scaleManager.update(outputWidth, outputHeight);

// Get the RENDER resolution (scaled if DLSS is active)
// Jitter must be calculated based on render resolution, not output resolution
currentRenderWidth = scaleManager.getRenderWidth();
currentRenderHeight = scaleManager.getRenderHeight();

// Only apply jitter when DLSS is actively processing
// Otherwise, disable jitter to prevent image shaking
if (!isEnabled || !dlssActive || currentRenderWidth <= 0 || currentRenderHeight <= 0) {
jitterX = 0;
jitterY = 0;
return;
}

// On first frame after activation, set prevJitter to current to avoid
// spurious motion from invalid previous state
if (firstFrameAfterActivation) {
prevJitterX = 0;
prevJitterY = 0;
firstFrameAfterActivation = false;
}

	frameIndex = sequenceIndex;
	float haltonX = halton(sequenceIndex, 2);
	float haltonY = halton(sequenceIndex, 3);
	sequenceIndex++;

	// Match Radiance: cameraJitter = halton(sequenceIndex++) - vec2(0.5).
	// Values are in render-resolution pixel space and are negated at the NGX boundary.
	jitterX = Math.max(-0.5f, Math.min(0.5f, haltonX - 0.5f));
	jitterY = Math.max(-0.5f, Math.min(0.5f, haltonY - 0.5f));

// DIAGNOSTIC: Log jitter values every 300 frames to reduce spam
if (frameCounter % 300 == 0) {
System.out.println("[JitterManager] frameIndex=" + frameIndex +
", jitter=(" + jitterX + ", " + jitterY + ")" +
", renderRes=" + currentRenderWidth + "x" + currentRenderHeight +
", outputRes=" + scaleManager.getOutputWidth() + "x" + scaleManager.getOutputHeight() +
", scale=" + scaleManager.getScale() +
", dlssActive=" + dlssActive);
}
}

    /**
     * Apply jitter to the projection matrix.
     * 
     * This converts pixel-space jitter to NDC space and applies it to the
     * projection matrix. DLSS requires the camera to be jittered each frame
     * to capture different sub-pixel samples.
     *
     * @param projectionMatrix The projection matrix to modify
     */
 public static void applyJitter(Matrix4f projectionMatrix) {
     if (!isEnabled || !dlssActive || (jitterX == 0 && jitterY == 0))
         return;
     // Use RENDER resolution for NDC conversion (correct for DLSS temporal sampling)
     // Jitter offsets are in render resolution pixel space and must be converted
     // to NDC using the render resolution, not the output resolution.
     // Using output resolution causes incorrect jitter scaling and artifacts.
     float ndcJitterX = (jitterX * 2.0f) / currentRenderWidth;
     float ndcJitterY = (jitterY * 2.0f) / currentRenderHeight;
     // Perspective view-space Z is negative, so adding the projection offset
     // shifts geometry by -jitter on screen. A fixed output pixel therefore
     // samples the same world point as the RT path's pixelCenter + jitter.
     projectionMatrix.m20(projectionMatrix.m20() + ndcJitterX);
     projectionMatrix.m21(projectionMatrix.m21() + ndcJitterY);
 }

 public static Matrix4f copyWithoutJitter(Matrix4f jitteredProjection, Matrix4f destination) {
     destination.set(jitteredProjection);
     if (!isEnabled || !dlssActive || currentRenderWidth <= 0 || currentRenderHeight <= 0 || (jitterX == 0 && jitterY == 0))
         return destination;

     float ndcJitterX = (jitterX * 2.0f) / currentRenderWidth;
     float ndcJitterY = (jitterY * 2.0f) / currentRenderHeight;
     destination.m20(destination.m20() - ndcJitterX);
     destination.m21(destination.m21() - ndcJitterY);
     return destination;
 }

    private static float halton(int index, int base) {
        float f = 1.0f;
        float r = 0.0f;
        int current = index;
        while (current > 0) {
            f = f / base;
            r = r + f * (current % base);
            current = (int) Math.floor(current / base);
        }
        return r;
    }

    /**
     * Get the jitter X value in PIXEL SPACE.
     * This is what DLSS expects for InJitterOffsetX.
     */
    public static float getJitterX() {
        return jitterX;
    }

    /**
     * Get the jitter Y value in PIXEL SPACE.
     * This is what DLSS expects for InJitterOffsetY.
     */
    public static float getJitterY() {
        return jitterY;
    }

    /**
     * Enable or disable jitter processing.
     * @param enabled true to enable, false to disable and reset
     */
    public static void setEnabled(boolean enabled) {
        isEnabled = enabled;
        if (!enabled) {
            reset();
        }
    }

    /**
     * Check if jitter is enabled.
     * @return true if jitter is enabled
     */
    public static boolean isJitterEnabled() {
        return isEnabled;
    }

    /**
     * Get the current frame counter.
     * @return the frame counter value
     */
    public static int getFrameIndex() {
        return frameCounter;
    }

    /**
     * Set whether DLSS is actively processing frames.
     * Jitter is only applied when DLSS is active to prevent image shaking.
     * 
     * Note: This method does NOT reset the jitter state. The jitter sequence
     * continues uninterrupted to maintain temporal consistency. Only the
     * dlssActive flag is toggled to control whether jitter is actually applied.
     *
     * @param active true if DLSS is processing frames, false otherwise
     */
    public static void setDLSSActive(boolean active) {
        if (dlssActive == active) {
            return;
        }
        dlssActive = active;
        // Mark first frame after activation to handle temporal state properly
        if (active) {
            firstFrameAfterActivation = true;
        }
        // Do NOT reset jitter state here - let the sequence continue
        // to maintain temporal consistency for DLSS motion vectors
    }

    /**
     * Check if DLSS is actively processing frames.
     */
    public static boolean isDLSSActive() {
        return dlssActive;
    }

    /**
     * Check if this is the first frame after DLSS activation.
     * Used to skip temporal operations that would have invalid history.
     */
    public static boolean isFirstFrameAfterActivation() {
        return firstFrameAfterActivation;
    }

    /**
     * Get the current render width (aligned to multiple of 8).
     * Used by UBODataEncoder to compute NDC jitter offsets.
     */
    public static int getCurrentRenderWidth() {
        return currentRenderWidth;
    }

    /**
     * Get the current render height (aligned to multiple of 8).
     * Used by UBODataEncoder to compute NDC jitter offsets.
     */
    public static int getCurrentRenderHeight() {
        return currentRenderHeight;
    }

    /**
     * Get the previous frame's jitter X value in PIXEL SPACE.
     * Used for DLSS motion vector calculation.
     */
    public static float getPrevJitterX() {
        return prevJitterX;
    }

    /**
     * Get the previous frame's jitter Y value in PIXEL SPACE.
     * Used for DLSS motion vector calculation.
     */
    public static float getPrevJitterY() {
        return prevJitterY;
    }

    /**
     * Get the jitter delta X (current - previous).
     * @return the difference between current and previous jitter X
     */
    public static float getJitterDeltaX() {
        return jitterX - prevJitterX;
    }

    /**
     * Get the jitter delta Y (current - previous).
     * @return the difference between current and previous jitter Y
     */
    public static float getJitterDeltaY() {
        return jitterY - prevJitterY;
    }

    /**
     * Reset the jitter state. Call this when:
     * - DLSS is reinitialized
     * - Camera teleport/scene change occurs
     * - Temporal history needs to be invalidated
     *
     * This ensures the next frame starts with a clean state,
     * preventing ghosting artifacts from invalid history.
     */
    public static void reset() {
    	frameIndex = 0;
    	sequenceIndex = 0;
    	jitterX = 0;
    	jitterY = 0;
    	prevJitterX = 0;
    	prevJitterY = 0;
    	firstFrameAfterActivation = true;
    	System.out.println("[JitterManager] Reset performed - temporal history cleared");
    }
   
    /**
     * Check if the jitter system is properly initialized and ready.
     *
     * @return true if jitter is enabled and DLSS is active
     */
    public static boolean isReady() {
        return isEnabled && dlssActive;
    }

    /**
     * Validate and log the current jitter state for debugging.
     * Call this to diagnose jitter-related issues.
     */
    public static void validateJitterState() {
        System.out.println("[JitterManager] === Jitter State Validation ===");
        System.out.println("[JitterManager] Enabled: " + isEnabled);
        System.out.println("[JitterManager] DLSS Active: " + dlssActive);
        String phaseLabel = phaseCount > 0 ? String.valueOf(phaseCount) : "unbounded";
        System.out.println("[JitterManager] Frame Index: " + frameIndex + "/" + phaseLabel);
        System.out.println("[JitterManager] Current Jitter: (" + jitterX + ", " + jitterY + ") pixels");
        System.out.println("[JitterManager] Previous Jitter: (" + prevJitterX + ", " + prevJitterY + ") pixels");
        System.out.println("[JitterManager] Jitter Delta: (" + getJitterDeltaX() + ", " + getJitterDeltaY() + ") pixels");
        System.out.println("[JitterManager] Render Resolution: " + currentRenderWidth + "x" + currentRenderHeight);
        System.out.println("[JitterManager] First Frame After Activation: " + firstFrameAfterActivation);
        
        // Validate jitter is in expected range
        if (Math.abs(jitterX) > 0.5f || Math.abs(jitterY) > 0.5f) {
            System.out.println("[JitterManager] WARNING: Jitter out of range [-0.5, 0.5]!");
        }
        
        // Validate resolution is set
        if (currentRenderWidth <= 0 || currentRenderHeight <= 0) {
            System.out.println("[JitterManager] WARNING: Invalid render resolution!");
        }
        
        System.out.println("[JitterManager] === End Validation ===");
    }

    /**
     * Get jitter debug information as an array for UI display.
     *
     * @return float array containing: [jitterX, jitterY, prevJitterX, prevJitterY,
     *          renderWidth, renderHeight, frameIndex, phaseCount]
     */
    public static float[] getJitterDebugInfo() {
        return new float[] {
            jitterX,
            jitterY,
            prevJitterX,
            prevJitterY,
            currentRenderWidth,
            currentRenderHeight,
            frameIndex,
            phaseCount
        };
    }
}

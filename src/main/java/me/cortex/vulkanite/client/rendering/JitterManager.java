package me.cortex.vulkanite.client.rendering;

import org.joml.Matrix4f;

/**
 * Manages subpixel jitter for DLSS temporal stability.
 *
 * IMPORTANT: Jitter MUST be applied to the projection matrix for DLSS to work!
 * DLSS is a temporal upscaler that needs different sub-pixel samples each
 * frame.
 * The engine applies jitter to the 3D camera (via projection matrix), and DLSS
 * uses the jitter offset values to shift the image back and accumulate details.
 *
 * The jitter values stored here are in PIXEL SPACE (typically [-0.5, 0.5]
 * pixels).
 * When applying to the projection matrix, they must be converted to NDC space.
 */
public class JitterManager {
    private static int frameIndex = 0;
    private static float jitterX = 0; // Pixel space jitter X
    private static float jitterY = 0; // Pixel space jitter Y
    private static int phaseCount = 8; // Reduced from 16 for faster convergence
    private static boolean isEnabled = true;

    // Track if DLSS is actively processing frames
    // Jitter should only be applied when DLSS is running to stabilize the image
    private static boolean dlssActive = false;

    // Previous frame jitter for DLSS motion vector calculation
    private static float prevJitterX = 0;
    private static float prevJitterY = 0;

    // Current render resolution (for NDC conversion)
    private static int currentRenderWidth = 1920;
    private static int currentRenderHeight = 1080;

    // Call this once per frame before rendering
    public static void updateJitter(int renderWidth, int renderHeight) {
        // Store previous jitter before updating (for DLSS motion vectors)
        prevJitterX = jitterX;
        prevJitterY = jitterY;

        // Update render resolution
        currentRenderWidth = renderWidth;
        currentRenderHeight = renderHeight;

        // Only apply jitter when DLSS is actively processing
        // Otherwise, disable jitter to prevent image shaking
        if (!isEnabled || !dlssActive || renderWidth <= 0 || renderHeight <= 0) {
            jitterX = 0;
            jitterY = 0;
            return;
        }

        frameIndex = (frameIndex + 1) % phaseCount;

        // Halton generates [0,1); shift to [-0.5, 0.5] pixel space.
        // DLSS natively expects subpixel offsets in pixel space, not NDC.
        jitterX = halton(frameIndex + 1, 2) - 0.5f;
        jitterY = halton(frameIndex + 1, 3) - 0.5f;

        // DIAGNOSTIC: Log jitter values every 60 frames to validate
        if (frameIndex % 60 == 0) {
            System.out.println("[JitterManager DIAGNOSTIC] frameIndex=" + frameIndex +
                    ", jitterX(pixels)=" + jitterX + ", jitterY(pixels)=" + jitterY +
                    ", renderWidth=" + renderWidth + ", renderHeight=" + renderHeight +
                    ", dlssActive=" + dlssActive + ", isEnabled=" + isEnabled);
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
        // Only apply jitter when DLSS is actively processing frames
        // This prevents image shaking when DLSS is not running
        if (!isEnabled || !dlssActive || (jitterX == 0 && jitterY == 0))
            return;

        // Convert pixel space to NDC space
        // NDC range is [-1, 1], so we multiply by 2.0 and divide by resolution
        float ndcJitterX = (jitterX * 2.0f) / currentRenderWidth;
        float ndcJitterY = (jitterY * 2.0f) / currentRenderHeight;

        // In OpenGL style projection (where w = -z), adding to m20 and m21
        // shifts the projected X and Y.
        projectionMatrix.m20(projectionMatrix.m20() + ndcJitterX);
        projectionMatrix.m21(projectionMatrix.m21() + ndcJitterY);
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

    public static void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }

    public static boolean isJitterEnabled() {
        return isEnabled;
    }

    public static int getFrameIndex() {
        return frameIndex;
    }

    /**
     * Set whether DLSS is actively processing frames.
     * Jitter is only applied when DLSS is active to prevent image shaking.
     * 
     * @param active true if DLSS is processing frames, false otherwise
     */
    public static void setDLSSActive(boolean active) {
        dlssActive = active;
        if (!active) {
            // Reset jitter when DLSS is disabled
            jitterX = 0;
            jitterY = 0;
        }
    }

    /**
     * Check if DLSS is actively processing frames.
     */
    public static boolean isDLSSActive() {
        return dlssActive;
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
     * Reset the jitter state. Call this when DLSS is reinitialized.
     */
    public static void reset() {
        frameIndex = 0;
        jitterX = 0;
        jitterY = 0;
        prevJitterX = 0;
        prevJitterY = 0;
    }
}

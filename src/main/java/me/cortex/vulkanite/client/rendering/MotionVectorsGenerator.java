package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.ImageAllocation;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import net.minecraft.client.MinecraftClient;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Motion Vectors Generator for DLSS Ray Reconstruction
 *
 * This class generates screen-space motion vectors required by DLSS RR for temporal reprojection.
 * Motion vectors represent the pixel movement from the previous frame to the current frame.
 *
 * NVIDIA DLSS REQUIREMENTS:
 * - Motion vectors MUST be in pixel units (not NDC)
 * - Motion vectors MUST represent pure geometric motion WITHOUT jitter
 * - Jitter is passed separately to DLSS via InJitterOffsetX/Y parameters
 * - DLSS handles jitter compensation internally
 *
 * Motion Vector Calculation:
 * - For camera movement: Uses UNJITTERED view/projection matrices
 * - For entity movement: Transforms entity positions between frames
 * - For block animations: Tracks animated texture coordinates
 *
 * Format: vec2 (2D screen-space vectors in pixel units)
 * - X component: Horizontal pixel movement
 * - Y component: Vertical pixel movement
 * - Formula: (currentClipPos/currentW - previousClipPos/previousW) * renderResolution
 */
public class MotionVectorsGenerator {
    private final VContext context;
    
    // Motion vector render target (RG16F format, DLSS-recommended)
    private VRef<VImage> motionVectorImage;
    private VRef<VImageView> motionVectorView;
    
    // Previous frame data
    private Matrix4f previousViewMatrix;
    private Matrix4f previousProjectionMatrix;
    private Matrix4f previousViewProjectionMatrix;
    
    // Current frame data
    private Matrix4f currentViewMatrix;
    private Matrix4f currentProjectionMatrix;
    private Matrix4f currentViewProjectionMatrix;
    
    // Viewport dimensions
    private int viewportWidth;
    private int viewportHeight;
    
    // Frame tracking
    private boolean hasPreviousFrame;
    private int frameIndex;
    
    // UBO for motion vector computation
    private MotionVectorUBO motionVectorUBO;
    
    // NVIDIA DLSS REQUIREMENT: Motion vectors must NOT include jitter.
    // Jitter is passed separately to DLSS via InJitterOffsetX/Y parameters.
    // DLSS handles jitter compensation internally - we do NOT subtract it from motion vectors.
    // These fields are kept for diagnostic purposes only.
    private float jitterDeltaX = 0;
    private float jitterDeltaY = 0;
   
    /**
     * Uniform Buffer Object for motion vector data
     * Passed to shaders for motion vector computation
     *
     * NVIDIA DLSS REQUIREMENTS:
     * - Motion vectors must be in pixel units
     * - Motion vectors must represent pure geometric motion (NO jitter)
     * - Jitter is passed separately to DLSS, NOT subtracted from motion vectors
     *
     * Layout:
     * - 0: currentViewProjection (64 bytes, 16 floats) - UNJITTERED
     * - 64: previousViewProjection (64 bytes, 16 floats) - UNJITTERED
     * - 128: inverseCurrentVP (64 bytes, 16 floats)
     * - 192: inversePreviousVP (64 bytes, 16 floats)
     * - 256: viewportWidth (4 bytes)
     * - 260: viewportHeight (4 bytes)
     * - 264: frameId (4 bytes)
     * - 268: padding (4 bytes)
     * - 272: reserved (4 bytes)
     * - 276: reserved (4 bytes)
     * - 280: padding (16 bytes for alignment)
     */
    private static class MotionVectorUBO {
    	private ByteBuffer buffer;
    	private long address;
   
    	public MotionVectorUBO() {
    		// Allocate 296 bytes for motion vector uniforms
    		buffer = MemoryUtil.memAlloc(296);
    		address = MemoryUtil.memAddress(buffer);
    	}
   
    	public void update(Matrix4f currentVP, Matrix4f previousVP,
    			Matrix4f inverseCurrentVP, Matrix4f inversePreviousVP,
    			int width, int height, int frameId) {
    		buffer.clear();
   
    		// Current view-projection matrix (64 bytes) - MUST be UNJITTERED
    		currentVP.get(buffer);
   
    		// Previous view-projection matrix (64 bytes) - MUST be UNJITTERED
    		previousVP.get(buffer);
   
    		// Inverse current view-projection matrix (64 bytes)
    		inverseCurrentVP.get(buffer);
   
    		// Inverse previous view-projection matrix (64 bytes)
    		inversePreviousVP.get(buffer);
   
    		// Viewport dimensions and frame info (16 bytes)
    		buffer.putInt(width);
    		buffer.putInt(height);
    		buffer.putInt(frameId);
    		buffer.putInt(0); // padding
   
    		// Reserved for future use (8 bytes)
    		buffer.putFloat(0); // reserved
    		buffer.putFloat(0); // reserved
    		buffer.putInt(0); // padding
    		buffer.putInt(0); // padding
   
    		buffer.flip();
    	}
        
        public ByteBuffer getBuffer() {
            return buffer;
        }
        
        public void cleanup() {
            if (buffer != null) {
                MemoryUtil.memFree(buffer);
            }
        }
    }
    
    public MotionVectorsGenerator(VContext context) {
        this.context = context;
        this.previousViewMatrix = new Matrix4f();
        this.previousProjectionMatrix = new Matrix4f();
        this.previousViewProjectionMatrix = new Matrix4f();
        this.currentViewMatrix = new Matrix4f();
        this.currentProjectionMatrix = new Matrix4f();
        this.currentViewProjectionMatrix = new Matrix4f();
        this.viewportWidth = 1920;
        this.viewportHeight = 1080;
        this.hasPreviousFrame = false;
        this.frameIndex = 0;
        this.motionVectorUBO = new MotionVectorUBO();
        
        createMotionVectorRenderTarget();
    }
    
    /**
     * Create the motion vector render target
     * Format: RG16F (16-bit float per component, DLSS-recommended format)
     */
    private void createMotionVectorRenderTarget() {
        MinecraftClient mc = MinecraftClient.getInstance();
        viewportWidth = mc.getWindow().getFramebufferWidth();
viewportHeight = mc.getWindow().getFramebufferHeight();

if (viewportWidth <= 0 || viewportHeight <= 0) {
viewportWidth = 1920;
viewportHeight = 1080;
}

// Create motion vector image with RGBA16F format (DLSSD-required)
// DLSSD requires R16G16B16A16_SFLOAT for all color buffers
// RG format: R = horizontal motion, G = vertical motion, BA = 0
// This matches DLSSD requirements for Ray Reconstruction
motionVectorImage = context.memory.createImage2D(
viewportWidth,
viewportHeight,
1,
VK_FORMAT_R16G16B16A16_SFLOAT, // RGBA16F format (DLSSD-required for all color buffers)
VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
);

// Create image view for shader access
motionVectorView = VImageView.create(context, motionVectorImage);

System.out.println("[Vulkanite] Created motion vector render target: " +
viewportWidth + "x" + viewportHeight + " (RGBA16F, DLSSD-compatible)");
    }
    
    /**
     * Update motion vectors for the current frame
     *
     * NVIDIA DLSS REQUIREMENT: Motion vectors must represent pure geometric motion
     * WITHOUT jitter. Jitter is passed separately to DLSS via InJitterOffsetX/Y.
     *
     * This method computes motion vectors using UNJITTERED projection matrices.
     * The input projection matrix may be jittered, so we remove the jitter before
     * calculating motion vectors.
     *
     * @param currentView Current frame view matrix
     * @param currentProjection Current frame projection matrix (may be jittered)
     * @param tickDelta Interpolation factor for smooth motion
     */
    public void updateMotionVectors(Matrix4f currentView, Matrix4f currentProjection, float tickDelta) {
    	frameIndex++;
   
    	// Get current jitter values for diagnostic purposes
    	float currentJitterX = JitterManager.getJitterX();
    	float currentJitterY = JitterManager.getJitterY();
    	float prevJitterX = JitterManager.getPrevJitterX();
    	float prevJitterY = JitterManager.getPrevJitterY();
   
    	// Store jitter delta for diagnostic purposes only
    	// NOTE: We do NOT subtract this from motion vectors - DLSS handles jitter internally
    	this.jitterDeltaX = currentJitterX - prevJitterX;
    	this.jitterDeltaY = currentJitterY - prevJitterY;
   
    	// CRITICAL: Create UNJITTERED projection matrix for motion vector calculation
    	// Motion vectors must represent pure geometric motion without jitter
    	Matrix4f unjitteredProjection = new Matrix4f(currentProjection);
    	if (viewportWidth > 0 && viewportHeight > 0 && (currentJitterX != 0 || currentJitterY != 0)) {
    		// Remove jitter from projection matrix
    		// Jitter is applied to m20 (x offset) and m21 (y offset) in NDC space
    		float ndcJitterX = (currentJitterX * 2.0f) / viewportWidth;
    		float ndcJitterY = (currentJitterY * 2.0f) / viewportHeight;
    		unjitteredProjection.m20(unjitteredProjection.m20() - ndcJitterX);
    		unjitteredProjection.m21(unjitteredProjection.m21() - ndcJitterY);
    	}
   
    	// Store current matrices (UNJITTERED for motion vector calculation)
    	this.currentViewMatrix.set(currentView);
    	this.currentProjectionMatrix.set(unjitteredProjection);
    	this.currentViewProjectionMatrix = new Matrix4f(unjitteredProjection).mul(currentView);
   
    	// Compute inverse matrices for reprojection
    	Matrix4f inverseCurrentVP = new Matrix4f(currentViewProjectionMatrix).invert();
    	Matrix4f inversePreviousVP = new Matrix4f(previousViewProjectionMatrix).invert();
   
    	// Update UBO with UNJITTERED motion vector data
    	// NOTE: No jitter delta is passed - motion vectors are already jitter-free
    	motionVectorUBO.update(
    			currentViewProjectionMatrix,
    			previousViewProjectionMatrix,
    			inverseCurrentVP,
    			inversePreviousVP,
    			viewportWidth,
    			viewportHeight,
    			frameIndex);
   
    	// DIAGNOSTIC: Log motion vector info
    	if (frameIndex % 60 == 0) {
    		System.out.println("[MotionVectors] Using UNJITTERED matrices for motion vectors");
    		System.out.println("[MotionVectors] JitterDelta (diagnostic): (" + jitterDeltaX + ", " + jitterDeltaY + ") pixels");
    		System.out.println("[MotionVectors] CurrentJitter: (" + currentJitterX + ", " + currentJitterY + ")");
    		System.out.println("[MotionVectors] PrevJitter: (" + prevJitterX + ", " + prevJitterY + ")");
    	}
   
    	// Mark that we have previous frame data for next frame
    	hasPreviousFrame = true;
   
    	// Store current UNJITTERED matrices as previous for next frame
    	previousViewMatrix.set(currentView);
    	previousProjectionMatrix.set(unjitteredProjection);
    	previousViewProjectionMatrix.set(currentViewProjectionMatrix);
    }
    
    /**
     * Get the motion vector image for binding to shaders
     */
    public VRef<VImage> getMotionVectorImage() {
        return motionVectorImage;
    }
    
    /**
     * Get the motion vector image view for descriptor set binding
     */
    public VRef<VImageView> getMotionVectorView() {
        return motionVectorView;
    }
    
    /**
     * Get the motion vector UBO buffer for shader access
     */
    public ByteBuffer getMotionVectorUBO() {
        return motionVectorUBO.getBuffer();
    }
    
    /**
     * Check if motion vectors are available (has previous frame data)
     */
    public boolean hasMotionVectors() {
        return hasPreviousFrame;
    }
    
    /**
     * Get the current jitter delta X in pixel space (DIAGNOSTIC ONLY).
     *
     * NOTE: This is for diagnostic purposes only. Motion vectors are calculated
     * WITHOUT jitter, so there is no need to subtract jitter delta from them.
     * DLSS receives jitter offsets separately via InJitterOffsetX/Y parameters.
     *
     * @return Jitter delta X in pixel space (diagnostic only)
     */
    public float getJitterDeltaX() {
    	return jitterDeltaX;
    }
   
    /**
     * Get the current jitter delta Y in pixel space (DIAGNOSTIC ONLY).
     *
     * NOTE: This is for diagnostic purposes only. Motion vectors are calculated
     * WITHOUT jitter, so there is no need to subtract jitter delta from them.
     * DLSS receives jitter offsets separately via InJitterOffsetX/Y parameters.
     *
     * @return Jitter delta Y in pixel space (diagnostic only)
     */
    public float getJitterDeltaY() {
    	return jitterDeltaY;
    }
    
    /**
     * Reset motion vector state (e.g., on camera teleport or scene change)
     */
    public void reset() {
        hasPreviousFrame = false;
        previousViewMatrix.identity();
        previousProjectionMatrix.identity();
        previousViewProjectionMatrix.identity();
        jitterDeltaX = 0;
        jitterDeltaY = 0;
    }
    
    /**
     * Update viewport dimensions (e.g., on window resize)
     */
    public void onViewportResize(int newWidth, int newHeight) {
        if (newWidth != viewportWidth || newHeight != viewportHeight) {
            // Recreate render target with new dimensions
            if (motionVectorView != null) {
                motionVectorView.close();
            }
            if (motionVectorImage != null) {
                motionVectorImage.close();
            }
            
            viewportWidth = newWidth;
            viewportHeight = newHeight;
            createMotionVectorRenderTarget();
            reset(); // Reset motion vectors on resize
        }
    }
    
    /**
     * Get viewport width
     */
    public int getViewportWidth() {
        return viewportWidth;
    }
    
    /**
     * Get viewport height
     */
    public int getViewportHeight() {
        return viewportHeight;
    }
    
    /**
     * Cleanup resources
     */
    public void cleanup() {
        if (motionVectorView != null) {
            motionVectorView.close();
        }
        if (motionVectorImage != null) {
            motionVectorImage.close();
        }
        if (motionVectorUBO != null) {
            motionVectorUBO.cleanup();
        }
    }
}

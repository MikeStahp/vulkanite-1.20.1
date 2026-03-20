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
 * Motion Vector Calculation:
 * - For camera movement: Uses view/projection matrices to compute screen-space motion
 * - For entity movement: Transforms entity positions between frames
 * - For block animations: Tracks animated texture coordinates
 * 
 * Format: vec2 (2D screen-space vectors)
 * - X component: Horizontal pixel movement (normalized to [-1, 1])
 * - Y component: Vertical pixel movement (normalized to [-1, 1])
 * - Formula: (currentPosition - previousPosition) / viewportSize
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
    
    // Jitter compensation
    // DLSS requires motion vectors WITHOUT jitter offset
    // The jitter delta must be subtracted from motion vectors
    private float jitterDeltaX = 0;
    private float jitterDeltaY = 0;
    
    /**
     * Uniform Buffer Object for motion vector data
     * Passed to shaders for motion vector computation
     * 
     * Layout:
     * - 0: currentViewProjection (64 bytes, 16 floats)
     * - 64: previousViewProjection (64 bytes, 16 floats)
     * - 128: inverseCurrentVP (64 bytes, 16 floats)
     * - 192: inversePreviousVP (64 bytes, 16 floats)
     * - 256: viewportWidth (4 bytes)
     * - 260: viewportHeight (4 bytes)
     * - 264: frameId (4 bytes)
     * - 268: padding (4 bytes)
     * - 272: jitterDeltaX (4 bytes)
     * - 276: jitterDeltaY (4 bytes)
     * - 280: padding (16 bytes for alignment)
     */
    private static class MotionVectorUBO {
        private ByteBuffer buffer;
        private long address;
        
        public MotionVectorUBO() {
            // Allocate 296 bytes for motion vector uniforms + jitter delta
            buffer = MemoryUtil.memAlloc(296);
            address = MemoryUtil.memAddress(buffer);
        }
        
        public void update(Matrix4f currentVP, Matrix4f previousVP, 
                          Matrix4f inverseCurrentVP, Matrix4f inversePreviousVP,
                          int width, int height, int frameId,
                          float jitterDeltaX, float jitterDeltaY) {
            buffer.clear();
            
            // Current view-projection matrix (64 bytes)
            currentVP.get(buffer);
            
            // Previous view-projection matrix (64 bytes)
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
            
            // Jitter delta for DLSS compensation (8 bytes)
            buffer.putFloat(jitterDeltaX);
            buffer.putFloat(jitterDeltaY);
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
     * CRITICAL: This method calculates motion vectors WITHOUT jitter offset.
     * The jitter delta is subtracted from motion vectors so DLSS can properly
     * correlate frames temporally.
     * 
     * @param currentView Current frame view matrix
     * @param currentProjection Current frame projection matrix
     * @param tickDelta Interpolation factor for smooth motion
     */
    public void updateMotionVectors(Matrix4f currentView, Matrix4f currentProjection, float tickDelta) {
        frameIndex++;
        
        // Calculate jitter delta BEFORE updating matrices
        // JitterManager stores jitter in pixel space [-0.5, 0.5]
        float currentJitterX = JitterManager.getJitterX();
        float currentJitterY = JitterManager.getJitterY();
        float prevJitterX = JitterManager.getPrevJitterX();
        float prevJitterY = JitterManager.getPrevJitterY();
        
        // Store jitter delta in PIXEL SPACE for shader to subtract
        this.jitterDeltaX = currentJitterX - prevJitterX;
        this.jitterDeltaY = currentJitterY - prevJitterY;
        
        // Store current matrices
        this.currentViewMatrix.set(currentView);
        this.currentProjectionMatrix.set(currentProjection);
        this.currentViewProjectionMatrix = new Matrix4f(currentProjection).mul(currentView);
        
        // Compute inverse matrices for reprojection
        Matrix4f inverseCurrentVP = new Matrix4f(currentViewProjectionMatrix).invert();
        Matrix4f inversePreviousVP = new Matrix4f(previousViewProjectionMatrix).invert();
        
        // Update UBO with motion vector data INCLUDING jitter delta
        motionVectorUBO.update(
            currentViewProjectionMatrix,
            previousViewProjectionMatrix,
            inverseCurrentVP,
            inversePreviousVP,
            viewportWidth,
            viewportHeight,
            frameIndex,
            jitterDeltaX,
            jitterDeltaY);
        
        // DIAGNOSTIC: Log jitter compensation
        if (frameIndex % 60 == 0) {
            System.out.println("[MotionVectors] JitterDelta: (" + jitterDeltaX + ", " + jitterDeltaY + ") pixels");
            System.out.println("[MotionVectors] CurrentJitter: (" + currentJitterX + ", " + currentJitterY + ")");
            System.out.println("[MotionVectors] PrevJitter: (" + prevJitterX + ", " + prevJitterY + ")");
        }
        
        // Mark that we have previous frame data for next frame
        hasPreviousFrame = true;
        
        // Store current matrices as previous for next frame
        previousViewMatrix.set(currentView);
        previousProjectionMatrix.set(currentProjection);
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
     * Get the current jitter delta X in pixel space.
     * This should be subtracted from motion vectors before passing to DLSS.
     */
    public float getJitterDeltaX() {
        return jitterDeltaX;
    }
    
    /**
     * Get the current jitter delta Y in pixel space.
     * This should be subtracted from motion vectors before passing to DLSS.
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

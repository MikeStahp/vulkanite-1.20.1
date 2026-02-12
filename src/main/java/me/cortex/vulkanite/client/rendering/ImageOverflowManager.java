package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages images that exceed Iris's 16-image limit.
 * This class handles ReSTIR reservoirs and other custom images
 * that cannot fit within the standard Iris image system.
 */
public class ImageOverflowManager {
    private final VContext ctx;
    private final Map<String, VRef<VImage>> overflowImages = new HashMap<>();
    private final Map<String, VRef<VImageView>> overflowImageViews = new HashMap<>();
    
    // Specific handling for ReSTIR reservoirs
    private VRef<VImage> reservoirA;
    private VRef<VImage> reservoirB;
    private VRef<VImageView> reservoirAView;
    private VRef<VImageView> reservoirBView;
    
    public ImageOverflowManager(VContext ctx) {
        this.ctx = ctx;
    }
    
    /**
     * Initialize overflow images from the provided list
     */
    public void initializeOverflowImages(Map<String, VRef<VGImage>> overflowImageMap) {
        // Clear existing images
        cleanup();
        
        // Process each overflow image
        System.out.println("[Vulkanite] Processing overflow images, count: " + overflowImageMap.size());
        for (Map.Entry<String, VRef<VGImage>> entry : overflowImageMap.entrySet()) {
            String imageName = entry.getKey();
            VRef<VGImage> vgImage = entry.getValue();
            System.out.println("[Vulkanite] Processing overflow image: " + imageName + ", vgImage=" + vgImage);
            
            if (vgImage != null && vgImage.get() != null) {
                // Create a copy of the VGImage as a VImage for our own management
                VGImage sourceImage = vgImage.get();
                VRef<VImage> managedImage = ctx.memory.createImage2D(
                    sourceImage.width, sourceImage.height, 1,
                    sourceImage.format,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT |
                    org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT,
                    org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                );
                
                // Print source image details for debugging
                System.out.println("[Vulkanite] Source image details for " + imageName + ":");
                System.out.println("[Vulkanite]   Width: " + sourceImage.width + ", Height: " + sourceImage.height);
                System.out.println("[Vulkanite]   Format: " + sourceImage.format);
                
                // Store the managed image
                overflowImages.put(imageName, managedImage);
                
                // Create image view
                VRef<VImageView> imageView = VImageView.create(ctx, managedImage);
                overflowImageViews.put(imageName, imageView);
                System.out.println("[Vulkanite] Created managed image and view for: " + imageName);
                
                // Special handling for ReSTIR reservoirs
                // Handle both exact matches and partial matches for reservoir names
                System.out.println("[Vulkanite] Checking if image is ReSTIR reservoir: " + imageName);
                if (imageName.equals("image.reservoirA") || imageName.startsWith("image.reservoirA")) {
                    System.out.println("[Vulkanite] Identified as reservoirA");
                    reservoirA = managedImage;
                    reservoirAView = imageView;
                } else if (imageName.equals("image.reservoirB") || imageName.startsWith("image.reservoirB")) {
                    System.out.println("[Vulkanite] Identified as reservoirB");
                    reservoirB = managedImage;
                    reservoirBView = imageView;
                }
                
                // Also check for reservoirs with Sampler suffix (from shaders.properties)
                if (imageName.equals("reservoirA_Sampler") || imageName.startsWith("reservoirA_Sampler")) {
                    System.out.println("[Vulkanite] Identified as reservoirA (Sampler variant)");
                    reservoirA = managedImage;
                    reservoirAView = imageView;
                } else if (imageName.equals("reservoirB_Sampler") || imageName.startsWith("reservoirB_Sampler")) {
                    System.out.println("[Vulkanite] Identified as reservoirB (Sampler variant)");
                    reservoirB = managedImage;
                    reservoirBView = imageView;
                }
                
                // Print image details for debugging
                if (reservoirAView != null && reservoirBView != null) {
                    System.out.println("[Vulkanite] ReSTIR reservoirs created:");
                    System.out.println("[Vulkanite]   ReservoirA: " + reservoirA.get() + " (format: " + reservoirA.get().format + ")");
                    System.out.println("[Vulkanite]   ReservoirB: " + reservoirB.get() + " (format: " + reservoirB.get().format + ")");
                }
            } else {
                System.out.println("[Vulkanite] Skipping null image: " + imageName);
            }
        }
    }
    
    /**
     * Get a specific overflow image by name
     */
    public VRef<VImage> getOverflowImage(String name) {
        return overflowImages.get(name);
    }
    
    /**
     * Get a specific overflow image view by name
     */
    public VRef<VImageView> getOverflowImageView(String name) {
        return overflowImageViews.get(name);
    }
    
    /**
     * Get ReSTIR reservoir A
     */
    public VRef<VImageView> getReservoirA() {
        return reservoirAView;
    }
    
    /**
     * Get ReSTIR reservoir B
     */
    public VRef<VImageView> getReservoirB() {
        return reservoirBView;
    }
    
    /**
     * Check if we have ReSTIR reservoirs
     */
    public boolean hasRestirReservoirs() {
        boolean result = reservoirAView != null && reservoirBView != null;
        System.out.println("[Vulkanite] hasRestirReservoirs: " + result + " (A=" + reservoirAView + ", B=" + reservoirBView + ")");
        return result;
    }
    
    /**
     * Clean up all resources
     */
    public void cleanup() {
        // Clean up image views
        for (VRef<VImageView> view : overflowImageViews.values()) {
            if (view != null) {
                view.close();
            }
        }
        overflowImageViews.clear();
        
        // Clean up images
        for (VRef<VImage> image : overflowImages.values()) {
            if (image != null) {
                image.close();
            }
        }
        overflowImages.clear();
        
        // Clear ReSTIR references
        reservoirA = null;
        reservoirB = null;
        reservoirAView = null;
        reservoirBView = null;
    }
}
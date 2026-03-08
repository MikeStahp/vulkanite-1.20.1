package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.config.VulkaniteConfig;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;

import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Resource manager for RTX-specific resources like ReSTIR reservoirs.
 * This class manages resources outside of the vanilla buffer system
 * and provides a bridge to the main rendering pipeline.
 */
public class RtxResourceManager {
    private final VContext ctx;

    // ReSTIR reservoirs - now managed by ImageOverflowManager
    private VRef<VImage> reservoirA;
    private VRef<VImage> reservoirB;
    private VRef<VImageView> reservoirAView;
    private VRef<VImageView> reservoirBView;

    // Reference to the ImageOverflowManager
    private ImageOverflowManager overflowManager;

    // Flag to indicate if ReSTIR is being used
    private boolean restirEnabled = false;

    public RtxResourceManager(VContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Initialize ReSTIR reservoirs if needed
     */
    public void initializeRestirReservoirs(boolean enabled, List<VRef<VGImage>> providedReservoirs, int displayWidth,
            int displayHeight) {
        // Use config setting to override enabled flag
        VulkaniteConfig config = VulkaniteConfig.getInstance();
        me.cortex.vulkanite.client.config.DLSSConfig dlssConfig = me.cortex.vulkanite.client.config.DLSSConfig.load();
        this.restirEnabled = enabled && dlssConfig.isReSTIREnabled();
        System.out.println("[Vulkanite] Setting restirEnabled to: " + this.restirEnabled + " (enabled: " + enabled
                + ", dlssConfig.enableReSTIR: " + dlssConfig.isReSTIREnabled() + ")");

        // If we have an overflow manager, try to get reservoirs from there first
        System.out.println("[Vulkanite] Checking for overflow manager reservoirs: overflowManager=" + overflowManager
                + ", hasRestirReservoirs=" + (overflowManager != null ? overflowManager.hasRestirReservoirs() : "N/A"));
        if (overflowManager != null && overflowManager.hasRestirReservoirs()) {
            this.reservoirAView = overflowManager.getReservoirA();
            this.reservoirBView = overflowManager.getReservoirB();
            System.out.println("[Vulkanite] Got reservoir views from overflow manager: A=" + this.reservoirAView
                    + ", B=" + this.reservoirBView);
            // Get the underlying images from the views
            if (reservoirAView != null && reservoirAView.get() != null) {
                this.reservoirA = new VRef<>(reservoirAView.get().image.get());
                System.out.println("[Vulkanite] Got reservoir A image: " + this.reservoirA);
            }
            if (reservoirBView != null && reservoirBView.get() != null) {
                this.reservoirB = new VRef<>(reservoirBView.get().image.get());
                System.out.println("[Vulkanite] Got reservoir B image: " + this.reservoirB);
            }
        } else if (this.restirEnabled) {
            System.out.println("[Vulkanite] No overflow manager reservoirs, using provided or creating new ones");
            if (providedReservoirs != null && providedReservoirs.size() >= 2) {
                // Use provided reservoirs
                this.reservoirA = new VRef<>(providedReservoirs.get(0).get());
                this.reservoirB = new VRef<>(providedReservoirs.get(1).get());
                System.out.println(
                        "[Vulkanite] Using provided reservoirs: A=" + this.reservoirA + ", B=" + this.reservoirB);
            } else {
                // Create reservoirs with proper size from config or display size
                int width = config.restirReservoirWidth;
                int height = config.restirReservoirHeight;

                if (width == -1 || height == -1) {
                    width = displayWidth;
                    height = displayHeight;
                    System.out.println(
                            "[Vulkanite] Using display resolution for ReSTIR reservoirs: " + width + "x" + height);
                } else {
                    System.out.println(
                            "[Vulkanite] Using config resolution for ReSTIR reservoirs: " + width + "x" + height);
                }

                this.reservoirA = ctx.memory.createImage2D(width, height, 1,
                        VK_FORMAT_R32G32B32A32_SFLOAT,
                        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                this.reservoirB = ctx.memory.createImage2D(width, height, 1,
                        VK_FORMAT_R32G32B32A32_SFLOAT,
                        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                System.out.println(
                        "[Vulkanite] Created ReSTIR reservoirs: A=" + this.reservoirA + ", B=" + this.reservoirB);
            }

            // Create image views if we didn't get them from overflow manager
            if (this.reservoirAView == null) {
                this.reservoirAView = VImageView.create(ctx, reservoirA);
                System.out.println("[Vulkanite] Created reservoir A view: " + this.reservoirAView);
            }
            if (this.reservoirBView == null) {
                this.reservoirBView = VImageView.create(ctx, reservoirB);
                System.out.println("[Vulkanite] Created reservoir B view: " + this.reservoirBView);
            }
        } else {
            System.out.println("[Vulkanite] ReSTIR not enabled, skipping reservoir initialization");
        }
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
     * Check if ReSTIR is enabled
     */
    public boolean isRestirEnabled() {
        return restirEnabled;
    }

    /**
     * Set the overflow manager for managing images beyond Iris limit
     */
    public void setOverflowManager(ImageOverflowManager overflowManager) {
        this.overflowManager = overflowManager;
    }

    /**
     * Clean up resources
     */
    public void destroy() {
        if (reservoirAView != null && (overflowManager == null || !overflowManager.hasRestirReservoirs())) {
            reservoirAView.close();
            reservoirAView = null;
        }
        if (reservoirBView != null && (overflowManager == null || !overflowManager.hasRestirReservoirs())) {
            reservoirBView.close();
            reservoirBView = null;
        }
        if (reservoirA != null && (overflowManager == null || !overflowManager.hasRestirReservoirs())) {
            reservoirA.close();
            reservoirA = null;
        }
        if (reservoirB != null && (overflowManager == null || !overflowManager.hasRestirReservoirs())) {
            reservoirB.close();
            reservoirB = null;
        }
    }
}
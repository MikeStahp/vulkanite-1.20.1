package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VGImage;

public interface IRenderTargetVkGetter {
    VRef<VGImage> getMain();
    VRef<VGImage> getAlt();
    
    /**
     * ThreadLocal to track which render target index is currently being created.
     * This is set by the code that creates render targets (e.g., RenderTargets.getOrCreate())
     * and read by MixinRenderTarget to determine the appropriate resolution.
     */
    ThreadLocal<Integer> CURRENT_RENDER_TARGET_INDEX = ThreadLocal.withInitial(() -> -1);
    
    /**
     * Checks if the current render target being created is the output buffer (colortex0).
     * @return true if the current render target is index 0 (the output buffer)
     */
    static boolean isOutputBuffer() {
        return CURRENT_RENDER_TARGET_INDEX.get() == 0;
    }
    
    /**
     * Checks if the current render target being created is a G-buffer (colortex1-5).
     * @return true if the current render target is a G-buffer (index 1-5)
     */
    static boolean isGBuffer() {
        int idx = CURRENT_RENDER_TARGET_INDEX.get();
        return idx >= 1 && idx <= 5;
    }
    
    /**
     * Gets the current render target index being created.
     * @return the render target index, or -1 if not set
     */
    static int getCurrentIndex() {
        return CURRENT_RENDER_TARGET_INDEX.get();
    }
    
    /**
     * Sets the current render target index being created.
     * @param index the render target index
     */
    static void setCurrentIndex(int index) {
        CURRENT_RENDER_TARGET_INDEX.set(index);
    }
    
    /**
     * Clears the current render target index.
     */
    static void clearCurrentIndex() {
        CURRENT_RENDER_TARGET_INDEX.remove();
    }
}

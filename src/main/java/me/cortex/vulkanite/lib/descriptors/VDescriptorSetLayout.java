package me.cortex.vulkanite.lib.descriptors;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VObject;
import me.cortex.vulkanite.lib.base.VRef;
import org.lwjgl.system.Pointer;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;

import java.nio.LongBuffer;
import java.util.Objects;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;

public final class VDescriptorSetLayout extends VObject implements Pointer {
    private final VContext ctx;
    public final long layout;
    public final int[] types;
    
    // Debug information
    private final long creationTime = System.nanoTime();
    private String debugName = "Unnamed";

    private VDescriptorSetLayout(VContext ctx, long layout, int[] types) {
        this.ctx = ctx;
        this.layout = layout;
        // Store a defensive copy of types array
        this.types = types != null ? types.clone() : new int[0];
    }

    public static VRef<VDescriptorSetLayout> create(VContext ctx, long layout, int[] types) {
        return new VRef<>(new VDescriptorSetLayout(ctx, layout, types));
    }

    @Override
    public long address() {
        return layout;
    }
    
    // Debug methods
    public void setDebugName(String name) {
        this.debugName = name != null ? name : "Unnamed";
    }
    
    public String getDebugName() {
        return debugName;
    }
    
    public long getAgeNanos() {
        return System.nanoTime() - creationTime;
    }
    
    public String getDebugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("DescriptorSetLayout Debug Info: ");
        sb.append("Name=").append(debugName).append(", ");
        sb.append("Layout Handle=").append(layout).append(", ");
        sb.append("Types Count=").append(types.length).append(", ");
        sb.append("Age(ms)=").append(getAgeNanos() / 1_000_000);
        return sb.toString();
    }

    @Override
    protected void free() {
        try {
            // Notify Vulkanite about layout removal
            if (Vulkanite.INSTANCE != null) {
                Vulkanite.INSTANCE.removePoolByLayout(this);
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to notify Vulkanite about layout removal: " + e.getMessage());
        }
        
        try {
            // Destroy the Vulkan descriptor set layout
            if (ctx != null && ctx.device != null && layout != 0) {
                vkDestroyDescriptorSetLayout(ctx.device, layout, null);
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to destroy descriptor set layout: " + e.getMessage());
        }
    }
    
    @Override
    public String toString() {
        return "VDescriptorSetLayout{name=" + debugName + ", handle=" + layout + ", types=" + types.length + "}";
    }
}

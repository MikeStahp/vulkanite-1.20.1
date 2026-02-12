package me.cortex.vulkanite.lib.descriptors;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VObject;
import me.cortex.vulkanite.lib.base.VRef;
import org.lwjgl.vulkan.VkCopyDescriptorSet;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;

public class VDescriptorSet extends VObject {
    public final long poolHandle;
    public final long set;
    private final VRef<VDescriptorPool> pool;
    private final Int2ObjectArrayMap<VRef<VObject>> refs = new Int2ObjectArrayMap<>();
    
    // Debug information
    private final long creationTime = System.nanoTime();
    private String debugName = "Unnamed";

    protected VDescriptorSet(VRef<VDescriptorPool> pool, long poolHandle, long set) {
        this.pool = pool;
        this.poolHandle = poolHandle;
        this.set = set;
    }

    public void addRef(int binding, VRef<VObject> ref) {
        if (ref == null) {
            // Remove existing reference if setting to null
            removeRef(binding);
            return;
        }
        
        var old = refs.put(binding, ref);
        if (old != null && old != ref) {
            old.close();
        }
    }

    public void removeRef(int binding) {
        var old = refs.remove(binding);
        if (old != null) {
            old.close();
        }
    }
    
    public VRef<VObject> getRef(int binding) {
        return refs.get(binding);
    }
    
    public int getRefCount() {
        return refs.size();
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
        sb.append("DescriptorSet Debug Info: ");
        sb.append("Name=").append(debugName).append(", ");
        sb.append("Set Handle=").append(set).append(", ");
        sb.append("Pool Handle=").append(poolHandle).append(", ");
        sb.append("References=").append(refs.size()).append(", ");
        sb.append("Age(ms)=").append(getAgeNanos() / 1_000_000);
        return sb.toString();
    }

    @Override
    protected void free() {
        // Close all referenced objects first
        try {
            refs.values().forEach(ref -> {
                if (ref != null) {
                    try {
                        ref.close();
                    } catch (Exception e) {
                        System.err.println("Warning: Failed to close descriptor reference: " + e.getMessage());
                    }
                }
            });
        } finally {
            refs.clear();
            
            // Then free the set itself
            if (pool != null && pool.get() != null) {
                try {
                    pool.get().freeSet(this);
                } catch (Exception e) {
                    System.err.println("Warning: Failed to free descriptor set: " + e.getMessage());
                }
            }
        }
    }

    public void copyFrom(VContext ctx, VRef<VDescriptorSet> other, int setCapacity) {
        if (other == null || other.get() == null) {
            throw new IllegalArgumentException("Source descriptor set cannot be null");
        }
        
        // Copy references
        for (var entry : other.get().refs.int2ObjectEntrySet()) {
            refs.put(entry.getIntKey(), entry.getValue().addRef());
        }

        try (var stack = stackPush()) {
            var setCopy = VkCopyDescriptorSet.calloc(1, stack);
            setCopy.get(0)
                    .sType$Default()
                    .srcSet(other.get().set)
                    .dstSet(set)
                    .descriptorCount(setCapacity);
            vkUpdateDescriptorSets(ctx.device, null, setCopy);
        } catch (Exception e) {
            System.err.println("Warning: Failed to copy descriptor set: " + e.getMessage());
            throw e;
        }
    }
    
    @Override
    public String toString() {
        return "VDescriptorSet{name=" + debugName + ", handle=" + set + ", refs=" + refs.size() + "}";
    }
}

package me.cortex.vulkanite.lib.descriptors;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VObject;
import me.cortex.vulkanite.lib.base.VRef;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetVariableDescriptorCountAllocateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;

public class VDescriptorPool extends VObject {
    private final VContext ctx;
    private final ArrayList<Long> pools = new ArrayList<>();
    private final ArrayList<Integer> poolFreeSizes = new ArrayList<>();
    private final VRef<VDescriptorSetLayout> layout;
    private final int flags;

    private final int nSetsPerPool;
    private final int countPerType;
    
    // Track total allocated sets for debugging
    private int totalAllocated = 0;

    private VDescriptorPool(VContext ctx, VRef<VDescriptorSetLayout> layout, int flags, int nSetsPerPool, int countPerType) {
        this.ctx = ctx;
        this.layout = layout.addRef();
        this.flags = flags;
        this.countPerType = countPerType;
        this.nSetsPerPool = nSetsPerPool;
    }

    public static VRef<VDescriptorPool> create(VContext ctx, VRef<VDescriptorSetLayout> layout, int flags) {
        return new VRef<>(new VDescriptorPool(ctx, layout, flags, 16, 1));
    }

    public static VRef<VDescriptorPool> create(VContext ctx, VRef<VDescriptorSetLayout> layout, int flags, int countPerType) {
        return new VRef<>(new VDescriptorPool(ctx, layout, flags, 1, countPerType));
    }

    private void createNewPool() {
        try (var stack = stackPush()) {
            var sizes = VkDescriptorPoolSize.calloc(layout.get().types.length, stack);
            for (int i = 0; i < layout.get().types.length; i++) {
                sizes.get(i).type(layout.get().types[i]).descriptorCount(nSetsPerPool * countPerType);
            }
            LongBuffer pPool = stack.mallocLong(1);
            _CHECK_(vkCreateDescriptorPool(ctx.device, VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(flags | VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(nSetsPerPool)
                    .pPoolSizes(sizes), null, pPool));
            pools.add(pPool.get(0));
            poolFreeSizes.add(nSetsPerPool);
        }
    }

    public VRef<VDescriptorSet> allocateSet(int variableSize) {
        if (layout == null || layout.get() == null) {
            throw new IllegalStateException("Descriptor pool has been freed");
        }
        
        // Find a pool with available space, starting from the most recent
        int poolIndex = -1;
        for (int i = pools.size() - 1; i >= 0; i--) {
            if (poolFreeSizes.get(i) > 0) {
                poolIndex = i;
                break;
            }
        }
        
        // If no pool has space, create a new one
        if (poolIndex == -1) {
            createNewPool();
            poolIndex = pools.size() - 1;
        }
        
        long pool = pools.get(poolIndex);
        long set;
        poolFreeSizes.set(poolIndex, poolFreeSizes.get(poolIndex) - 1);
        totalAllocated++;
        
        try (var stack = stackPush()) {
            var pSet = stack.mallocLong(1);
            var allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(layout.get().layout));
            if (variableSize >= 0) {
                var variableCountInfo = VkDescriptorSetVariableDescriptorCountAllocateInfo.calloc(stack)
                        .sType$Default()
                        .pDescriptorCounts(stack.ints(variableSize));
                allocInfo.pNext(variableCountInfo.address());
            }
            int result = vkAllocateDescriptorSets(ctx.device, allocInfo, pSet);
            if (result == VK_ERROR_OUT_OF_POOL_MEMORY) {
                // Restore the free count since allocation failed
                poolFreeSizes.set(poolIndex, poolFreeSizes.get(poolIndex) + 1);
                totalAllocated--;
                createNewPool();
                return allocateSet(variableSize);
            }
            _CHECK_(result);
            set = pSet.get(0);
        } catch (Exception e) {
            // Restore the free count on failure
            poolFreeSizes.set(poolIndex, poolFreeSizes.get(poolIndex) + 1);
            totalAllocated--;
            throw e;
        }
        
        return new VRef<>(new VDescriptorSet(new VRef<>(this), pool, set));
    }

    public VRef<VDescriptorSet> allocateSet() {
        return allocateSet(-1);
    }

    public void freeSet(VDescriptorSet set) {
        if (set == null) {
            return;
        }
        
        int index = pools.indexOf(set.poolHandle);
        if (index == -1) {
            System.err.println("Warning: Attempting to free descriptor set from unknown pool");
            return;
        }
        
        try (var stack = stackPush()) {
            var pDescriptorSets = stack.mallocLong(1).put(0, set.set);
            _CHECK_(vkFreeDescriptorSets(ctx.device, set.poolHandle, pDescriptorSets));
        } catch (Exception e) {
            System.err.println("Warning: Failed to free descriptor set: " + e.getMessage());
        }
        
        int newFreeCount = poolFreeSizes.get(index) + 1;
        poolFreeSizes.set(index, newFreeCount);
        totalAllocated--;
        
        // If pool is completely free, destroy it
        if (newFreeCount == nSetsPerPool) {
            vkDestroyDescriptorPool(ctx.device, set.poolHandle, null);
            pools.remove(index);
            poolFreeSizes.remove(index);
        }
    }
    
    // Debug method to get allocation statistics
    public String getStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("DescriptorPool Stats: ");
        sb.append("Total Pools: ").append(pools.size()).append(", ");
        sb.append("Total Allocated: ").append(totalAllocated).append(", ");
        sb.append("Free Sizes: ").append(poolFreeSizes);
        return sb.toString();
    }

    @Override
    protected void free() {
        // Free all remaining pools
        for (long pool : pools) {
            try {
                vkDestroyDescriptorPool(ctx.device, pool, null);
            } catch (Exception e) {
                System.err.println("Warning: Failed to destroy descriptor pool: " + e.getMessage());
            }
        }
        pools.clear();
        poolFreeSizes.clear();
    }
}

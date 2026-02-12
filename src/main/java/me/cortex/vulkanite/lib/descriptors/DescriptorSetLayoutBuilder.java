package me.cortex.vulkanite.lib.descriptors;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBindingFlagsCreateInfo;

import java.nio.LongBuffer;
import java.util.HashMap;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;

public class DescriptorSetLayoutBuilder {
    private final IntArrayList types = new IntArrayList();
    private final HashMap<Integer, Integer> bindingFlagsMap = new HashMap<>();
    private VkDescriptorSetLayoutBinding.Buffer bindings;
    private int capacity = 0;
    
    public DescriptorSetLayoutBuilder binding(int binding, int type, int count, int stages) {
        ensureCapacity(capacity + 1);
        var struct = bindings.get(capacity);
        struct.set(binding, type, count, stages, null);
        types.add(type);
        capacity++;
        return this;
    }

    public DescriptorSetLayoutBuilder binding(int binding, int type, int stages) {
        return binding(binding, type, 1, stages);
    }
    
    public DescriptorSetLayoutBuilder binding(int type, int stages) {
        return binding(capacity, type, stages);
    }

    public void setBindingFlags(int binding, int flag) {
        bindingFlagsMap.put(binding, flag);
    }

    private int flags;
    
    public DescriptorSetLayoutBuilder() {
        this(0);
    }
    
    public DescriptorSetLayoutBuilder(int flags) {
        this.flags = flags;
        this.bindings = VkDescriptorSetLayoutBinding.calloc(8); // Start with reasonable capacity
        this.capacity = 0;
    }
    
    private void ensureCapacity(int minCapacity) {
        if (minCapacity > bindings.capacity()) {
            int newCapacity = Math.max(minCapacity, bindings.capacity() * 2);
            bindings = VkDescriptorSetLayoutBinding.create(
                MemoryUtil.nmemRealloc(bindings.address(), (long) newCapacity * VkDescriptorSetLayoutBinding.SIZEOF),
                newCapacity);
        }
    }

    public VRef<VDescriptorSetLayout> build(VContext ctx) {
        try (var stack = stackPush()) {
            // Limit the buffer to actual size
            bindings.limit(capacity);
            
            var info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(bindings)
                    .flags(flags);

            if (!bindingFlagsMap.isEmpty()) {
                var bindingFlags = new int[capacity];
                for (var i = 0; i < capacity; i++) {
                    bindingFlags[i] = bindingFlagsMap.getOrDefault(bindings.get(i).binding(), 0);
                }

                var bindingInfo = VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(stack)
                        .sType$Default()
                        .pBindingFlags(stack.ints(bindingFlags));
                info.pNext(bindingInfo);
            }

            LongBuffer pBuffer = stack.mallocLong(1);
            _CHECK_(vkCreateDescriptorSetLayout(ctx.device, info, null, pBuffer));
            return VDescriptorSetLayout.create(ctx, pBuffer.get(0), types.toIntArray());
        } finally {
            // Clean up resources
            if (bindings != null) {
                bindings.free();
                bindings = null;
            }
        }
    }
    
    // Cleanup method for explicit resource management
    public void cleanup() {
        if (bindings != null) {
            bindings.free();
            bindings = null;
        }
    }
}

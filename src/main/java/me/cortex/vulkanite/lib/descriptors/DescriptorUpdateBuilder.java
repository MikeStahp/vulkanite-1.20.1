package me.cortex.vulkanite.lib.descriptors;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class DescriptorUpdateBuilder {
    private final VContext ctx;
    private final MemoryStack stack;
    private final VkWriteDescriptorSet.Buffer updates;
    private final VRef<VImageView> placeholderImageView;
    private final ArrayList<VkDescriptorBufferInfo.Buffer> bulkBufferInfos = new ArrayList<>();
    private final ArrayList<VkDescriptorImageInfo.Buffer> bulkImageInfos = new ArrayList<>();
    private ShaderReflection.Set refSet = null;
    private long set;
    private VRef<VDescriptorSet> setRef;
    private boolean applied = false;

    public DescriptorUpdateBuilder(VContext ctx, int maxUpdates) {
        this(ctx, maxUpdates, null);
    }

    public DescriptorUpdateBuilder(VContext ctx, int maxUpdates, final VRef<VImageView> placeholderImageView) {
        this.ctx = ctx;
        // Calculate required memory size with better estimation
        int objSize = Integer.max(
                Integer.max(
                        VkDescriptorBufferInfo.SIZEOF,
                        VkDescriptorImageInfo.SIZEOF),
                VkWriteDescriptorSetAccelerationStructureKHR.SIZEOF);
        objSize = ((objSize + 15) / 16) * 16;

        // Add safety margin and better sizing
        long requiredMemory = 2048L + (long) maxUpdates * VkWriteDescriptorSet.SIZEOF + (long) maxUpdates * objSize;

        // Ensure we don't exceed reasonable limits
        requiredMemory = Math.min(requiredMemory, 1024L * 1024L); // Cap at 1MB

        this.stack = MemoryStack.create((int) requiredMemory);
        this.stack.push();
        this.updates = VkWriteDescriptorSet.calloc(maxUpdates, stack);
        this.placeholderImageView = placeholderImageView;
    }

    public DescriptorUpdateBuilder(VContext ctx, ShaderReflection.Set refSet) {
        this(ctx, refSet, null);
    }

    public DescriptorUpdateBuilder(VContext ctx, ShaderReflection.Set refSet,
            final VRef<VImageView> placeholderImageView) {
        this(ctx, Math.max(refSet.bindings().size(), 8), placeholderImageView); // Minimum of 8 updates
        this.refSet = refSet;
    }

    private long viewOrPlaceholder(VRef<VImageView> v) {
        if (v == null && placeholderImageView == null)
            return 0;
        return v == null ? placeholderImageView.get().view : v.get().view;
    }

    public DescriptorUpdateBuilder set(VRef<VDescriptorSet> set) {
        if (applied) {
            throw new IllegalStateException("Cannot reuse DescriptorUpdateBuilder after apply() has been called");
        }
        this.set = set.get().set;
        this.setRef = set.addRef();
        return this;
    }

    public DescriptorUpdateBuilder buffer(int binding, final VRef<VBuffer> buffer) {
        validateNotApplied();
        return buffer(binding, buffer, 0, VK_WHOLE_SIZE);
    }

    public DescriptorUpdateBuilder buffer(int binding, final VRef<VBuffer> buffer, long offset, long range) {
        validateNotApplied();
        validateState();
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        setRef.get().addRef(binding, buffer.addRefGeneric());
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(VkDescriptorBufferInfo
                        .calloc(1, stack)
                        .buffer(buffer.get().buffer())
                        .offset(offset)
                        .range(range));

        return this;
    }

    public DescriptorUpdateBuilder buffer(int binding, int dstArrayElement, final List<VRef<VBuffer>> buffers) {
        validateNotApplied();
        validateState();
        if (buffers == null || buffers.isEmpty()) {
            return this;
        }
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        var bufInfo = VkDescriptorBufferInfo.calloc(buffers.size());
        for (int i = 0; i < buffers.size(); i++) {
            setRef.get().addRef(binding + dstArrayElement + i, buffers.get(i).addRefGeneric());
            bufInfo.get(i)
                    .buffer(buffers.get(i).get().buffer())
                    .offset(0)
                    .range(VK_WHOLE_SIZE);
        }
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .dstArrayElement(dstArrayElement)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(buffers.size())
                .pBufferInfo(bufInfo);
        bulkBufferInfos.add(bufInfo);
        return this;
    }

    public DescriptorUpdateBuilder buffer(int binding, int dstArrayElement, final VRef<VBuffer> buffer,
            List<Long> offsets) {
        validateNotApplied();
        validateState();
        if (offsets == null || offsets.isEmpty()) {
            return this;
        }
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        var bufInfo = VkDescriptorBufferInfo.calloc(offsets.size());
        for (int i = 0; i < offsets.size(); i++) {
            setRef.get().addRef(binding + dstArrayElement + i, buffer.addRefGeneric());
            bufInfo.get(i)
                    .buffer(buffer.get().buffer())
                    .offset(offsets.get(i))
                    .range(VK_WHOLE_SIZE);
        }
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .dstArrayElement(dstArrayElement)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(offsets.size())
                .pBufferInfo(bufInfo);
        bulkBufferInfos.add(bufInfo);
        return this;
    }

    private void validateState() {
        if (setRef == null) {
            throw new IllegalStateException("Descriptor set must be set before adding descriptors");
        }
    }

    private void validateNotApplied() {
        if (applied) {
            throw new IllegalStateException("Cannot modify DescriptorUpdateBuilder after apply() has been called");
        }
    }

    public DescriptorUpdateBuilder uniform(int binding, final VRef<VBuffer> buffer) {
        validateNotApplied();
        return uniform(binding, buffer, 0, VK_WHOLE_SIZE);
    }

    public DescriptorUpdateBuilder uniform(int binding, final VRef<VBuffer> buffer, long offset, long range) {
        validateNotApplied();
        validateState();
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null");
        }
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        setRef.get().addRef(binding, buffer.addRefGeneric());
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(VkDescriptorBufferInfo
                        .calloc(1, stack)
                        .buffer(buffer.get().buffer())
                        .offset(offset)
                        .range(range));
        return this;
    }

    public final DescriptorUpdateBuilder acceleration(int binding, VRef<VAccelerationStructure>... structures) {
        validateNotApplied();
        validateState();
        if (structures == null || structures.length == 0) {
            return this;
        }
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        var buff = stack.mallocLong(structures.length);
        for (var structure : structures) {
            if (structure != null) {
                setRef.get().addRef(binding, structure.addRefGeneric());
                buff.put(structure.get().structure);
            }
        }
        buff.rewind();
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(structures.length)
                .pNext(VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                        .sType$Default()
                        .pAccelerationStructures(buff));
        return this;
    }

    public DescriptorUpdateBuilder imageStore(int binding, int dstArrayElement, final List<VRef<VImageView>> views) {
        validateNotApplied();
        validateState();
        if (views == null || views.isEmpty()) {
            return this;
        }
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        var imgInfo = VkDescriptorImageInfo.calloc(views.size());
        for (int i = 0; i < views.size(); i++) {
            VRef<VImageView> view = views.get(i);
            setRef.get().addRef(binding, view != null ? view.addRefGeneric() : null);
            imgInfo.get(i)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .imageView(viewOrPlaceholder(view));
        }
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(views.size())
                .pImageInfo(imgInfo);
        bulkImageInfos.add(imgInfo);
        return this;
    }

    public DescriptorUpdateBuilder imageStore(int binding, final VRef<VImageView> view) {
        validateNotApplied();
        return imageStore(binding, VK_IMAGE_LAYOUT_GENERAL, view);
    }

    public DescriptorUpdateBuilder imageStore(int binding, int layout, final VRef<VImageView> view) {
        validateNotApplied();
        validateState();
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        setRef.get().addRef(binding, view != null ? view.addRefGeneric() : null);
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo
                        .calloc(1, stack)
                        .imageLayout(layout)
                        .imageView(viewOrPlaceholder(view)));
        return this;
    }

    public DescriptorUpdateBuilder imageSampler(int binding, final VRef<VImageView> view, VRef<VSampler> sampler) {
        validateNotApplied();
        return imageSampler(binding, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, view, sampler);
    }

    public DescriptorUpdateBuilder imageSampler(int binding, int layout, final VRef<VImageView> view,
            VRef<VSampler> sampler) {
        validateNotApplied();
        validateState();
        if (sampler == null) {
            throw new IllegalArgumentException("Sampler cannot be null");
        }
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        setRef.get().addRef(binding, view != null ? view.addRefGeneric() : null);
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo
                        .calloc(1, stack)
                        .imageLayout(layout)
                        .imageView(viewOrPlaceholder(view))
                        .sampler(sampler.get().sampler));
        return this;
    }

    public void apply() {
        if (applied) {
            throw new IllegalStateException("apply() has already been called");
        }
        applied = true;

        try {
            updates.limit(updates.position());
            updates.rewind();
            vkUpdateDescriptorSets(ctx.device, updates, null);
        } finally {
            // Clean up resources
            stack.pop();
            for (var bufInfo : bulkBufferInfos) {
                try {
                    bufInfo.free();
                } catch (Exception e) {
                    // Log but don't fail - we're cleaning up
                    System.err.println("Warning: Failed to free buffer info: " + e.getMessage());
                }
            }
            bulkBufferInfos.clear();
            for (var imgInfo : bulkImageInfos) {
                try {
                    imgInfo.free();
                } catch (Exception e) {
                    // Log but don't fail - we're cleaning up
                    System.err.println("Warning: Failed to free image info: " + e.getMessage());
                }
            }
            bulkImageInfos.clear();

            if (setRef != null) {
                setRef.close();
                setRef = null;
            }
        }
    }

    // Allow explicit cleanup if apply() is not called
    public void cleanup() {
        if (applied) {
            return; // Already cleaned up in apply()
        }

        try {
            stack.pop();
        } catch (Exception e) {
            System.err.println("Warning: Failed to pop stack: " + e.getMessage());
        }

        for (var bufInfo : bulkBufferInfos) {
            try {
                bufInfo.free();
            } catch (Exception e) {
                System.err.println("Warning: Failed to free buffer info: " + e.getMessage());
            }
        }
        bulkBufferInfos.clear();

        for (var imgInfo : bulkImageInfos) {
            try {
                imgInfo.free();
            } catch (Exception e) {
                System.err.println("Warning: Failed to free image info: " + e.getMessage());
            }
        }
        bulkImageInfos.clear();

        if (setRef != null) {
            setRef.close();
            setRef = null;
        }
    }

    /**
     * Cleans up resources if not already applied.
     * This should be called explicitly instead of relying on finalize().
     */
    public void close() {
        if (!applied && setRef != null) {
            System.err.println("Warning: DescriptorUpdateBuilder was not properly cleaned up");
            cleanup();
        }
    }
}

package me.cortex.vulkanite.lib.pipeline;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.other.VUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Base class for pipeline builders to reduce code duplication and improve maintainability.
 */
public abstract class PipelineBuilder<T extends PipelineBuilder<T>> {
    protected final Set<VRef<VDescriptorSetLayout>> layouts = new LinkedHashSet<>();
    protected final List<PushConstantRange> pushConstants = new ArrayList<>();

    /**
     * Adds a descriptor set layout to the pipeline.
     *
     * @param layout The descriptor set layout to add
     * @return This builder instance for chaining
     */
    @SuppressWarnings("unchecked")
    public T addLayout(VRef<VDescriptorSetLayout> layout) {
        layouts.add(layout);
        return (T) this;
    }

    /**
     * Adds a push constant range to the pipeline.
     *
     * @param size   Size of the push constant range in bytes
     * @param offset Offset in bytes where the push constant range starts
     * @return This builder instance for chaining
     */
    @SuppressWarnings("unchecked")
    public T addPushConstantRange(int size, int offset) {
        pushConstants.add(new PushConstantRange(size, offset));
        return (T) this;
    }

    /**
     * Creates a pipeline layout based on the configured descriptor set layouts and push constants.
     *
     * @param context The Vulkan context
     * @param stack   The memory stack for allocations
     * @return The created pipeline layout handle
     */
    protected long createPipelineLayout(VContext context, MemoryStack stack) {
        VkPipelineLayoutCreateInfo layoutCreateInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);

        // Set descriptor set layouts
        if (!layouts.isEmpty()) {
            layoutCreateInfo.pSetLayouts(stack.longs(layouts.stream().mapToLong(layout -> layout.get().layout).toArray()));
        }

        // Set push constant ranges
        if (!pushConstants.isEmpty()) {
            VkPushConstantRange.Buffer pushConstantRanges = VkPushConstantRange.calloc(pushConstants.size(), stack);
            for (int i = 0; i < pushConstants.size(); i++) {
                PushConstantRange pushConstant = pushConstants.get(i);
                pushConstantRanges.get(i)
                        .stageFlags(VK_SHADER_STAGE_ALL)
                        .offset(pushConstant.offset)
                        .size(pushConstant.size);
            }
            layoutCreateInfo.pPushConstantRanges(pushConstantRanges);
        }

        LongBuffer pLayout = stack.mallocLong(1);
        _CHECK_(vkCreatePipelineLayout(context.device, layoutCreateInfo, null, pLayout),
                "Failed to create pipeline layout");
        return pLayout.get(0);
    }

    /**
     * Validates that all required components are set before building the pipeline.
     *
     * @throws IllegalStateException if validation fails
     */
    protected abstract void validate() throws IllegalStateException;

    /**
     * Internal representation of a push constant range.
     */
    protected static class PushConstantRange {
        final int size;
        final int offset;

        PushConstantRange(int size, int offset) {
            this.size = size;
            this.offset = offset;
        }
    }
}
package me.cortex.vulkanite.lib.cmd;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.base.VObject;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSet;
import me.cortex.vulkanite.lib.memory.MemoryManager;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.memory.VImage;

import me.cortex.vulkanite.lib.other.VQueryPool;
import me.cortex.vulkanite.lib.other.VUtil;
import me.cortex.vulkanite.lib.other.sync.VGSemaphore;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import me.cortex.vulkanite.lib.pipeline.VComputePipeline;
import me.cortex.vulkanite.lib.pipeline.VRaytracePipeline;
import org.lwjgl.vulkan.*;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;

import java.util.*;

//TODO: Track with TrackedResourceObject but need to be careful due to how the freeing works
public class VCmdBuff extends VObject {
    private final VRef<VCommandPool> pool;
    private VkCommandBuffer buffer;

    @SuppressWarnings("FieldCanBeLocal")
    private final List<VRef<VObject>> refs = Collections.synchronizedList(new ArrayList<>());

    public void addBufferRef(final VRef<VBuffer> buffer) {
        refs.add(buffer.addRefGeneric());
    }

    // This is a generic method that can be used to add any type of VObject to the
    // refs list
    // ref should be produced by addRefGeneric() method of the object
    public void moveRefGeneric(final VRef<VObject> ref) {
        refs.add(ref);
    }

    public void addImageRef(final VRef<VImage> image) {
        refs.add(image.addRefGeneric());
    }

    public void addSemaphoreRef(final VRef<VSemaphore> semaphore) {
        refs.add(semaphore.addRefGeneric());
    }

    public void addVGSemaphoreRef(final VRef<VGSemaphore> semaphore) {
        refs.add(semaphore.addRefGeneric());
    }

    public void addAccelerationStructureRef(final VRef<VAccelerationStructure> accelerationStructure) {
        refs.add(accelerationStructure.addRefGeneric());
    }

    protected VCmdBuff(VRef<VCommandPool> pool, VkCommandBuffer buff, int flags) {
        this.pool = pool;
        this.buffer = buff;

        try (var stack = stackPush()) {
            vkBeginCommandBuffer(buffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(flags));
        }
    }

    public final VkCommandBuffer buffer() {
        return buffer;
    }

    /**
     * Returns the address of the command buffer for native interop.
     * 
     * @return the native address of the command buffer, or 0 if buffer is null
     */
    public long bufferAddress() {
        return buffer != null ? buffer.address() : 0;
    }

    private VkCommandBuffer finalizedBuffer = null;

    public VkCommandBuffer seal() {
        if (finalizedBuffer != null) {
            return finalizedBuffer;
        }
        if (buffer == null) {
            throw new IllegalStateException("Command buffer is null, cannot seal");
        }
        finalizedBuffer = buffer;
        buffer = null;
        int result = vkEndCommandBuffer(finalizedBuffer);
        if (result != VK10.VK_SUCCESS) {
            System.err.println("vkEndCommandBuffer failed with result: " + VUtil.translateVulkanResult(result) + " ("
                    + result + ")");
            VUtil._CHECK_(result);
        }
        return finalizedBuffer;
    }

    private long currentPipelineLayout = -1;
    private int currentShaderStageMask = 0;
    private int currentPipelineBindPoint = -1;

    private VkStridedDeviceAddressRegionKHR gen, miss, hit, callable;

    public void bindCompute(final VRef<VComputePipeline> pipeline) {
        vkCmdBindPipeline(buffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.get().pipeline());
        refs.add(new VRef<>(pipeline.get()));
        currentPipelineLayout = pipeline.get().layout();
        currentShaderStageMask = VK_SHADER_STAGE_COMPUTE_BIT;
        currentPipelineBindPoint = VK_PIPELINE_BIND_POINT_COMPUTE;
    }

    public void bindRT(final VRef<VRaytracePipeline> pipeline) {
        vkCmdBindPipeline(buffer, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline.get().pipeline);
        refs.add(new VRef<>(pipeline.get()));
        currentPipelineLayout = pipeline.get().layout;
        currentShaderStageMask = VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_MISS_BIT_KHR
                | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | VK_SHADER_STAGE_CALLABLE_BIT_KHR;
        currentPipelineBindPoint = VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR;

        gen = pipeline.get().gen;
        miss = pipeline.get().miss;
        hit = pipeline.get().hit;
        callable = pipeline.get().callable;
    }

    public void traceRays(int width, int height, int depth) {
        vkCmdTraceRaysKHR(buffer, gen, miss, hit, callable, width, height, depth);
    }

    public void bindDSet(VRef<VDescriptorSet>... sets) {
        try (var stack = stackPush()) {
            var vkSets = stack.mallocLong(sets.length);
            for (VRef<VDescriptorSet> set : sets) {
                vkSets.put(set.get().set);
                refs.add(set.addRefGeneric());
            }
            vkSets.rewind();
            vkCmdBindDescriptorSets(buffer, currentPipelineBindPoint, currentPipelineLayout, 0, vkSets, null);
        }
    }

    public void bindDSet(List<VRef<VDescriptorSet>> sets) {
        try (var stack = stackPush()) {
            var vkSets = stack.mallocLong(sets.size());
            for (int i = 0; i < sets.size(); i++) {
                VRef<VDescriptorSet> set = sets.get(i);
                vkSets.put(set.get().set);
                refs.add(set.addRefGeneric());
            }
            vkSets.rewind();
            vkCmdBindDescriptorSets(buffer, currentPipelineBindPoint, currentPipelineLayout, 0, vkSets, null);
        }
    }

    public void pushConstants(int offset, int size, long dataPtr) {
        pushConstants(offset, size, dataPtr, VK_SHADER_STAGE_ALL);
    }

    public void pushConstants(int offset, int size, long dataPtr, int stageMask) {
        nvkCmdPushConstants(buffer, currentPipelineLayout, stageMask, offset, size, dataPtr);
    }

    public void pushConstants(int offset, long[] data) {
        pushConstants(offset, data, VK_SHADER_STAGE_ALL);
    }

    public void pushConstants(int offset, long[] data, int stageMask) {
        vkCmdPushConstants(buffer, currentPipelineLayout, stageMask, offset, data);
    }

    public void dispatch(int x, int y, int z) {
        if (currentShaderStageMask != VK_SHADER_STAGE_COMPUTE_BIT || currentPipelineLayout == -1) {
            throw new IllegalStateException("No compute pipeline bound");
        }
        vkCmdDispatch(buffer, x, y, z);
    }

    public void resetQueryPool(final VRef<VQueryPool> queryPool, int first, int size) {
        vkCmdResetQueryPool(buffer, queryPool.get().pool, first, size);
        refs.add(queryPool.addRefGeneric());
    }

    public void writeTimestamp(final VRef<VQueryPool> queryPool, int query, int pipelineStage) {
        vkCmdWriteTimestamp(buffer, pipelineStage, queryPool.get().pool, query);
        refs.add(queryPool.addRefGeneric());
    }

    public void encodeBufferCopy(final VRef<VBuffer> src, long srcOffset, final VRef<VBuffer> dest, long destOffset,
            long size) {
        try (var stack = stackPush()) {
            var copy = VkBufferCopy.calloc(1, stack);
            copy.get(0).srcOffset(srcOffset).dstOffset(destOffset).size(size);
            vkCmdCopyBuffer(buffer, src.get().buffer(), dest.get().buffer(), copy);
        }

        addBufferRef(src);
        addBufferRef(dest);
    }

    public void encodeDataUpload(MemoryManager manager, long src, final VRef<VBuffer> dest, long destOffset,
            long size) {
        VRef<VBuffer> staging = manager.createBuffer(size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
        staging.get().setDebugUtilsObjectName("Data Upload Host Staging");
        long ptr = staging.get().map();
        MemoryUtil.memCopy(src, ptr, size);
        staging.get().unmap();

        try (var stack = stackPush()) {
            var copy = VkBufferCopy.calloc(1, stack);
            copy.get(0).srcOffset(0).dstOffset(destOffset).size(size);
            vkCmdCopyBuffer(buffer, staging.get().buffer(), dest.get().buffer(), copy);
        }

        addBufferRef(staging);
        addBufferRef(dest);

        staging.close();
    }

    public void encodeImageUpload(MemoryManager manager, long src, final VRef<VImage> dest, long srcSize,
            int destLayout) {
        VRef<VBuffer> staging = manager.createBuffer(srcSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
        staging.get().setDebugUtilsObjectName("Image Upload Host Staging");
        long ptr = staging.get().map();
        MemoryUtil.memCopy(src, ptr, srcSize);
        staging.get().unmap();

        try (var stack = stackPush()) {
            var copy = VkBufferImageCopy.calloc(1, stack);
            copy.get(0).bufferOffset(0).bufferImageHeight(0).bufferRowLength(0)
                    .imageOffset(o -> o.set(0, 0, 0))
                    .imageExtent(extent -> extent.set(dest.get().width, dest.get().height, dest.get().depth))
                    .imageSubresource(
                            s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseArrayLayer(0).layerCount(1).mipLevel(0));
            vkCmdCopyBufferToImage(buffer, staging.get().buffer(), dest.get().image(), destLayout, copy);
        }

        addBufferRef(staging);
        addImageRef(dest);

        staging.close();
    }

    public void encodeMemoryBarrier() {
        encodeMemoryBarrier(
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_MEMORY_WRITE_BIT,
                VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
    }

    public void encodeMemoryBarrier(int srcStage, int dstStage, int srcAccess, int dstAccess) {
        try (var stack = stackPush()) {
            var barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default().srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(this.buffer, srcStage, dstStage,
                    0, barrier, null, null);
        }
    }

    public static int dstStageToAccess(int dstStage) {
        return switch (dstStage) {
            case VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT -> 0;
            case VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT -> VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
            case VK_PIPELINE_STAGE_VERTEX_INPUT_BIT -> VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT;
            case VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TESSELLATION_CONTROL_SHADER_BIT,
                    VK_PIPELINE_STAGE_TESSELLATION_EVALUATION_SHADER_BIT,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR ->
                VK_ACCESS_SHADER_READ_BIT;
            case VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT -> VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
            case VK_PIPELINE_STAGE_TRANSFER_BIT -> VK_ACCESS_TRANSFER_READ_BIT;
            case VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR ->
                VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_SHADER_READ_BIT;
            default -> VK_ACCESS_MEMORY_READ_BIT;
        };
    }

    public static int srcStageToAccess(int srcStage) {
        return switch (srcStage) {
            case VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT -> 0;
            case VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT -> VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
            case VK_PIPELINE_STAGE_VERTEX_INPUT_BIT -> VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT;
            case VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TESSELLATION_CONTROL_SHADER_BIT,
                    VK_PIPELINE_STAGE_TESSELLATION_EVALUATION_SHADER_BIT,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR ->
                VK_ACCESS_SHADER_WRITE_BIT;
            case VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT -> VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case VK_PIPELINE_STAGE_TRANSFER_BIT -> VK_ACCESS_TRANSFER_WRITE_BIT;
            case VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR ->
                VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK_ACCESS_SHADER_WRITE_BIT;
            default -> VK_ACCESS_MEMORY_WRITE_BIT;
        };
    }

    public void encodeBufferBarrier(final VRef<VBuffer> buffer, long offset, long size) {
        encodeBufferBarrier(buffer, offset, size, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
    }

    public void encodeBufferBarrier(final VRef<VBuffer> buffer, long offset, long size, int srcStage, int dstStage) {
        encodeBufferBarrier(buffer, offset, size, srcStage, dstStage,
                srcStageToAccess(srcStage), dstStageToAccess(dstStage));
    }

    public void encodeBufferBarrier(
            final VRef<VBuffer> buffer,
            long offset,
            long size,
            int srcStage,
            int dstStage,
            int srcAccess,
            int dstAccess) {
        try (var stack = stackPush()) {
            var barrier = VkBufferMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default().srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess).buffer(buffer.get().buffer())
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .offset(offset).size(size);
            vkCmdPipelineBarrier(this.buffer, srcStage, dstStage,
                    0, null, barrier, null);
        }

        addBufferRef(buffer);
    }

    public int srcLayoutToStage(int srcLayout) {
        return switch (srcLayout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL ->
                VK_PIPELINE_STAGE_TRANSFER_BIT;
            case VK_IMAGE_LAYOUT_GENERAL -> VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ->
                VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                        | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
            default -> VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
        };
    }

    public int layoutToAccess(int srcLayout) {
        return switch (srcLayout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> 0;
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK_ACCESS_TRANSFER_READ_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK_ACCESS_TRANSFER_WRITE_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK_ACCESS_SHADER_READ_BIT;
            default -> VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        };
    }

    public int dstLayoutToStage(int dstLayout) {
        return switch (dstLayout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL ->
                VK_PIPELINE_STAGE_TRANSFER_BIT;
            case VK_IMAGE_LAYOUT_GENERAL -> VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ->
                VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                        | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
            default -> VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        };
    }

    public void encodeImageTransition(VRef<VImage> image, int src, int dst, int aspectMask, int mipLevels) {
        try (var stack = stackPush()) {
            var barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default().oldLayout(src).newLayout(dst).image(image.get().image())
                    .subresourceRange().aspectMask(aspectMask).baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0)
                    .layerCount(VK_REMAINING_ARRAY_LAYERS);

            int srcStage = srcLayoutToStage(src);
            int dstStage = dstLayoutToStage(dst);
            barrier.srcAccessMask(layoutToAccess(src));
            barrier.dstAccessMask(layoutToAccess(dst));

            vkCmdPipelineBarrier(this.buffer, srcStage, dstStage,
                    0, null, null, barrier);
        }
        addImageRef(image);
    }

    /**
     * Copy one image to another. Both images must have the same dimensions.
     * 
     * @param src       Source image
     * @param dst       Destination image
     * @param srcLayout Source image layout (typically TRANSFER_SRC_OPTIMAL)
     * @param dstLayout Destination image layout (typically TRANSFER_DST_OPTIMAL)
     */
    public void copyImage(VRef<VImage> src, VRef<VImage> dst, int srcLayout, int dstLayout) {
        if (src == null || dst == null) {
            throw new IllegalArgumentException("Source and destination images must not be null");
        }
        try (var stack = stackPush()) {
            var copy = VkImageCopy.calloc(1, stack);
            copy.get(0)
                    .srcSubresource(
                            s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
                    .srcOffset(o -> o.set(0, 0, 0))
                    .dstSubresource(
                            d -> d.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
                    .dstOffset(o -> o.set(0, 0, 0))
                    .extent(e -> e.set(src.get().width, src.get().height, src.get().depth));
            vkCmdCopyImage(buffer, src.get().image(), srcLayout, dst.get().image(), dstLayout, copy);
        }
        addImageRef(src);
        addImageRef(dst);
    }

    /**
     * Blit one image to another, supporting format conversion and scaling.
     * Both images should be in TRANSFER layouts.
     * 
     * @param src       Source image
     * @param dst       Destination image
     * @param srcLayout Source image layout (typically TRANSFER_SRC_OPTIMAL)
     * @param dstLayout Destination image layout (typically TRANSFER_DST_OPTIMAL)
     * @param filter    Texture filtering (e.g. VK_FILTER_LINEAR)
     */
    public void blitImage(VRef<VImage> src, VRef<VImage> dst, int srcLayout, int dstLayout, int filter) {
        if (src == null || dst == null) {
            throw new IllegalArgumentException("Source and destination images must not be null");
        }
        try (var stack = stackPush()) {
            var blit = VkImageBlit.calloc(1, stack);
            blit.get(0)
                    .srcSubresource(
                            s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
                    .srcOffsets(0, o -> o.set(0, 0, 0))
                    .srcOffsets(1, o -> o.set(src.get().width, src.get().height, src.get().depth))
                    .dstSubresource(
                            d -> d.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
                    .dstOffsets(0, o -> o.set(0, 0, 0))
                    .dstOffsets(1, o -> o.set(dst.get().width, dst.get().height, dst.get().depth));
            vkCmdBlitImage(buffer, src.get().image(), srcLayout, dst.get().image(), dstLayout, blit, filter);
        }
        addImageRef(src);
        addImageRef(dst);
    }

    protected void free() {
        VCommandPool owner = pool.get();
        synchronized (owner) {
            vkFreeCommandBuffers(owner.device, owner.pool, buffer == null ? finalizedBuffer : buffer);
        }
        List<VRef<VObject>> refsToClose;
        synchronized (refs) {
            refsToClose = new ArrayList<>(refs);
            refs.clear();
        }
        for (VRef<VObject> ref : refsToClose) {
            ref.close();
        }
    }

    public void setDebugUtilsObjectName(String name) {
        Vulkanite.INSTANCE.getCtx().setDebugUtilsObjectName(buffer.address(), VK_OBJECT_TYPE_COMMAND_BUFFER, name);
    }
}

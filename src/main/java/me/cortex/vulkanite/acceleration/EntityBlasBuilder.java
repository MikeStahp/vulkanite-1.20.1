package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.other.VUtil;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Pair;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class EntityBlasBuilder {
    private final VContext ctx;

    // Use triple buffering to allow CPU to record frames ahead of GPU without stalling or race conditions
    private static final int FRAMES_IN_FLIGHT = 3;
    private final List<FrameData> frames = new ArrayList<>();
    private int frameIndex = 0;

    public EntityBlasBuilder(VContext context) {
        this.ctx = context;
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            frames.add(new FrameData());
        }
    }

    private static class FrameData {
        VRef<VBuffer> stagingBuffer;
        VRef<VBuffer> geometryBuffer;
        VRef<VBuffer> scratchBuffer;
        VRef<VAccelerationStructure> resultStructure;

        long currentStagingSize;
        long currentGeometrySize;
        long currentScratchSize;
        long currentStructureSize;

        public void ensureGeometryBuffers(VContext ctx, long size) {
            if (stagingBuffer == null || currentStagingSize < size) {
                if (stagingBuffer != null) stagingBuffer.close();
                // Grow by 1.5x to reduce reallocation frequency
                currentStagingSize = Math.max(size, (long)(currentStagingSize * 1.5));
                // Ensure non-zero size
                if (currentStagingSize == 0) currentStagingSize = 1024;

                stagingBuffer = ctx.memory.createBuffer(
                        currentStagingSize,
                        VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                        VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                        0,
                        VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
            }
            if (geometryBuffer == null || currentGeometrySize < size) {
                if (geometryBuffer != null) geometryBuffer.close();
                currentGeometrySize = Math.max(size, (long)(currentGeometrySize * 1.5));
                if (currentGeometrySize == 0) currentGeometrySize = 1024;

                geometryBuffer = ctx.memory.createBuffer(
                        currentGeometrySize,
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            }
        }

        public void ensureStructure(VContext ctx, long asSize, long scratchSize) {
            if (resultStructure == null || currentStructureSize < asSize) {
                if (resultStructure != null) resultStructure.close();
                currentStructureSize = Math.max(asSize, (long)(currentStructureSize * 1.5));
                if (currentStructureSize == 0) currentStructureSize = 1024;

                resultStructure = ctx.memory.createAcceleration(currentStructureSize, 256,
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            }
            if (scratchBuffer == null || currentScratchSize < scratchSize) {
                if (scratchBuffer != null) scratchBuffer.close();
                currentScratchSize = Math.max(scratchSize, (long)(currentScratchSize * 1.5));
                if (currentScratchSize == 0) currentScratchSize = 1024;

                scratchBuffer = ctx.memory.createBuffer(currentScratchSize,
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 256, 0);
            }
        }

        public void free() {
            if (stagingBuffer != null) stagingBuffer.close();
            if (geometryBuffer != null) geometryBuffer.close();
            if (scratchBuffer != null) scratchBuffer.close();
            if (resultStructure != null) resultStructure.close();
        }
    }

    // Cleans up all resources. Should be called on shutdown.
    public void free() {
        for (FrameData frame : frames) {
            frame.free();
        }
        frames.clear();
    }

    private void executeBlasBuild(VContext ctx, VCmdBuff cmd, MemoryStack stack, VkAccelerationStructureGeometryKHR.Buffer geometryInfos, int[] prims, FrameData frame) {
        var buildInfos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        var buildRanges = VkAccelerationStructureBuildRangeInfoKHR.calloc(prims.length, stack);
        for (int primCount : prims) {
            buildRanges.get().primitiveCount(primCount);
        }

        var bi = buildInfos.get()
                .sType$Default()
                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                .pGeometries(geometryInfos)
                .geometryCount(geometryInfos.remaining());

        VkAccelerationStructureBuildSizesInfoKHR buildSizesInfo = VkAccelerationStructureBuildSizesInfoKHR
                .calloc(stack)
                .sType$Default();

        vkGetAccelerationStructureBuildSizesKHR(
                ctx.device,
                VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                bi,
                prims,
                buildSizesInfo);

        // Ensure buffers are large enough for the build
        frame.ensureStructure(ctx, buildSizesInfo.accelerationStructureSize(), buildSizesInfo.buildScratchSize());

        // Debug naming for scratch buffer
        frame.scratchBuffer.get().setDebugUtilsObjectName("Entity BLAS Scratch Frame " + frameIndex);

        bi.scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(frame.scratchBuffer.get().deviceAddress()));
        bi.dstAccelerationStructure(frame.resultStructure.get().structure);

        buildInfos.rewind();
        buildRanges.rewind();

        vkCmdBuildAccelerationStructuresKHR(cmd.buffer(), buildInfos, stack.pointers(buildRanges));

        vkCmdPipelineBarrier(cmd.buffer(), VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, 0, VkMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR), null, null);

        // Keep buffers alive for the duration of the command execution
        cmd.addAccelerationStructureRef(frame.resultStructure);
        cmd.addBufferRef(frame.scratchBuffer);
        // Do NOT close scratchBuffer here as it is reused
    }

    List<BLASResult> buildBlas(List<Pair<RenderLayer, BufferBuilder.BuiltBuffer>> renders, VCmdBuff cmd) {
        long combined_size = 0;
        TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
        for (var type : renders) {
            // Checks for vertex format validity
            if (!type.getRight().getParameters().format().equals(IrisVertexFormats.ENTITY)) {
                throw new IllegalStateException("Unknown vertex format used");
            }
            combined_size += type.getRight().getVertexBuffer().remaining() + 256; // Add alignment padding
        }

        // Get current frame data
        FrameData frame = frames.get(frameIndex);
        frameIndex = (frameIndex + 1) % FRAMES_IN_FLIGHT;

        // Ensure geometry buffers are large enough
        frame.ensureGeometryBuffers(ctx, combined_size);

        long ptr = frame.stagingBuffer.get().map();
        long offset = 0;
        List<BuildInfo> infos = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();

        for (var pair : renders) {
            offset = VUtil.alignUp(offset, 128);
            MemoryUtil.memCopy(MemoryUtil.memAddress(pair.getRight().getVertexBuffer()), ptr + offset, pair.getRight().getVertexBuffer().remaining());
            infos.add(new BuildInfo(pair.getRight().getParameters().format(), pair.getRight().getParameters().indexCount() / 6, frame.geometryBuffer.get().deviceAddress() + offset));
            offsets.add(offset);

            offset += pair.getRight().getVertexBuffer().remaining();
        }

        // Keep staging buffer alive for transfer
        cmd.addBufferRef(frame.stagingBuffer);
        frame.stagingBuffer.get().unmap();

        cmd.encodeBufferCopy(frame.stagingBuffer, 0, frame.geometryBuffer, 0, combined_size);
        cmd.encodeBufferBarrier(frame.geometryBuffer, 0, combined_size, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);
        // Do NOT close stagingBuffer as it is reused

        try (var stack = MemoryStack.stackPush()) {
            int[] primitiveCounts = new int[infos.size()];
            var buildInfo = populateBuildStructs(ctx, stack, cmd, infos, primitiveCounts);

            executeBlasBuild(ctx, cmd, stack, buildInfo, primitiveCounts, frame);
        }

        // Return new references to the reused resources.
        // The caller (AccelerationTLASManager) will close these, decrementing the ref count.
        // EntityBlasBuilder retains its own references via FrameData.
        return List.of(new BLASResult(frame.resultStructure.addRef(), frame.geometryBuffer.addRef(), offsets));
    }

    private VkAccelerationStructureGeometryKHR.Buffer populateBuildStructs(VContext ctx, MemoryStack stack, VCmdBuff cmdBuff, List<BuildInfo> geometries, int[] primitiveCounts) {
        var geometryInfos = VkAccelerationStructureGeometryKHR.calloc(geometries.size(), stack);
        int i = 0;
        for (var geometry : geometries) {
            var indexBuffer = SharedQuadVkIndexBuffer.getIndexBuffer(ctx, cmdBuff, geometry.quadCount);
            VkDeviceOrHostAddressConstKHR indexData = indexBuffer.get().deviceAddressConst();
            int indexType = SharedQuadVkIndexBuffer.TYPE;

            VkDeviceOrHostAddressConstKHR vertexData = VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(geometry.address);
            int vertexFormat = VK_FORMAT_R32G32B32_SFLOAT;
            int vertexStride = geometry.format.getVertexSizeByte();

            geometryInfos.get()
                    .sType$Default()
                    .geometry(VkAccelerationStructureGeometryDataKHR.calloc(stack)
                            .triangles(VkAccelerationStructureGeometryTrianglesDataKHR.calloc(stack)
                                    .sType$Default()

                                    .vertexData(vertexData)
                                    .vertexFormat(vertexFormat)
                                    .vertexStride(vertexStride)
                                    .maxVertex(geometry.quadCount * 4)

                                    .indexData(indexData)
                                    .indexType(indexType)))
                    .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
            //        .flags(geometry.geometryFlags)
            ;

            primitiveCounts[i++] = (geometry.quadCount * 2);

            cmdBuff.addBufferRef(indexBuffer);
        }
        geometryInfos.rewind();
        return geometryInfos;
    }

    public record BLASResult(VRef<VAccelerationStructure> structure, VRef<VBuffer> geometry, List<Long> offsets) {
    }

    private record BuildInfo(VertexFormat format, int quadCount, long address) {
    }
}

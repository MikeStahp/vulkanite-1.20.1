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
    private final List<FrameData> frames = new ArrayList<>();
    private int frameIndex = 0;

    public EntityBlasBuilder(VContext context) {
        this.ctx = context;
        for (int i = 0; i < 3; i++) {
            frames.add(new FrameData());
        }
    }

    private class FrameData {
        VRef<VBuffer> staging;
        VRef<VBuffer> geometry;
        VRef<VBuffer> scratch;
        VRef<VBuffer> result;

        void ensureStagingGeometry(long stagingSize, long geometrySize) {
            // Staging buffer
            if (staging == null || staging.get().size() < stagingSize) {
                if (staging != null) staging.close();
                long size = Math.max(stagingSize, (long) (staging != null ? staging.get().size() * 1.5f : 0));
                staging = ctx.memory.createBuffer(
                        size,
                        VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                        VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                        0,
                        VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
            }

            // Geometry buffer
            if (geometry == null || geometry.get().size() < geometrySize) {
                if (geometry != null) geometry.close();
                long size = Math.max(geometrySize, (long) (geometry != null ? geometry.get().size() * 1.5f : 0));
                geometry = ctx.memory.createBuffer(
                        size,
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            }
        }

        void ensureScratchResult(long scratchSize, long resultSize) {
            // Scratch buffer
            if (scratch == null || scratch.get().size() < scratchSize) {
                if (scratch != null) scratch.close();
                long size = Math.max(scratchSize, (long) (scratch != null ? scratch.get().size() * 1.5f : 0));
                scratch = ctx.memory.createBuffer(size,
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 256, 0);
            }

            // Result buffer (BLAS)
            if (result == null || result.get().size() < resultSize) {
                if (result != null) result.close();
                long size = Math.max(resultSize, (long) (result != null ? result.get().size() * 1.5f : 0));
                result = ctx.memory.createBuffer(size,
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 256, 0);
            }
        }

        void free() {
            if (staging != null) staging.close();
            if (geometry != null) geometry.close();
            if (scratch != null) scratch.close();
            if (result != null) result.close();
        }
    }

    public void free() {
        for (var frame : frames) {
            frame.free();
        }
    }

    private VRef<VAccelerationStructure> executeBlasBuild(FrameData frame, VContext ctx, VCmdBuff cmd, MemoryStack stack, VkAccelerationStructureGeometryKHR.Buffer geometryInfos, int[] prims) {
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


        frame.ensureScratchResult(buildSizesInfo.buildScratchSize(), buildSizesInfo.accelerationStructureSize());

        var structure = ctx.memory.createAcceleration(frame.result, 0, buildSizesInfo.accelerationStructureSize(),
                VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);

        // Use the reused scratch buffer
        var scratchAddress = frame.scratch.get().deviceAddress();

        bi.scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(scratchAddress));
        bi.dstAccelerationStructure(structure.get().structure);

        buildInfos.rewind();
        buildRanges.rewind();

        vkCmdBuildAccelerationStructuresKHR(cmd.buffer(), buildInfos, stack.pointers(buildRanges));

        vkCmdPipelineBarrier(cmd.buffer(), VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, 0, VkMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR), null, null);

        cmd.addAccelerationStructureRef(structure);
        cmd.addBufferRef(frame.scratch);
        // Do NOT close scratch here, it is owned by FrameData
        // scratch.close();

        return structure;
    }

    List<BLASResult> buildBlas(List<Pair<RenderLayer, BufferBuilder.BuiltBuffer>> renders, VCmdBuff cmd) {
        long combined_size = 0;
        TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
        for (var type : renders) {
            if (!type.getRight().getParameters().format().equals(IrisVertexFormats.ENTITY)) {
                throw new IllegalStateException("Unknown vertex format used");
            }
            combined_size += type.getRight().getVertexBuffer().remaining() + 256;//Add just some buffer so we can do alignment etc
        }

        if (combined_size == 0) {
            return List.of();
        }

        // Get current frame data
        var frame = frames.get(frameIndex);
        frameIndex = (frameIndex + 1) % 3;

        //Each render layer gets its own geometry entry in the blas

        //TODO: PUT THE BINDLESS TEXTURE REFERENCE AT THE START OF THE render layers geometry buffer

        frame.ensureStagingGeometry(combined_size, combined_size);

        var geometryBufferStaging = frame.staging;
        var geometryBuffer = frame.geometry;

        long ptr = geometryBufferStaging.get().map();
        long offset = 0;
        List<BuildInfo> infos = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();
        for (var pair : renders) {
            offset = VUtil.alignUp(offset, 128);
            MemoryUtil.memCopy(MemoryUtil.memAddress(pair.getRight().getVertexBuffer()), ptr + offset, pair.getRight().getVertexBuffer().remaining());
            infos.add(new BuildInfo(pair.getRight().getParameters().format(), pair.getRight().getParameters().indexCount() / 6, geometryBuffer.get().deviceAddress() + offset));
            offsets.add(offset);

            offset += pair.getRight().getVertexBuffer().remaining();
        }
        cmd.addBufferRef(geometryBufferStaging);
        geometryBufferStaging.get().unmap();

        cmd.encodeBufferCopy(geometryBufferStaging, 0, geometryBuffer, 0, combined_size);
        cmd.encodeBufferBarrier(geometryBuffer, 0, combined_size, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);
        // Do NOT close staging buffer
        // geometryBufferStaging.close();

        VRef<VAccelerationStructure> blas;
        try (var stack = MemoryStack.stackPush()) {
            int[] primitiveCounts = new int[infos.size()];
            var buildInfo = populateBuildStructs(ctx, stack, cmd, infos, primitiveCounts);

            blas = executeBlasBuild(frame, ctx, cmd, stack, buildInfo, primitiveCounts);
        }

        // Return addRef()'d versions because the caller expects to own them (or at least close them)
        // geometryBuffer is owned by FrameData, but BLASResult takes a VRef.
        // The BLASResult caller closes it.
        // So we should pass geometryBuffer.addRef()
        // Wait, BLASResult record: BLASResult(VRef<VAccelerationStructure> structure, VRef<VBuffer> geometry, ...)

        return List.of(new BLASResult(blas, geometryBuffer.addRef(), offsets));
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

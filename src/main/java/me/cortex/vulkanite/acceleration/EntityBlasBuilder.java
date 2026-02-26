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

    // Cached buffers for reuse to reduce allocation overhead
    private VRef<VBuffer> reusableGeometryBuffer;
    private VRef<VBuffer> reusableScratchBuffer;

    public EntityBlasBuilder(VContext context) {
        this.ctx = context;
    }

    // Called to clean up cached buffers if needed
    public void free() {
        if (reusableGeometryBuffer != null) {
            reusableGeometryBuffer.close();
            reusableGeometryBuffer = null;
        }
        if (reusableScratchBuffer != null) {
            reusableScratchBuffer.close();
            reusableScratchBuffer = null;
        }
    }

    private VRef<VAccelerationStructure> executeBlasBuild(VContext ctx, VCmdBuff cmd, MemoryStack stack, VkAccelerationStructureGeometryKHR.Buffer geometryInfos, int[] prims) {
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


        var structure = ctx.memory.createAcceleration(buildSizesInfo.accelerationStructureSize(), 256,
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);

        // Resize scratch buffer if needed
        long scratchSize = buildSizesInfo.buildScratchSize();
        if (reusableScratchBuffer == null || reusableScratchBuffer.get().size() < scratchSize) {
            if (reusableScratchBuffer != null) {
                reusableScratchBuffer.close();
            }
            reusableScratchBuffer = ctx.memory.createBuffer(scratchSize,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 256, 0);
            reusableScratchBuffer.get().setDebugUtilsObjectName("Entity BLAS Scratch Buffer");
        } else {
            // Add barrier to synchronize access to the reused scratch buffer
            cmd.encodeBufferBarrier(reusableScratchBuffer, 0, reusableScratchBuffer.get().size(),
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);
        }

        bi.scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(reusableScratchBuffer.get().deviceAddress()));
        bi.dstAccelerationStructure(structure.get().structure);

        buildInfos.rewind();
        buildRanges.rewind();

        vkCmdBuildAccelerationStructuresKHR(cmd.buffer(), buildInfos, stack.pointers(buildRanges));

        vkCmdPipelineBarrier(cmd.buffer(), VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, 0, VkMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR), null, null);

        cmd.addAccelerationStructureRef(structure);
        // Important: Keep reference in command buffer so it stays alive during execution
        cmd.addBufferRef(reusableScratchBuffer);
        // DO NOT close reusableScratchBuffer here, we keep it for reuse

        return structure;
    }

    List<BLASResult> buildBlas(List<Pair<RenderLayer, BufferBuilder.BuiltBuffer>> renders, VCmdBuff cmd) {
        long combined_size = 0;
        TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
        for (var type : renders) {
//            if (((RenderLayer.MultiPhase) type.getLeft()).phases.texture instanceof RenderPhase.Textures) {
//                throw new IllegalStateException("Multi texture not supported");
//            }
//            var textureId = ((RenderLayer.MultiPhase) type.getLeft()).phases.texture.getId().get();
//            var texture = textureManager.getTexture(textureId);
//            var vkImage = ((IVGImage) texture).getVGImage();
//            if (vkImage == null) {
//                throw new IllegalStateException("Vulkan texture not created for render layer " + type.getLeft());
//            }
            if (!type.getRight().getParameters().format().equals(IrisVertexFormats.ENTITY)) {
                throw new IllegalStateException("Unknown vertex format used");
            }
            combined_size += type.getRight().getVertexBuffer().remaining() + 256;//Add just some buffer so we can do alignment etc
        }
        //Each render layer gets its own geometry entry in the blas

        //TODO: PUT THE BINDLESS TEXTURE REFERENCE AT THE START OF THE render layers geometry buffer
        var geometryBufferStaging = ctx.memory.createBuffer(
                combined_size,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                0,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);

        // Reuse geometry buffer logic
        if (reusableGeometryBuffer == null || reusableGeometryBuffer.get().size() < combined_size) {
            if (reusableGeometryBuffer != null) {
                reusableGeometryBuffer.close();
            }
            reusableGeometryBuffer = ctx.memory.createBuffer(
                    combined_size,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            reusableGeometryBuffer.get().setDebugUtilsObjectName("Entity BLAS Geometry Buffer");
        } else {
            // Add barrier to ensure previous frame's AS build read is complete before we overwrite with transfer
            // We use a manual barrier here because VCmdBuff helper doesn't support the specific access flags we need for WAR dependency
            try (var stack = MemoryStack.stackPush()) {
                var barrier = VkBufferMemoryBarrier.calloc(1, stack);
                barrier.get(0).sType$Default()
                        .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .buffer(reusableGeometryBuffer.get().buffer())
                        .offset(0)
                        .size(reusableGeometryBuffer.get().size());

                vkCmdPipelineBarrier(cmd.buffer(),
                        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, null, barrier, null);
            }
            cmd.addBufferRef(reusableGeometryBuffer);
        }

        long ptr = geometryBufferStaging.get().map();
        long offset = 0;
        List<BuildInfo> infos = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();
        for (var pair : renders) {
            offset = VUtil.alignUp(offset, 128);
            MemoryUtil.memCopy(MemoryUtil.memAddress(pair.getRight().getVertexBuffer()), ptr + offset, pair.getRight().getVertexBuffer().remaining());
            infos.add(new BuildInfo(pair.getRight().getParameters().format(), pair.getRight().getParameters().indexCount() / 6, reusableGeometryBuffer.get().deviceAddress() + offset));
            offsets.add(offset);

            offset += pair.getRight().getVertexBuffer().remaining();
        }
        cmd.addBufferRef(geometryBufferStaging);
        geometryBufferStaging.get().unmap();

        cmd.encodeBufferCopy(geometryBufferStaging, 0, reusableGeometryBuffer, 0, combined_size);
        cmd.encodeBufferBarrier(reusableGeometryBuffer, 0, combined_size, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);
        geometryBufferStaging.close();

        VRef<VAccelerationStructure> blas;
        try (var stack = MemoryStack.stackPush()) {
            int[] primitiveCounts = new int[infos.size()];
            var buildInfo = populateBuildStructs(ctx, stack, cmd, infos, primitiveCounts);

            blas = executeBlasBuild(ctx, cmd, stack, buildInfo, primitiveCounts);
        }

        // Return a NEW reference to the reused buffer so the caller can close it without affecting our cached reference
        // Note: The caller (AccelerationTLASManager) will close this reference, but we still hold ours in `reusableGeometryBuffer`
        return List.of(new BLASResult(blas, reusableGeometryBuffer.addRef(), offsets));
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

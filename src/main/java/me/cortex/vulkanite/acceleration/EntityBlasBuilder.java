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
    private final FrameData[] frames = new FrameData[3];
    private int frameIndex = 0;

    public EntityBlasBuilder(VContext context) {
        this.ctx = context;
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new FrameData();
        }
    }

    public void free() {
        for (FrameData frame : frames) {
            frame.free();
        }
    }

    private class FrameData {
        private VRef<VBuffer> geometryStaging;
        private VRef<VBuffer> geometry;
        private VRef<VAccelerationStructure> blas;
        private VRef<VBuffer> scratch;

        void free() {
            if (geometryStaging != null) geometryStaging.close();
            if (geometry != null) geometry.close();
            if (blas != null) blas.close();
            if (scratch != null) scratch.close();
        }

        void ensureGeometry(long size) {
            if (geometry == null || geometry.get().size() < size) {
                if (geometry != null) geometry.close();
                // 1.5x growth factor
                long newSize = (long) (Math.max(size, geometry == null ? 0 : geometry.get().size()) * 1.5);
                // Ensure at least the requested size if 1.5x is somehow smaller (e.g. 0 start)
                newSize = Math.max(newSize, size);

                geometry = ctx.memory.createBuffer(
                        newSize,
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            }

            if (geometryStaging == null || geometryStaging.get().size() < size) {
                if (geometryStaging != null) geometryStaging.close();
                long newSize = (long) (Math.max(size, geometryStaging == null ? 0 : geometryStaging.get().size()) * 1.5);
                newSize = Math.max(newSize, size);

                geometryStaging = ctx.memory.createBuffer(
                        newSize,
                        VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                        VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                        0,
                        VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
            }
        }

        void ensureBlas(long size, long scratchSize) {
            if (blas == null || blas.get().size < size) {
                if (blas != null) blas.close();
                // 1.5x growth
                long newSize = (long) (Math.max(size, blas == null ? 0 : blas.get().size) * 1.5);
                newSize = Math.max(newSize, size);

                blas = ctx.memory.createAcceleration(newSize, 256,
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            }

            if (scratch == null || scratch.get().size() < scratchSize) {
                if (scratch != null) scratch.close();
                long newSize = (long) (Math.max(scratchSize, scratch == null ? 0 : scratch.get().size()) * 1.5);
                newSize = Math.max(newSize, scratchSize);

                scratch = ctx.memory.createBuffer(newSize,
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 256, 0);
            }
        }
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

        FrameData frame = frames[frameIndex];
        frameIndex = (frameIndex + 1) % frames.length;

        frame.ensureGeometry(combined_size);

        var geometryBufferStaging = frame.geometryStaging;
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
        cmd.addBufferRef(geometryBufferStaging.addRef()); // Keep alive for command buffer
        geometryBufferStaging.get().unmap();

        cmd.encodeBufferCopy(geometryBufferStaging, 0, geometryBuffer, 0, combined_size);
        cmd.encodeBufferBarrier(geometryBuffer, 0, combined_size, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);

        // geometryBufferStaging is NOT closed here because it belongs to FrameData.
        // The VCmdBuff holds a reference (added above) which protects it during execution.
        // FrameData holds a reference which keeps it alive for reuse.

        VRef<VAccelerationStructure> blas;
        try (var stack = MemoryStack.stackPush()) {
            int[] primitiveCounts = new int[infos.size()];
            var geometryInfos = populateBuildStructs(ctx, stack, cmd, infos, primitiveCounts);

            // Query sizes
            var buildInfos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            var buildRanges = VkAccelerationStructureBuildRangeInfoKHR.calloc(primitiveCounts.length, stack);
            for (int primCount : primitiveCounts) {
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
                    primitiveCounts,
                    buildSizesInfo);

            frame.ensureBlas(buildSizesInfo.accelerationStructureSize(), buildSizesInfo.buildScratchSize());

            blas = frame.blas;
            var scratch = frame.scratch;

            bi.scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(scratch.get().deviceAddress()));
            bi.dstAccelerationStructure(blas.get().structure);

            buildInfos.rewind();
            buildRanges.rewind();

            vkCmdBuildAccelerationStructuresKHR(cmd.buffer(), buildInfos, stack.pointers(buildRanges));

            vkCmdPipelineBarrier(cmd.buffer(), VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, 0, VkMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                    .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR), null, null);

            cmd.addAccelerationStructureRef(blas.addRef());
            cmd.addBufferRef(scratch.addRef());
        }

        // Return new references to the reused buffers/structures.
        // The caller will close() them, which decrements the ref count, but FrameData keeps them alive.
        return List.of(new BLASResult(blas.addRef(), geometryBuffer.addRef(), offsets));
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

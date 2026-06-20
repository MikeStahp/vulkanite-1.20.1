package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.client.rendering.EntityCapture;
import me.cortex.vulkanite.acceleration.blas.BLASBuildPolicy;
import me.cortex.vulkanite.client.config.VulkaniteConfig;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.other.VUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryTrianglesDataKHR;
import org.lwjgl.vulkan.VkDeviceOrHostAddressKHR;
import org.lwjgl.vulkan.VkDeviceOrHostAddressConstKHR;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Builds one BLAS per captured entity, matching Radiance's per-entity transform
 * model instead of merging unrelated models at the origin.
 */
public final class EntityBlasBuilder {
    private static final int MIN_CACHED_ENTITY_BLAS = 32;
    private static final int MAX_CACHED_ENTITY_BLAS = 2048;

    private final VContext ctx;
    private final LinkedHashMap<EntityGeometryKey, CacheEntry> cache =
            new LinkedHashMap<>(64, 0.75f, true);

    public EntityBlasBuilder(VContext context) {
        this.ctx = context;
    }

    List<BLASResult> buildBlas(List<EntityCapture.EntityRenderData> entities, VCmdBuff cmd) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        List<BLASResult> results = new ArrayList<>(entities.size());
        for (EntityCapture.EntityRenderData entity : entities) {
            BLASResult result = buildEntity(entity, cmd);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    private BLASResult buildEntity(EntityCapture.EntityRenderData entity, VCmdBuff cmd) {
        List<EntityCapture.Geometry> sourceGeometries = entity.geometries();
        int validGeometryCount = 0;
        for (EntityCapture.Geometry geometry : sourceGeometries) {
            if (geometry.quadCount() > 0) {
                validGeometryCount++;
            }
        }
        if (validGeometryCount == 0) {
            return null;
        }
        List<EntityCapture.Geometry> geometries = sourceGeometries;
        if (validGeometryCount != sourceGeometries.size()) {
            geometries = new ArrayList<>(validGeometryCount);
            for (EntityCapture.Geometry geometry : sourceGeometries) {
                if (geometry.quadCount() > 0) {
                    geometries.add(geometry);
                }
            }
        }

        EntityGeometryKey key = null;
        if (entity.cacheable()) {
            key = EntityGeometryKey.from(geometries);
            CacheEntry cached = cache.get(key);
            if (cached != null) {
                return cached.resultAt(entity.x(), entity.y(), entity.z());
            }
        }

        long combinedSize = 0;
        for (EntityCapture.Geometry geometry : geometries) {
            combinedSize = VUtil.alignUp(combinedSize, 128);
            combinedSize += geometry.vertices().remaining();
        }

        VRef<VBuffer> staging = ctx.memory.createBuffer(
                combinedSize,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                0,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
        VRef<VBuffer> geometryBuffer = ctx.memory.createBuffer(
                combinedSize,
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR
                        | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                        | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        long pointer = staging.get().map();
        long offset = 0;
        List<Long> offsets = new ArrayList<>(geometries.size());
        List<BuildInfo> buildInfos = new ArrayList<>(geometries.size());
        for (EntityCapture.Geometry geometry : geometries) {
            offset = VUtil.alignUp(offset, 128);
            MemoryUtil.memCopy(MemoryUtil.memAddress(geometry.vertices()), pointer + offset,
                    geometry.vertices().remaining());
            offsets.add(offset);
            buildInfos.add(new BuildInfo(
                    geometry.quadCount(),
                    geometryBuffer.get().deviceAddress() + offset));
            offset += geometry.vertices().remaining();
        }
        staging.get().unmap();
        cmd.addBufferRef(staging);
        cmd.encodeBufferCopy(staging, 0, geometryBuffer, 0, combinedSize);
        cmd.encodeBufferBarrier(geometryBuffer, 0, combinedSize, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);
        staging.close();

        VRef<VAccelerationStructure> blas;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int[] primitiveCounts = new int[buildInfos.size()];
            VkAccelerationStructureGeometryKHR.Buffer geometryInfo =
                    populateBuildStructs(stack, cmd, buildInfos, primitiveCounts);
            blas = executeBlasBuild(cmd, stack, geometryInfo, primitiveCounts);
        }

        if (key != null) {
            CacheEntry entry = new CacheEntry(blas.addRef(), geometryBuffer.addRef(), List.copyOf(offsets));
            putCache(key, entry);
        }

        return new BLASResult(blas, geometryBuffer, offsets, entity.x(), entity.y(), entity.z());
    }

    private void putCache(EntityGeometryKey key, CacheEntry entry) {
        CacheEntry previous = cache.put(key, entry);
        if (previous != null) {
            previous.close();
        }

        int targetSize = Math.max(MIN_CACHED_ENTITY_BLAS,
                Math.min(MAX_CACHED_ENTITY_BLAS, VulkaniteConfig.getInstance().rtxEntityBlasCacheSize));
        while (cache.size() > targetSize) {
            Map.Entry<EntityGeometryKey, CacheEntry> eldest = cache.entrySet().iterator().next();
            cache.remove(eldest.getKey());
            eldest.getValue().close();
        }
    }

    public void clearCache() {
        for (CacheEntry entry : cache.values()) {
            entry.close();
        }
        cache.clear();
    }

    private VkAccelerationStructureGeometryKHR.Buffer populateBuildStructs(
            MemoryStack stack,
            VCmdBuff cmd,
            List<BuildInfo> geometries,
            int[] primitiveCounts) {
        VkAccelerationStructureGeometryKHR.Buffer geometryInfos =
                VkAccelerationStructureGeometryKHR.calloc(geometries.size(), stack);

        for (int i = 0; i < geometries.size(); i++) {
            BuildInfo geometry = geometries.get(i);
            VRef<VBuffer> indexBuffer = SharedQuadVkIndexBuffer.getIndexBuffer(ctx, cmd, geometry.quadCount());

            geometryInfos.get(i)
                    .sType$Default()
                    .geometry(VkAccelerationStructureGeometryDataKHR.calloc(stack)
                            .triangles(VkAccelerationStructureGeometryTrianglesDataKHR.calloc(stack)
                                    .sType$Default()
                                    .vertexData(VkDeviceOrHostAddressConstKHR.calloc(stack)
                                            .deviceAddress(geometry.address()))
                                    .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                                    .vertexStride(EntityCapture.VERTEX_STRIDE)
                                    .maxVertex(geometry.quadCount() * 4 - 1)
                                    .indexData(indexBuffer.get().deviceAddressConst())
                                    .indexType(SharedQuadVkIndexBuffer.TYPE)))
                    .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .flags(0);
            primitiveCounts[i] = geometry.quadCount() * 2;
            cmd.addBufferRef(indexBuffer);
            indexBuffer.close();
        }
        return geometryInfos;
    }

    private VRef<VAccelerationStructure> executeBlasBuild(
            VCmdBuff cmd,
            MemoryStack stack,
            VkAccelerationStructureGeometryKHR.Buffer geometryInfos,
            int[] primitiveCounts) {
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfos =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        VkAccelerationStructureBuildRangeInfoKHR.Buffer buildRanges =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(primitiveCounts.length, stack);
        for (int i = 0; i < primitiveCounts.length; i++) {
            buildRanges.get(i).primitiveCount(primitiveCounts[i]);
        }

        VkAccelerationStructureBuildGeometryInfoKHR buildInfo = buildInfos.get(0)
                .sType$Default()
                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(BLASBuildPolicy.dynamicEntityBuildFlags())
                .pGeometries(geometryInfos)
                .geometryCount(geometryInfos.remaining());

        VkAccelerationStructureBuildSizesInfoKHR sizes =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(
                ctx.device,
                VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo,
                primitiveCounts,
                sizes);

        VRef<VAccelerationStructure> structure = ctx.memory.createAcceleration(
                sizes.accelerationStructureSize(),
                256,
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        VRef<VBuffer> scratch = ctx.memory.createBuffer(
                sizes.buildScratchSize(),
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                256,
                0);

        buildInfo.scratchData(VkDeviceOrHostAddressKHR.calloc(stack)
                        .deviceAddress(scratch.get().deviceAddress()))
                .dstAccelerationStructure(structure.get().structure);
        vkCmdBuildAccelerationStructuresKHR(cmd.buffer(), buildInfos, stack.pointers(buildRanges));
        vkCmdPipelineBarrier(
                cmd.buffer(),
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                0,
                VkMemoryBarrier.calloc(1, stack)
                        .sType$Default()
                        .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                        .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR),
                null,
                null);

        cmd.addAccelerationStructureRef(structure);
        cmd.addBufferRef(scratch);
        scratch.close();
        return structure;
    }

    public record BLASResult(
            VRef<VAccelerationStructure> structure,
            VRef<VBuffer> geometry,
            List<Long> offsets,
            double x,
            double y,
            double z) {
    }

    private record BuildInfo(int quadCount, long address) {
    }

    private record CacheEntry(
            VRef<VAccelerationStructure> structure,
            VRef<VBuffer> geometry,
            List<Long> offsets) {
        BLASResult resultAt(double x, double y, double z) {
            return new BLASResult(structure.addRef(), geometry.addRef(), offsets, x, y, z);
        }

        void close() {
            structure.close();
            geometry.close();
        }
    }

    private record EntityGeometryKey(long hash, int totalBytes, int geometryCount, int totalQuads) {
        static EntityGeometryKey from(List<EntityCapture.Geometry> geometries) {
            long hash = 0xcbf29ce484222325L;
            int totalBytes = 0;
            int totalQuads = 0;

            for (EntityCapture.Geometry geometry : geometries) {
                ByteBuffer vertices = geometry.vertices().duplicate();
                int remaining = vertices.remaining();
                totalBytes += remaining;
                totalQuads += geometry.quadCount();
                hash = mixString(hash, geometry.textureId().toString());
                hash = mix(hash, remaining);
                hash = mix(hash, geometry.quadCount());
                for (int i = vertices.position(); i < vertices.limit(); i++) {
                    hash ^= vertices.get(i) & 0xFFL;
                    hash *= 0x100000001b3L;
                }
            }

            return new EntityGeometryKey(hash, totalBytes, geometries.size(), totalQuads);
        }

        private static long mix(long hash, int value) {
            hash ^= value & 0xFFFFFFFFL;
            return hash * 0x100000001b3L;
        }

        private static long mixString(long hash, String value) {
            for (int i = 0; i < value.length(); i++) {
                hash ^= value.charAt(i);
                hash *= 0x100000001b3L;
            }
            return hash;
        }
    }
}

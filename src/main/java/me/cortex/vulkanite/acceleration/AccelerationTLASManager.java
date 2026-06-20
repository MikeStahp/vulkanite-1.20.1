package me.cortex.vulkanite.acceleration;

//TLAS manager, ingests blas build requests and manages builds and syncs the tlas

import me.cortex.vulkanite.acceleration.blas.BLASBuildResult;
import me.cortex.vulkanite.acceleration.tlas.TLASSectionManager;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.vulkanite.client.rendering.EntityCapture;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.*;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.other.VQueryPool;
import me.cortex.vulkanite.acceleration.tlas.TLASSectionHolder;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import org.joml.Matrix4x3f;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages the Top-Level Acceleration Structure (TLAS) for ray tracing.
 * Coordinates section updates, entity geometry, and TLAS building.
 */
public class AccelerationTLASManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccelerationTLASManager.class);
    private static final int TLAS_BUILD_SLOTS = 3;
    private static final int TLAS_TIMESTAMP_QUERIES_PER_SLOT = 2;
    private static final int TLAS_TIMESTAMP_START = 0;
    private static final int TLAS_TIMESTAMP_END = 1;
    private static final long INFO_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final long SLOW_TLAS_ENCODE_LOG_NANOS = 2_000_000L;

    private final EntityBlasBuilder entityBlasBuilder;
    private final TLASSectionManager buildDataManager;
    private final VContext context;
    private final int queue;
    private final TlasBuildSlot[] tlasBuildSlots = new TlasBuildSlot[TLAS_BUILD_SLOTS];
    private final VRef<VQueryPool> tlasTimestampQueryPool;
    private int tlasBuildCursor = 0;
    private EntityCapture.Frame entityData;
    private VRef<VAccelerationStructure> cachedTlas;
    private List<VRef<TLASSectionHolder>> cachedTransientHolders = List.of();
    private boolean tlasDirty = true;
    private long lastTlasInfoLogNanos;

    public AccelerationTLASManager(VContext context, int queue) {
        this.context = context;
        this.queue = queue;
        this.buildDataManager = new TLASSectionManager(context);
        this.buildDataManager.resizeBindlessSet(0);
        this.entityBlasBuilder = new EntityBlasBuilder(context);
        this.tlasTimestampQueryPool = VQueryPool.create(context.device,
                TLAS_BUILD_SLOTS * TLAS_TIMESTAMP_QUERIES_PER_SLOT, VK_QUERY_TYPE_TIMESTAMP);
    }

    /**
     * Updates sections with new BLAS build results.
     */
    public void updateSections(List<BLASBuildResult> results) {
        for (var result : results) {
            buildDataManager.update(result);
        }
        if (!results.isEmpty()) {
            tlasDirty = true;
        }
    }

    public void setEntityData(EntityCapture.Frame data) {
        boolean hadEntityData = entityData != null;
        if (entityData != null) {
            entityData.close();
        }
        this.entityData = data;
        if (data != null || hadEntityData) {
            tlasDirty = true;
        }
    }

    public List<VRef<VGImage>> getEntityTextureImages() {
        if (entityData == null) {
            return List.of();
        }
        List<VRef<VGImage>> textures = entityData.textureRefs();
        ArrayList<VRef<VGImage>> refs = new ArrayList<>(textures.size());
        for (VRef<VGImage> texture : textures) {
            refs.add(texture.addRef());
        }
        return refs;
    }

    public void removeSection(RenderSection section) {
        buildDataManager.remove(section);
        tlasDirty = true;
    }

    /**
     * Builds the TLAS for the current frame.
     * 
     * @param cmd Command buffer to record build commands into
     * @return Reference to the built acceleration structure
     */
    public VRef<VAccelerationStructure> buildTLAS(VCmdBuff cmd) {
        RenderSystem.assertOnRenderThread();
        long startNanos = System.nanoTime();

        if (!tlasDirty) {
            if (cachedTlas != null) {
                cmd.addAccelerationStructureRef(cachedTlas);
            }
            return cachedTlas == null ? null : cachedTlas.addRef();
        }

        List<VRef<TLASSectionHolder>> transientHolders = new ArrayList<>();
        boolean installedTransientHolders = false;
        try (var stack = stackPush()) {
            VkAccelerationStructureGeometryKHR geometry = VkAccelerationStructureGeometryKHR.calloc(stack);

            // Process entity geometry
            if (entityData != null) {
                var entityBuild = entityBlasBuilder.buildBlas(entityData.entities(), cmd);

                for (var entityBatch : entityBuild) {
                    if (entityBatch.offsets().isEmpty()) {
                        continue;
                    }

                    var entityASI = VkAccelerationStructureInstanceKHR.calloc(stack)
                            .mask(0xFF)
                            .instanceShaderBindingTableRecordOffset(1);
                    entityASI.transform().matrix(new Matrix4x3f()
                            .translate((float) entityBatch.x(), (float) entityBatch.y(), (float) entityBatch.z())
                            .getTransposed(stack.mallocFloat(12)));

                    transientHolders.add(buildDataManager.addEphemeralInstance(entityASI,
                            entityBatch.structure(), entityBatch.geometry(), entityBatch.offsets()));
                    entityBatch.geometry().close();
                    entityBatch.structure().close();
                }
            }

            // Get instance buffer (also builds/updates the geometry desc set)
            var rets = buildDataManager.getInstanceBuffer();
            var instanceBuffer = rets.getLeft();
            int numInstances = rets.getRight();
            if (numInstances == 0) {
                instanceBuffer.close();
                replaceCachedTlas(cmd, null, List.of());
                installedTransientHolders = true;
                tlasDirty = false;
                logTlasEncode("no instances", 0, 0, System.nanoTime() - startNanos);
                return null;
            }

            // Let the cmd buffer manage the lifetime of the holders & desc set entries
            for (var holderRef : buildDataManager.activeSections.values()) {
                cmd.moveRefGeneric(holderRef.addRefGeneric());
            }

            geometry.sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                    .flags(0);

            geometry.geometry()
                    .instances()
                    .sType$Default()
                    .arrayOfPointers(false);

            geometry.geometry()
                    .instances()
                    .data()
                    .deviceAddress(instanceBuffer.get().deviceAddress());

            // TLAS always rebuild & PREFER_FAST_TRACE according to Nvidia
            var buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack)
                    .sType$Default()
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .pGeometries(VkAccelerationStructureGeometryKHR.create(geometry.address(), 1))
                    .geometryCount(1);

            VkAccelerationStructureBuildSizesInfoKHR buildSizesInfo = VkAccelerationStructureBuildSizesInfoKHR
                    .calloc(stack)
                    .sType$Default();

            int[] instanceCounts = new int[] { numInstances };
            vkGetAccelerationStructureBuildSizesKHR(
                    context.device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo.get(0),
                    stack.ints(instanceCounts),
                    buildSizesInfo);

            TlasBuildSlot slot = acquireTlasBuildSlot(
                    buildSizesInfo.accelerationStructureSize(),
                    buildSizesInfo.buildScratchSize());
            logCompletedTlasGpuTiming(slot);
            var tlas = slot.tlas.addRef();
            var scratchBuffer = slot.scratch.addRef();

            buildInfo.dstAccelerationStructure(tlas.get().structure)
                    .scratchData(VkDeviceOrHostAddressKHR.calloc(stack)
                            .deviceAddress(scratchBuffer.get().deviceAddress()));

            var buildRanges = VkAccelerationStructureBuildRangeInfoKHR.calloc(instanceCounts.length, stack);
            for (int count : instanceCounts) {
                buildRanges.get().primitiveCount(count);
            }
            buildRanges.rewind();

            encodeInstanceBufferBuildBarrier(cmd, instanceBuffer,
                    VkAccelerationStructureInstanceKHR.SIZEOF * (long) numInstances, stack);

            int timestampBase = slot.index * TLAS_TIMESTAMP_QUERIES_PER_SLOT;
            cmd.resetQueryPool(tlasTimestampQueryPool, timestampBase, TLAS_TIMESTAMP_QUERIES_PER_SLOT);
            cmd.writeTimestamp(tlasTimestampQueryPool, timestampBase + TLAS_TIMESTAMP_START,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
            vkCmdBuildAccelerationStructuresKHR(cmd.buffer(),
                    buildInfo,
                    stack.pointers(buildRanges));
            cmd.writeTimestamp(tlasTimestampQueryPool, timestampBase + TLAS_TIMESTAMP_END,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);
            slot.timestampWritten = true;
            cmd.addBufferRef(instanceBuffer);
            cmd.addBufferRef(scratchBuffer);
            cmd.addAccelerationStructureRef(tlas);
            instanceBuffer.close();
            scratchBuffer.close();

            encodeTlasBuildShaderBarrier(cmd, stack);

            replaceCachedTlas(cmd, tlas.addRef(), transientHolders);
            installedTransientHolders = true;
            tlasDirty = false;
            logTlasEncode("build", numInstances, transientHolders.size(), System.nanoTime() - startNanos);
            return tlas;
        } finally {
            if (!installedTransientHolders) {
                closeTransientHolders(transientHolders);
            }
        }
    }

    private void replaceCachedTlas(VCmdBuff cmd, VRef<VAccelerationStructure> replacement,
            List<VRef<TLASSectionHolder>> transientHolders) {
        if (cachedTlas != null) {
            cachedTlas.close();
        }
        retireCachedTransientHolders(cmd);
        cachedTlas = replacement;
        cachedTransientHolders = List.copyOf(transientHolders);
    }

    private void retireCachedTransientHolders(VCmdBuff cmd) {
        for (VRef<TLASSectionHolder> holder : cachedTransientHolders) {
            cmd.moveRefGeneric(holder.addRefGeneric());
            holder.close();
        }
        cachedTransientHolders = List.of();
    }

    private static void closeTransientHolders(List<VRef<TLASSectionHolder>> holders) {
        for (VRef<TLASSectionHolder> holder : holders) {
            holder.close();
        }
    }

    private TlasBuildSlot acquireTlasBuildSlot(long tlasSize, long scratchSize) {
        int slotIndex = tlasBuildCursor;
        tlasBuildCursor = (tlasBuildCursor + 1) % TLAS_BUILD_SLOTS;

        TlasBuildSlot slot = tlasBuildSlots[slotIndex];
        if (slot == null) {
            slot = new TlasBuildSlot(slotIndex);
            tlasBuildSlots[slotIndex] = slot;
        }

        if (slot.tlas == null || slot.tlasCapacity < tlasSize) {
            if (slot.tlas != null) {
                slot.tlas.close();
            }

            slot.tlasCapacity = roundUpCapacity(tlasSize);
            slot.tlas = context.memory.createAcceleration(slot.tlasCapacity,
                    256,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
        }

        if (slot.scratch == null || slot.scratchCapacity < scratchSize) {
            if (slot.scratch != null) {
                slot.scratch.close();
            }

            slot.scratchCapacity = roundUpCapacity(scratchSize);
            slot.scratch = context.memory.createBuffer(slot.scratchCapacity,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 256, 0);
            slot.scratch.get().setDebugUtilsObjectName("TLAS Scratch Buffer " + slotIndex);
        }

        return slot;
    }

    private static long roundUpCapacity(long size) {
        size = Math.max(size, 4096L);
        long highest = Long.highestOneBit(size);
        if (highest == size) {
            return size;
        }
        return highest << 1;
    }

    private static void encodeInstanceBufferBuildBarrier(
            VCmdBuff cmd,
            VRef<VBuffer> instanceBuffer,
            long size,
            org.lwjgl.system.MemoryStack stack) {
        var barrier = VkBufferMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(instanceBuffer.get().buffer())
                .offset(0)
                .size(size);

        vkCmdPipelineBarrier(
                cmd.buffer(),
                VK_PIPELINE_STAGE_HOST_BIT,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                0,
                null,
                barrier,
                null);
        cmd.addBufferRef(instanceBuffer);
    }

    private static void encodeTlasBuildShaderBarrier(VCmdBuff cmd, org.lwjgl.system.MemoryStack stack) {
        var barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);

        vkCmdPipelineBarrier(
                cmd.buffer(),
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                0,
                barrier,
                null,
                null);
    }

    private void logTlasEncode(String reason, int instances, int transientInstances, long cpuNanos) {
        long now = System.nanoTime();
        boolean info = cpuNanos >= SLOW_TLAS_ENCODE_LOG_NANOS
                || now - lastTlasInfoLogNanos >= INFO_LOG_INTERVAL_NANOS;
        if (info) {
            lastTlasInfoLogNanos = now;
            LOGGER.info("[Vulkanite] TLAS encode: reason={}, instances={}, activeSections={}, transientInstances={}, cpu={} ms",
                    reason, instances, buildDataManager.activeSections.size(), transientInstances,
                    formatMillis(cpuNanos));
        } else {
            LOGGER.debug("[Vulkanite] TLAS encode: reason={}, instances={}, activeSections={}, transientInstances={}, cpu={} ms",
                    reason, instances, buildDataManager.activeSections.size(), transientInstances,
                    formatMillis(cpuNanos));
        }
    }

    private void logCompletedTlasGpuTiming(TlasBuildSlot slot) {
        if (!slot.timestampWritten) {
            return;
        }

        int timestampBase = slot.index * TLAS_TIMESTAMP_QUERIES_PER_SLOT;
        long[] timestamps = tlasTimestampQueryPool.get().getResultsLongIfAvailable(
                timestampBase, TLAS_TIMESTAMP_QUERIES_PER_SLOT);
        if (timestamps == null) {
            return;
        }

        long gpuNanos = timestampDeltaNanos(timestamps[TLAS_TIMESTAMP_START], timestamps[TLAS_TIMESTAMP_END]);
        slot.timestampWritten = false;

        long now = System.nanoTime();
        boolean info = gpuNanos >= SLOW_TLAS_ENCODE_LOG_NANOS
                || now - lastTlasInfoLogNanos >= INFO_LOG_INTERVAL_NANOS;
        if (info) {
            lastTlasInfoLogNanos = now;
            LOGGER.info("[Vulkanite] TLAS gpu: slot={}, gpuBuild={} ms",
                    slot.index, formatMillis(gpuNanos));
        } else {
            LOGGER.debug("[Vulkanite] TLAS gpu: slot={}, gpuBuild={} ms",
                    slot.index, formatMillis(gpuNanos));
        }
    }

    private long timestampDeltaNanos(long start, long end) {
        if (end <= start) {
            return 0L;
        }
        return Math.round((end - start) * (double) context.properties.timestampPeriodNanos);
    }

    private static class TlasBuildSlot {
        private final int index;
        private VRef<VAccelerationStructure> tlas;
        private long tlasCapacity;
        private VRef<VBuffer> scratch;
        private long scratchCapacity;
        private boolean timestampWritten;

        private TlasBuildSlot(int index) {
            this.index = index;
        }
    }

    public VRef<VDescriptorSet> getGeometrySet() {
        return buildDataManager.geometryBufferDescSet.addRef();
    }

    public VRef<VDescriptorSetLayout> getGeometryLayout() {
        return buildDataManager.getGeometryLayout();
    }

    public void destroy() {
        if (cachedTlas != null) {
            cachedTlas.close();
            cachedTlas = null;
        }
        closeTransientHolders(cachedTransientHolders);
        cachedTransientHolders = List.of();
        if (entityData != null) {
            entityData.close();
            entityData = null;
        }
        entityBlasBuilder.clearCache();
        buildDataManager.destroy();
        tlasTimestampQueryPool.close();
        for (TlasBuildSlot slot : tlasBuildSlots) {
            if (slot == null) {
                continue;
            }
            if (slot.tlas != null) {
                slot.tlas.close();
                slot.tlas = null;
            }
            if (slot.scratch != null) {
                slot.scratch.close();
                slot.scratch = null;
            }
        }
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }
}

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
import me.cortex.vulkanite.lib.other.GpuTimestampMath;
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
    private static final int HYBRID_TLAS_TIMING_SLOTS = 8;
    private static final int HYBRID_TLAS_TIMING_QUERIES_PER_SLOT = 2;
    private static final int HYBRID_TLAS_TIMING_START = 0;
    private static final int HYBRID_TLAS_TIMING_END = 1;
    private static final boolean PHASE7_GPU_TIMING = Boolean.getBoolean("vulkanite.phase7GpuTiming");
    private static final long INFO_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final long SLOW_TLAS_ENCODE_LOG_NANOS = 2_000_000L;

    private final EntityBlasBuilder entityBlasBuilder;
    private final TLASSectionManager buildDataManager;
    private final VContext context;
    private final int queue;
    private final TlasBuildSlot[] tlasBuildSlots = new TlasBuildSlot[TLAS_BUILD_SLOTS];
    private final TlasBuildSlot[] proceduralTlasBuildSlots = new TlasBuildSlot[TLAS_BUILD_SLOTS];
    private final TlasBuildSlot[] hybridShadowTlasBuildSlots = new TlasBuildSlot[TLAS_BUILD_SLOTS];
    private final VRef<VQueryPool> tlasTimestampQueryPool;
    private final VRef<VQueryPool> hybridTlasTimestampQueryPool;
    private final HybridTlasTimingSlot[] hybridTlasTimingSlots =
            new HybridTlasTimingSlot[HYBRID_TLAS_TIMING_SLOTS];
    private int tlasBuildCursor = 0;
    private int proceduralTlasBuildCursor = 0;
    private int hybridShadowTlasBuildCursor = 0;
    private int hybridTlasTimingCursor;
    private long hybridTlasTimingSequence;
    private long hybridTlasTimingDrops;
    private HybridTlasTimingSlot hybridTlasTimingAwaitingSubmission;
    private EntityCapture.Frame entityData;
    private VRef<VAccelerationStructure> cachedTlas;
    private VRef<VAccelerationStructure> cachedProceduralTlas;
    private VRef<VAccelerationStructure> cachedHybridShadowTlas;
    private List<VRef<TLASSectionHolder>> cachedTransientHolders = List.of();
    private boolean tlasDirty = true;
    private boolean proceduralReflectionEnabled;
    private boolean proceduralTlasDirty = true;
    private boolean hybridShadowTlasDirty = true;
    private boolean hybridTlasEverBuilt;
    private int pendingHybridSectionUpdates;
    private int pendingHybridSectionRemovals;
    private int pendingHybridEntityUpdates;
    private long lastTlasInfoLogNanos;
    private final boolean hybridShadowEnabled =
            HybridAccelerationConfig.fromSystemProperties().hybridShadows();

    public AccelerationTLASManager(VContext context, int queue) {
        this.context = context;
        this.queue = queue;
        this.buildDataManager = new TLASSectionManager(context);
        this.buildDataManager.resizeBindlessSet(0);
        this.entityBlasBuilder = new EntityBlasBuilder(context);
        this.tlasTimestampQueryPool = VQueryPool.create(context.device,
                TLAS_BUILD_SLOTS * TLAS_TIMESTAMP_QUERIES_PER_SLOT, VK_QUERY_TYPE_TIMESTAMP);
        for (int i = 0; i < hybridTlasTimingSlots.length; i++) {
            hybridTlasTimingSlots[i] = new HybridTlasTimingSlot(i);
        }
        if (PHASE7_GPU_TIMING && context.properties.timestampValidBits > 0) {
            this.hybridTlasTimestampQueryPool = VQueryPool.create(
                    context.device,
                    HYBRID_TLAS_TIMING_SLOTS * HYBRID_TLAS_TIMING_QUERIES_PER_SLOT,
                    VK_QUERY_TYPE_TIMESTAMP);
            context.setDebugUtilsObjectName(
                    hybridTlasTimestampQueryPool.get().pool,
                    VK_OBJECT_TYPE_QUERY_POOL,
                    "Phase 7 Hybrid TLAS GPU Timing");
            LOGGER.info("[Vulkanite][Phase7GPU] event=hybrid_tlas_timing_enabled slots={} validBits={} periodNs={}",
                    HYBRID_TLAS_TIMING_SLOTS,
                    context.properties.timestampValidBits,
                    context.properties.timestampPeriodNanos);
        } else {
            this.hybridTlasTimestampQueryPool = null;
            if (PHASE7_GPU_TIMING) {
                LOGGER.warn("[Vulkanite][Phase7GPU] event=hybrid_tlas_timing_unavailable reason=no_timestamp_bits");
            }
        }
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
            proceduralTlasDirty = true;
            hybridShadowTlasDirty = true;
            pendingHybridSectionUpdates = saturatingAdd(pendingHybridSectionUpdates, results.size());
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
            hybridShadowTlasDirty = true;
            pendingHybridEntityUpdates = saturatingAdd(pendingHybridEntityUpdates, 1);
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
        proceduralTlasDirty = true;
        hybridShadowTlasDirty = true;
        pendingHybridSectionRemovals = saturatingAdd(pendingHybridSectionRemovals, 1);
    }

    /**
     * Selects whether the material TLAS may replace regular opaque cube
     * triangles with procedural instances. Callers must validate the active SBT
     * before enabling this; unsupported devices are forced to the triangle path.
     */
    public void setProceduralReflectionEnabled(boolean enabled) {
        boolean effective = enabled && context.capabilities.proceduralAabbBlas();
        if (proceduralReflectionEnabled == effective) {
            return;
        }
        proceduralReflectionEnabled = effective;
        tlasDirty = true;
        LOGGER.info("[Vulkanite][Phase10] event=procedural_reflection_tlas enabled={}", effective);
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
            retainActiveSectionHolders(cmd);
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
                            .instanceShaderBindingTableRecordOffset(HybridSbtLayout.ENTITY_TRIANGLE_HIT_GROUP);
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
            if (proceduralReflectionEnabled) {
                VRef<VBuffer> reflectionBuffer = null;
                int reflectionInstanceCount = 0;
                try {
                    var reflectionInstances = buildDataManager.getHybridReflectionInstanceBuffer();
                    reflectionBuffer = reflectionInstances.getLeft();
                    reflectionInstanceCount = reflectionInstances.getRight();
                } catch (RuntimeException exception) {
                    // The full triangle instance snapshot is still alive here,
                    // so an experimental payload/layout failure can fall back
                    // without invalidating the frame's material TLAS.
                    if (reflectionBuffer != null) {
                        reflectionBuffer.close();
                    }
                    proceduralReflectionEnabled = false;
                    LOGGER.error("[Vulkanite][Phase10] event=procedural_reflection_tlas_fallback reason=instance_snapshot_failed",
                            exception);
                }
                if (reflectionBuffer != null) {
                    instanceBuffer.close();
                    instanceBuffer = reflectionBuffer;
                    numInstances = reflectionInstanceCount;
                }
            }
            if (numInstances == 0) {
                instanceBuffer.close();
                replaceCachedTlas(cmd, null, List.of());
                installedTransientHolders = true;
                tlasDirty = false;
                logTlasEncode("no instances", 0, 0, System.nanoTime() - startNanos);
                return null;
            }

            // Let the cmd buffer manage the lifetime of the holders & desc set entries
            retainActiveSectionHolders(cmd);

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

    /**
     * Builds the opt-in debug TLAS containing only procedural section AABBs.
     * Production rays continue to receive {@link #buildTLAS(VCmdBuff)}.
     */
    public VRef<VAccelerationStructure> buildProceduralTLAS(VCmdBuff cmd) {
        RenderSystem.assertOnRenderThread();
        if (!context.capabilities.proceduralAabbBlas()) {
            return null;
        }

        if (!proceduralTlasDirty) {
            retainActiveSectionHolders(cmd);
            if (cachedProceduralTlas != null) {
                cmd.addAccelerationStructureRef(cachedProceduralTlas);
            }
            return cachedProceduralTlas == null ? null : cachedProceduralTlas.addRef();
        }

        long startNanos = System.nanoTime();
        try (var stack = stackPush()) {
            var instances = buildDataManager.getProceduralInstanceBuffer();
            VRef<VBuffer> instanceBuffer = instances.getLeft();
            int instanceCount = instances.getRight();
            if (instanceCount == 0) {
                instanceBuffer.close();
                replaceCachedProceduralTlas(null);
                proceduralTlasDirty = false;
                LOGGER.debug("[Vulkanite] Procedural debug TLAS: no instances");
                return null;
            }

            retainActiveSectionHolders(cmd);
            var geometry = VkAccelerationStructureGeometryKHR.calloc(stack)
                    .sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                    .flags(0);
            geometry.geometry()
                    .instances()
                    .sType$Default()
                    .arrayOfPointers(false)
                    .data()
                    .deviceAddress(instanceBuffer.get().deviceAddress());

            var buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack)
                    .sType$Default()
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .pGeometries(VkAccelerationStructureGeometryKHR.create(geometry.address(), 1))
                    .geometryCount(1);

            var buildSizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(
                    context.device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo.get(0),
                    stack.ints(instanceCount),
                    buildSizes);

            TlasBuildSlot slot = acquireProceduralTlasBuildSlot(
                    buildSizes.accelerationStructureSize(),
                    buildSizes.buildScratchSize());
            VRef<VAccelerationStructure> tlas = slot.tlas.addRef();
            VRef<VBuffer> scratch = slot.scratch.addRef();
            buildInfo
                    .dstAccelerationStructure(tlas.get().structure)
                    .scratchData(VkDeviceOrHostAddressKHR.calloc(stack)
                            .deviceAddress(scratch.get().deviceAddress()));

            var buildRange = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            buildRange.get(0).primitiveCount(instanceCount);
            encodeInstanceBufferBuildBarrier(
                    cmd,
                    instanceBuffer,
                    VkAccelerationStructureInstanceKHR.SIZEOF * (long) instanceCount,
                    stack);
            vkCmdBuildAccelerationStructuresKHR(cmd.buffer(), buildInfo, stack.pointers(buildRange));

            cmd.addBufferRef(instanceBuffer);
            cmd.addBufferRef(scratch);
            cmd.addAccelerationStructureRef(tlas);
            instanceBuffer.close();
            scratch.close();
            encodeTlasBuildShaderBarrier(cmd, stack);

            replaceCachedProceduralTlas(tlas.addRef());
            proceduralTlasDirty = false;
            LOGGER.debug("[Vulkanite] Procedural debug TLAS: instances={}, cpu={} ms",
                    instanceCount, formatMillis(System.nanoTime() - startNanos));
            return tlas;
        }
    }

    /** Builds the unified production visibility TLAS from the current material-instance snapshot. */
    public VRef<VAccelerationStructure> buildHybridShadowTLAS(int queueId, VCmdBuff cmd) {
        RenderSystem.assertOnRenderThread();
        pollCompletedHybridTlasTimings();
        if (!hybridShadowEnabled || !context.capabilities.proceduralAabbBlas()) {
            buildDataManager.discardHybridShadowInstanceSnapshot();
            return null;
        }
        if (!hybridShadowTlasDirty) {
            retainActiveSectionHolders(cmd);
            if (cachedHybridShadowTlas != null) cmd.addAccelerationStructureRef(cachedHybridShadowTlas);
            // A material-TLAS-only change (for example toggling procedural
            // reflection) may have produced fresh entity ephemerals even while
            // the cached hybrid shadow TLAS remains valid.
            buildDataManager.discardHybridShadowInstanceSnapshot();
            return cachedHybridShadowTlas == null ? null : cachedHybridShadowTlas.addRef();
        }
        long startNanos = System.nanoTime();
        HybridTlasTimingSlot timingSlot = null;
        try (var stack = stackPush()) {
            var instances = buildDataManager.getHybridShadowInstanceBuffer();
            VRef<VBuffer> instanceBuffer = instances.getLeft();
            int instanceCount = instances.getRight();
            int terrainTriangleInstances = buildDataManager.hybridShadowTerrainTriangleCount();
            int proceduralInstances = buildDataManager.hybridShadowProceduralCount();
            int entityInstances = Math.max(
                    0, instanceCount - terrainTriangleInstances - proceduralInstances);
            if (instanceCount == 0) {
                instanceBuffer.close();
                replaceCachedHybridShadowTlas(null);
                hybridShadowTlasDirty = false;
                if (hybridTlasTimestampQueryPool != null) {
                    LOGGER.info("[Vulkanite][Phase7GPU] event=hybrid_tlas_empty op={} reasons={} sectionUpdates={} sectionRemovals={} entityUpdates={} queryDrops={}",
                            hybridTlasEverBuilt ? "rebuild" : "initial",
                            hybridDirtyReasons(),
                            pendingHybridSectionUpdates,
                            pendingHybridSectionRemovals,
                            pendingHybridEntityUpdates,
                            hybridTlasTimingDrops);
                }
                resetHybridDirtyCounters();
                return null;
            }
            retainActiveSectionHolders(cmd);
            var geometry = VkAccelerationStructureGeometryKHR.calloc(stack).sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).flags(0);
            geometry.geometry().instances().sType$Default().arrayOfPointers(false)
                    .data().deviceAddress(instanceBuffer.get().deviceAddress());
            var buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack)
                    .sType$Default().mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .pGeometries(VkAccelerationStructureGeometryKHR.create(geometry.address(), 1))
                    .geometryCount(1);
            var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(context.device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, buildInfo.get(0),
                    stack.ints(instanceCount), sizes);
            TlasBuildSlot slot = acquireHybridShadowTlasBuildSlot(
                    sizes.accelerationStructureSize(), sizes.buildScratchSize());
            VRef<VAccelerationStructure> tlas = slot.tlas.addRef();
            VRef<VBuffer> scratch = slot.scratch.addRef();
            buildInfo.dstAccelerationStructure(tlas.get().structure)
                    .scratchData(VkDeviceOrHostAddressKHR.calloc(stack)
                            .deviceAddress(scratch.get().deviceAddress()));
            var range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(instanceCount);
            encodeInstanceBufferBuildBarrier(cmd, instanceBuffer,
                    VkAccelerationStructureInstanceKHR.SIZEOF * (long) instanceCount, stack);
            timingSlot = beginHybridTlasTiming(
                    queueId,
                    cmd,
                    terrainTriangleInstances,
                    proceduralInstances,
                    entityInstances,
                    instanceCount);
            vkCmdBuildAccelerationStructuresKHR(cmd.buffer(), buildInfo, stack.pointers(range));
            finishHybridTlasTiming(cmd, timingSlot, System.nanoTime() - startNanos);
            cmd.addBufferRef(instanceBuffer);
            cmd.addBufferRef(scratch);
            cmd.addAccelerationStructureRef(tlas);
            instanceBuffer.close();
            scratch.close();
            encodeTlasBuildShaderBarrier(cmd, stack);
            replaceCachedHybridShadowTlas(tlas.addRef());
            hybridShadowTlasDirty = false;
            hybridTlasEverBuilt = true;
            resetHybridDirtyCounters();
            LOGGER.debug("[Vulkanite] Hybrid shadow TLAS: instances={}, cpu={} ms",
                    instanceCount, formatMillis(System.nanoTime() - startNanos));
            return tlas;
        } catch (RuntimeException | Error exception) {
            cancelHybridTlasTiming(timingSlot);
            throw exception;
        }
    }

    public void markHybridTlasTimingSubmitted(long execution) {
        HybridTlasTimingSlot slot = hybridTlasTimingAwaitingSubmission;
        if (slot == null) {
            return;
        }
        if (slot.state != HybridTlasTimingState.RECORDED) {
            throw new IllegalStateException("Hybrid TLAS timing slot was not recorded before submission");
        }
        slot.execution = execution;
        slot.state = HybridTlasTimingState.SUBMITTED;
        hybridTlasTimingAwaitingSubmission = null;
    }

    public void cancelUnsubmittedHybridTlasTiming() {
        HybridTlasTimingSlot slot = hybridTlasTimingAwaitingSubmission;
        if (slot != null) {
            cancelHybridTlasTiming(slot);
        }
    }

    /** Releases per-frame entity instance snapshots when no hybrid shadow TLAS consumes them. */
    public void discardHybridShadowInstanceSnapshot() {
        buildDataManager.discardHybridShadowInstanceSnapshot();
    }

    private void retainActiveSectionHolders(VCmdBuff cmd) {
        for (var holderRef : buildDataManager.activeSections.values()) {
            cmd.moveRefGeneric(holderRef.addRefGeneric());
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

    private void replaceCachedProceduralTlas(VRef<VAccelerationStructure> replacement) {
        if (cachedProceduralTlas != null) {
            cachedProceduralTlas.close();
        }
        cachedProceduralTlas = replacement;
    }

    private void replaceCachedHybridShadowTlas(VRef<VAccelerationStructure> replacement) {
        if (cachedHybridShadowTlas != null) cachedHybridShadowTlas.close();
        cachedHybridShadowTlas = replacement;
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

    private TlasBuildSlot acquireProceduralTlasBuildSlot(long tlasSize, long scratchSize) {
        int slotIndex = proceduralTlasBuildCursor;
        proceduralTlasBuildCursor = (proceduralTlasBuildCursor + 1) % TLAS_BUILD_SLOTS;

        TlasBuildSlot slot = proceduralTlasBuildSlots[slotIndex];
        if (slot == null) {
            slot = new TlasBuildSlot(slotIndex);
            proceduralTlasBuildSlots[slotIndex] = slot;
        }
        if (slot.tlas == null || slot.tlasCapacity < tlasSize) {
            if (slot.tlas != null) {
                slot.tlas.close();
            }
            slot.tlasCapacity = roundUpCapacity(tlasSize);
            slot.tlas = context.memory.createAcceleration(
                    slot.tlasCapacity,
                    256,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                    VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            context.setDebugUtilsObjectName(
                    slot.tlas.get().structure,
                    VK_OBJECT_TYPE_ACCELERATION_STRUCTURE_KHR,
                    "Procedural Debug TLAS " + slotIndex);
        }
        if (slot.scratch == null || slot.scratchCapacity < scratchSize) {
            if (slot.scratch != null) {
                slot.scratch.close();
            }
            slot.scratchCapacity = roundUpCapacity(scratchSize);
            slot.scratch = context.memory.createBuffer(
                    slot.scratchCapacity,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    256,
                    0);
            slot.scratch.get().setDebugUtilsObjectName("Procedural Debug TLAS Scratch " + slotIndex);
        }
        return slot;
    }

    private TlasBuildSlot acquireHybridShadowTlasBuildSlot(long tlasSize, long scratchSize) {
        int index = hybridShadowTlasBuildCursor;
        hybridShadowTlasBuildCursor = (hybridShadowTlasBuildCursor + 1) % TLAS_BUILD_SLOTS;
        TlasBuildSlot slot = hybridShadowTlasBuildSlots[index];
        if (slot == null) hybridShadowTlasBuildSlots[index] = slot = new TlasBuildSlot(index);
        if (slot.tlas == null || slot.tlasCapacity < tlasSize) {
            if (slot.tlas != null) slot.tlas.close();
            slot.tlasCapacity = roundUpCapacity(tlasSize);
            slot.tlas = context.memory.createAcceleration(slot.tlasCapacity, 256,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                    VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            context.setDebugUtilsObjectName(slot.tlas.get().structure,
                    VK_OBJECT_TYPE_ACCELERATION_STRUCTURE_KHR, "Hybrid Shadow TLAS " + index);
        }
        if (slot.scratch == null || slot.scratchCapacity < scratchSize) {
            if (slot.scratch != null) slot.scratch.close();
            slot.scratchCapacity = roundUpCapacity(scratchSize);
            slot.scratch = context.memory.createBuffer(slot.scratchCapacity,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 256, 0);
            slot.scratch.get().setDebugUtilsObjectName("Hybrid Shadow TLAS Scratch " + index);
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

    private HybridTlasTimingSlot beginHybridTlasTiming(
            int queueId,
            VCmdBuff cmd,
            int terrainTriangleInstances,
            int proceduralInstances,
            int entityInstances,
            int totalInstances) {
        if (hybridTlasTimestampQueryPool == null) {
            return null;
        }
        if (hybridTlasTimingAwaitingSubmission != null) {
            hybridTlasTimingDrops++;
            LOGGER.warn("[Vulkanite][Phase7GPU] event=hybrid_tlas_timing_drop reason=uncommitted_recording queryDrops={}",
                    hybridTlasTimingDrops);
            return null;
        }

        HybridTlasTimingSlot slot = null;
        for (int i = 0; i < hybridTlasTimingSlots.length; i++) {
            int index = (hybridTlasTimingCursor + i) % hybridTlasTimingSlots.length;
            if (hybridTlasTimingSlots[index].state == HybridTlasTimingState.FREE) {
                slot = hybridTlasTimingSlots[index];
                hybridTlasTimingCursor = (index + 1) % hybridTlasTimingSlots.length;
                break;
            }
        }
        if (slot == null) {
            hybridTlasTimingDrops++;
            LOGGER.warn("[Vulkanite][Phase7GPU] event=hybrid_tlas_timing_drop reason=query_ring_full queryDrops={}",
                    hybridTlasTimingDrops);
            return null;
        }

        slot.sequence = ++hybridTlasTimingSequence;
        slot.queueId = queueId;
        slot.operation = hybridTlasEverBuilt ? "rebuild" : "initial";
        slot.reasons = hybridDirtyReasons();
        slot.sectionUpdates = pendingHybridSectionUpdates;
        slot.sectionRemovals = pendingHybridSectionRemovals;
        slot.entityUpdates = pendingHybridEntityUpdates;
        slot.terrainTriangleInstances = terrainTriangleInstances;
        slot.proceduralInstances = proceduralInstances;
        slot.entityInstances = entityInstances;
        slot.totalInstances = totalInstances;
        slot.state = HybridTlasTimingState.RECORDING;

        int queryBase = slot.index * HYBRID_TLAS_TIMING_QUERIES_PER_SLOT;
        cmd.resetQueryPool(
                hybridTlasTimestampQueryPool,
                queryBase,
                HYBRID_TLAS_TIMING_QUERIES_PER_SLOT);
        cmd.writeTimestamp(
                hybridTlasTimestampQueryPool,
                queryBase + HYBRID_TLAS_TIMING_START,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);
        return slot;
    }

    private void finishHybridTlasTiming(VCmdBuff cmd, HybridTlasTimingSlot slot, long cpuEncodeNanos) {
        if (slot == null) {
            return;
        }
        if (slot.state != HybridTlasTimingState.RECORDING) {
            throw new IllegalStateException("Hybrid TLAS timing slot is not recording");
        }
        int queryBase = slot.index * HYBRID_TLAS_TIMING_QUERIES_PER_SLOT;
        cmd.writeTimestamp(
                hybridTlasTimestampQueryPool,
                queryBase + HYBRID_TLAS_TIMING_END,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);
        slot.cpuEncodeNanos = cpuEncodeNanos;
        slot.state = HybridTlasTimingState.RECORDED;
        hybridTlasTimingAwaitingSubmission = slot;
    }

    private void pollCompletedHybridTlasTimings() {
        if (hybridTlasTimestampQueryPool == null) {
            return;
        }
        for (HybridTlasTimingSlot slot : hybridTlasTimingSlots) {
            if (slot.state != HybridTlasTimingState.SUBMITTED
                    || context.cmd.getQueueCurrentExecution(slot.queueId) < slot.execution) {
                continue;
            }
            int queryBase = slot.index * HYBRID_TLAS_TIMING_QUERIES_PER_SLOT;
            long[] timestamps = hybridTlasTimestampQueryPool.get().getResultsLongIfAvailable(
                    queryBase,
                    HYBRID_TLAS_TIMING_QUERIES_PER_SLOT);
            if (timestamps == null) {
                continue;
            }

            long gpuTicks = GpuTimestampMath.deltaTicks(
                    timestamps[HYBRID_TLAS_TIMING_START],
                    timestamps[HYBRID_TLAS_TIMING_END],
                    context.properties.timestampValidBits);
            double gpuMillis = gpuTicks * (double) context.properties.timestampPeriodNanos / 1_000_000.0;
            LOGGER.info("[Vulkanite][Phase7GPU] event=hybrid_tlas_sample seq={} execution={} op={} reasons={} sectionUpdates={} sectionRemovals={} entityUpdates={} terrainInstances={} proceduralInstances={} entityInstances={} totalInstances={} gpuTicks={} gpuMs={} cpuEncodeMs={} queryDrops={}",
                    slot.sequence,
                    slot.execution,
                    slot.operation,
                    slot.reasons,
                    slot.sectionUpdates,
                    slot.sectionRemovals,
                    slot.entityUpdates,
                    slot.terrainTriangleInstances,
                    slot.proceduralInstances,
                    slot.entityInstances,
                    slot.totalInstances,
                    gpuTicks,
                    formatGpuMillis(gpuMillis),
                    formatMillis(slot.cpuEncodeNanos),
                    hybridTlasTimingDrops);
            slot.reset();
        }
    }

    private void cancelHybridTlasTiming(HybridTlasTimingSlot slot) {
        if (slot == null) {
            return;
        }
        if (hybridTlasTimingAwaitingSubmission == slot) {
            hybridTlasTimingAwaitingSubmission = null;
        }
        slot.reset();
    }

    private String hybridDirtyReasons() {
        ArrayList<String> reasons = new ArrayList<>(3);
        if (pendingHybridSectionUpdates > 0) {
            reasons.add("section_update");
        }
        if (pendingHybridSectionRemovals > 0) {
            reasons.add("section_remove");
        }
        if (pendingHybridEntityUpdates > 0) {
            reasons.add("entity_update");
        }
        if (reasons.isEmpty()) {
            reasons.add(hybridTlasEverBuilt ? "unspecified" : "initial");
        }
        return String.join("+", reasons);
    }

    private void resetHybridDirtyCounters() {
        pendingHybridSectionUpdates = 0;
        pendingHybridSectionRemovals = 0;
        pendingHybridEntityUpdates = 0;
    }

    private static int saturatingAdd(int value, int increment) {
        return increment > Integer.MAX_VALUE - value ? Integer.MAX_VALUE : value + increment;
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

    private enum HybridTlasTimingState {
        FREE,
        RECORDING,
        RECORDED,
        SUBMITTED
    }

    private static final class HybridTlasTimingSlot {
        private final int index;
        private HybridTlasTimingState state = HybridTlasTimingState.FREE;
        private long sequence;
        private int queueId;
        private long execution;
        private String operation;
        private String reasons;
        private int sectionUpdates;
        private int sectionRemovals;
        private int entityUpdates;
        private int terrainTriangleInstances;
        private int proceduralInstances;
        private int entityInstances;
        private int totalInstances;
        private long cpuEncodeNanos;

        private HybridTlasTimingSlot(int index) {
            this.index = index;
        }

        private void reset() {
            state = HybridTlasTimingState.FREE;
            sequence = 0L;
            queueId = 0;
            execution = 0L;
            operation = null;
            reasons = null;
            sectionUpdates = 0;
            sectionRemovals = 0;
            entityUpdates = 0;
            terrainTriangleInstances = 0;
            proceduralInstances = 0;
            entityInstances = 0;
            totalInstances = 0;
            cpuEncodeNanos = 0L;
        }
    }

    public VRef<VDescriptorSet> getGeometrySet() {
        return buildDataManager.geometryBufferDescSet.addRef();
    }

    public VRef<VDescriptorSetLayout> getGeometryLayout() {
        return buildDataManager.getGeometryLayout();
    }

    public void destroy() {
        pollCompletedHybridTlasTimings();
        if (hybridTlasTimestampQueryPool != null) {
            int pendingTimings = 0;
            for (HybridTlasTimingSlot slot : hybridTlasTimingSlots) {
                if (slot.state != HybridTlasTimingState.FREE) {
                    pendingTimings++;
                }
            }
            LOGGER.info("[Vulkanite][Phase7GPU] event=hybrid_tlas_timing_closed pending={} queryDrops={}",
                    pendingTimings, hybridTlasTimingDrops);
            hybridTlasTimestampQueryPool.close();
        }
        if (cachedTlas != null) {
            cachedTlas.close();
            cachedTlas = null;
        }
        if (cachedProceduralTlas != null) {
            cachedProceduralTlas.close();
            cachedProceduralTlas = null;
        }
        if (cachedHybridShadowTlas != null) {
            cachedHybridShadowTlas.close();
            cachedHybridShadowTlas = null;
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
        for (TlasBuildSlot slot : hybridShadowTlasBuildSlots) {
            if (slot == null) continue;
            if (slot.tlas != null) slot.tlas.close();
            if (slot.scratch != null) slot.scratch.close();
        }
        for (TlasBuildSlot slot : proceduralTlasBuildSlots) {
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

    private static String formatGpuMillis(double millis) {
        return String.format(Locale.ROOT, "%.6f", millis);
    }
}

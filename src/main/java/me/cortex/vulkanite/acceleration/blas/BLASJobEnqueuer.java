package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.HybridAccelerationConfig;
import me.cortex.vulkanite.acceleration.JobPassThroughData;
import me.cortex.vulkanite.acceleration.voxel.AdaptiveBrickSizePolicy;
import me.cortex.vulkanite.acceleration.voxel.AdaptiveBrickTelemetry;
import me.cortex.vulkanite.acceleration.voxel.ProceduralMaterialExtractor;
import me.cortex.vulkanite.acceleration.voxel.ProceduralMaterialPayload;
import me.cortex.vulkanite.acceleration.voxel.VoxelBrickGeometry;
import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.compat.ISectionLightBuildResult;
import me.cortex.vulkanite.compat.SectionLightTable;
import me.cortex.vulkanite.compat.ShadowGeometryFilter;
import me.cortex.vulkanite.compat.SodiumGeometry;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Handles enqueueing of chunk build outputs for BLAS construction.
 * Processes incoming chunk data, uploads geometry, and queues build jobs.
 */
public class BLASJobEnqueuer {
    private static final Logger LOGGER = LoggerFactory.getLogger(BLASJobEnqueuer.class);
    private static final long INFO_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final long SLOW_ENQUEUE_LOG_NANOS =
            Long.getLong("vulkanite.blasSlowEnqueueLogMs", 8L) * 1_000_000L;

    private final VContext context;
    private final int queueId;
    private final Semaphore awaitingJobBatches;
    private final ConcurrentLinkedDeque<List<BLASBuildJob>> batchedJobs;
    private final Map<RenderSection, ProceduralOccupancyState> proceduralOccupancy = new IdentityHashMap<>();
    private final boolean proceduralBlasEnabled;
    private final boolean shadowGeometryFilteringEnabled;
    private final AdaptiveBrickSizePolicy adaptiveBrickPolicy =
            new AdaptiveBrickSizePolicy(AdaptiveBrickSizePolicy.Weights.fromSystemProperties());
    private final AdaptiveBrickTelemetry adaptiveBrickTelemetry = new AdaptiveBrickTelemetry();
    private long lastInfoLogNanos;

    public BLASJobEnqueuer(VContext context,
            int queueId,
            Semaphore awaitingJobBatches,
            ConcurrentLinkedDeque<List<BLASBuildJob>> batchedJobs) {
        this.context = context;
        this.queueId = queueId;
        this.awaitingJobBatches = awaitingJobBatches;
        this.batchedJobs = batchedJobs;
        var accelerationConfig = HybridAccelerationConfig.fromSystemProperties();
        this.proceduralBlasEnabled = accelerationConfig.proceduralBlas()
                && context.capabilities.proceduralAabbBlas();
        this.shadowGeometryFilteringEnabled = accelerationConfig.filterShadowGeometry();
        if (proceduralBlasEnabled) {
            LOGGER.info("[Procedural BLAS] Section builds enabled; shadowGeometryMode={}; full reflection triangles remain resident",
                    shadowGeometryFilteringEnabled ? "filtered" : "triangle-only");
        } else {
            LOGGER.info("[Procedural BLAS] Disabled (requested={}, accelerationStructure={}, rtPipeline={}, bufferDeviceAddress={})",
                    accelerationConfig.proceduralBlas(),
                    context.capabilities.accelerationStructure(),
                    context.capabilities.rayTracingPipeline(),
                    context.capabilities.bufferDeviceAddress());
        }
    }

    /**
     * Enqueues jobs of section blas builds.
     * NOTE: This is called from a different thread!
     */
    public void enqueue(List<ChunkBuildOutput> batch) {
        long enqueueStartNanos = System.nanoTime();
        var cmd = context.cmd.getSingleUsePool().createCommandBuffer();

        List<BLASBuildJob> jobs = new ArrayList<>(batch.size());
        boolean queued = false;
        long totalGeometryBytes = 0L;
        int geometryRanges = 0;
        int proceduralBuilds = 0;
        int proceduralAabbs = 0;
        long proceduralUploadBytes = 0L;
        long shadowSourceQuads = 0L;
        long shadowRetainedQuads = 0L;
        long shadowRemovedQuads = 0L;
        long shadowUnresolvedQuads = 0L;
        try {
            for (ChunkBuildOutput cbr : batch) {
                var sodiumGeometry = ((IAccelerationBuildResult) cbr).getAccelerationGeometry();
                if (sodiumGeometry == null || sodiumGeometry.isEmpty()) {
                    continue;
                }
                totalGeometryBytes += sodiumGeometry.totalSizeBytes();
                geometryRanges += sodiumGeometry.geometries().size();

                var geomBuffer = context.memory.createBuffer(sodiumGeometry.totalSizeBytes(),
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR
                                | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                geomBuffer.get().setDebugUtilsObjectName("Terrain geometry buffer");
                Optional<ShadowTriangleBLASInput> shadowTriangleInput = Optional.empty();
                Optional<ProceduralBLASInput> proceduralInput = Optional.empty();
                boolean transferred = false;
                try {
                    List<BLASTriangleData> buildData = new ArrayList<>();
                    List<Long> bufferOffsets = new ArrayList<>();
                    long destOffset = 0;
                    for (SodiumGeometry geometry : sodiumGeometry.geometries()) {
                        int flag = geometryFlags(geometry.pass());
                        buildData.add(new BLASTriangleData(
                                geometry.quadCount(), flag, geometryKind(geometry.pass())));
                        cmd.get().encodeDataUpload(context.memory,
                                MemoryUtil.memAddress(geometry.vertexData().getDirectBuffer()),
                                geomBuffer, destOffset, geometry.sizeBytes());
                        bufferOffsets.add(destOffset);
                        destOffset += geometry.sizeBytes();
                    }

                    SectionLightTable sectionData = cbr instanceof ISectionLightBuildResult lightResult
                            ? lightResult.getSectionLights()
                            : null;
                    long[] occupancy = sectionData == null
                            ? SectionLightTable.newOpacityMask()
                            : sectionData.proceduralBlocks();

                    boolean filteredShadowGeometry = false;
                    ProceduralMaterialPayload materialPayload = ProceduralMaterialPayload.empty();
                    if (proceduralBlasEnabled && shadowGeometryFilteringEnabled && !isEmpty(occupancy)) {
                        try (ShadowGeometryFilter.Result split =
                                ShadowGeometryFilter.filter(sodiumGeometry, occupancy)) {
                            if (split.removedShadowQuads() > 0L) {
                                filteredShadowGeometry = true;
                                shadowTriangleInput = createShadowTriangleInput(cbr, split, cmd.get());
                                materialPayload = ProceduralMaterialExtractor.extract(split, sectionData);
                                shadowSourceQuads += split.sourceQuads();
                                shadowRetainedQuads += split.shadowQuads();
                                shadowRemovedQuads += split.removedShadowQuads();
                                shadowUnresolvedQuads += split.unresolvedQuads();
                            }
                        } catch (RuntimeException filterFailure) {
                            // Filtering is an optimization. Keep the full material
                            // triangle mesh authoritative when raw vertex metadata is
                            // absent, malformed, or cannot be represented safely.
                            shadowTriangleInput.ifPresent(ShadowTriangleBLASInput::close);
                            shadowTriangleInput = Optional.empty();
                            filteredShadowGeometry = false;
                            materialPayload = ProceduralMaterialPayload.empty();
                            LOGGER.warn("[Vulkanite][Phase8] event=shadow_filter_fallback section={} reason={}",
                                    cbr.render.getPosition(), filterFailure.toString());
                        }
                    }

                    ProceduralBLASDisposition proceduralDisposition = ProceduralBLASDisposition.CLEAR;
                    if (proceduralBlasEnabled) {
                        ProceduralOccupancyState occupancyState = proceduralOccupancy.computeIfAbsent(
                                cbr.render, ignored -> new ProceduralOccupancyState());
                        int brickSize = selectedBrickSize(occupancy, occupancyState.brickSize());
                        proceduralDisposition = occupancyState.record(
                                occupancy, cbr.buildTime, brickSize, materialPayload);
                        if (proceduralDisposition == ProceduralBLASDisposition.REPLACE) {
                            VoxelBrickGeometry voxelGeometry = VoxelBrickGeometry.fromOpacityMask(
                                    occupancy, brickSize, materialPayload);
                            ProceduralBLASInput input = createProceduralInput(cbr, voxelGeometry, cmd.get());
                            proceduralInput = Optional.of(input);
                            proceduralBuilds++;
                            proceduralAabbs += voxelGeometry.brickCount();
                            proceduralUploadBytes += input.aabbBuffer().get().size()
                                    + input.payloadBuffer().get().size();
                        }
                    }

                    jobs.add(new BLASBuildJob(
                            buildData,
                            new JobPassThroughData(cbr.render, cbr.buildTime, geomBuffer,
                                    bufferOffsets, enqueueStartNanos),
                            shadowTriangleInput,
                            filteredShadowGeometry,
                            proceduralInput,
                            proceduralDisposition));
                    transferred = true;
                    ((IAccelerationBuildResult) cbr).setAccelerationGeometry(null);
                } finally {
                    if (!transferred) {
                        geomBuffer.close();
                        shadowTriangleInput.ifPresent(ShadowTriangleBLASInput::close);
                        proceduralInput.ifPresent(ProceduralBLASInput::close);
                    }
                }
            }

            long uploadSubmitStartNanos = System.nanoTime();
            long uploadExecution = 0L;
            if (!jobs.isEmpty()) {
                uploadExecution = context.cmd.submit(queueId, cmd);
            }
            long uploadSubmitNanos = System.nanoTime() - uploadSubmitStartNanos;
            cmd.close();

            if (jobs.isEmpty()) {
                return;
            }

            for (int start = 0; start < jobs.size(); start += BLASBatchProcessor.MAX_BATCH_SIZE) {
                int end = Math.min(start + BLASBatchProcessor.MAX_BATCH_SIZE, jobs.size());
                batchedJobs.add(new ArrayList<>(jobs.subList(start, end)));
                awaitingJobBatches.release();
            }
            queued = true;
            long totalNanos = System.nanoTime() - enqueueStartNanos;
            logEnqueue(batch.size(), jobs.size(), geometryRanges, totalGeometryBytes,
                    proceduralBuilds, proceduralAabbs, proceduralUploadBytes,
                    shadowSourceQuads, shadowRetainedQuads, shadowRemovedQuads,
                    shadowUnresolvedQuads,
                    uploadExecution, uploadSubmitNanos, totalNanos);
        } catch (Throwable t) {
            LOGGER.error("[BLAS Enqueue] Failed to enqueue chunk BLAS jobs; skipping acceleration for this batch", t);
            cmd.close();
            if (!queued) {
                for (BLASBuildJob job : jobs) {
                    closeJob(job);
                }
            }
            for (ChunkBuildOutput cbr : batch) {
                ((IAccelerationBuildResult) cbr).setAccelerationGeometry(null);
            }
        }
    }

    private Optional<ShadowTriangleBLASInput> createShadowTriangleInput(
            ChunkBuildOutput output,
            ShadowGeometryFilter.Result split,
            me.cortex.vulkanite.lib.cmd.VCmdBuff cmd) {
        if (split.shadowGeometry().isEmpty()) {
            // A filtered section with no retained quads is meaningful: the
            // TLAS holder records filtered ownership while omitting a zero-size
            // triangle BLAS and lets the procedural instance own all shadows.
            return Optional.empty();
        }

        var shadowBuffer = context.memory.createBuffer(
                split.shadowGeometry().totalSizeBytes(),
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR
                        | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        try {
            String debugName = "Section " + output.render.getPosition() + " Shadow Triangles";
            shadowBuffer.get().setDebugUtilsObjectName(debugName + " Geometry");
            List<BLASTriangleData> geometries = new ArrayList<>();
            List<Long> offsets = new ArrayList<>();
            long destinationOffset = 0L;
            for (SodiumGeometry geometry : split.shadowGeometry().geometries()) {
                geometries.add(new BLASTriangleData(
                        geometry.quadCount(),
                        geometryFlags(geometry.pass()),
                        geometryKind(geometry.pass())));
                offsets.add(destinationOffset);
                cmd.encodeDataUpload(
                        context.memory,
                        MemoryUtil.memAddress(geometry.vertexData().getDirectBuffer()),
                        shadowBuffer,
                        destinationOffset,
                        geometry.sizeBytes());
                destinationOffset += geometry.sizeBytes();
            }
            return Optional.of(new ShadowTriangleBLASInput(
                    geometries,
                    shadowBuffer,
                    offsets,
                    split.sourceQuads(),
                    split.shadowQuads(),
                    debugName));
        } catch (Throwable throwable) {
            shadowBuffer.close();
            throw throwable;
        }
    }

    private ProceduralBLASInput createProceduralInput(
            ChunkBuildOutput output,
            VoxelBrickGeometry geometry,
            me.cortex.vulkanite.lib.cmd.VCmdBuff cmd) {
        if (geometry.brickCount() <= 0) {
            throw new IllegalArgumentException("Cannot upload an empty procedural section");
        }

        VRef<VBuffer> aabbBuffer = null;
        VRef<VBuffer> payloadBuffer = null;
        try {
            aabbBuffer = context.memory.createBuffer(
                    geometry.aabbBytes(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR
                            | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            payloadBuffer = context.memory.createBuffer(
                    geometry.packedBytes(),
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR
                            | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            String sectionName = "Section " + output.render.getPosition();
            aabbBuffer.get().setDebugUtilsObjectName(sectionName + " Procedural AABB Input");
            payloadBuffer.get().setDebugUtilsObjectName(sectionName + " Voxel Brick Payload");

            var aabbs = geometry.packAabbs();
            var payload = geometry.pack();
            cmd.encodeDataUpload(context.memory, MemoryUtil.memAddress(aabbs), aabbBuffer, 0, aabbs.remaining());
            cmd.encodeDataUpload(context.memory, MemoryUtil.memAddress(payload), payloadBuffer, 0, payload.remaining());
            return new ProceduralBLASInput(geometry, aabbBuffer, payloadBuffer, sectionName);
        } catch (Throwable t) {
            if (aabbBuffer != null) {
                aabbBuffer.close();
            }
            if (payloadBuffer != null) {
                payloadBuffer.close();
            }
            throw t;
        }
    }

    public synchronized void markInstalled(List<BLASBuildResult> results) {
        for (BLASBuildResult result : results) {
            ProceduralOccupancyState state = proceduralOccupancy.get(result.data().section());
            if (state != null) {
                state.markInstalled(result.data().time(), result.proceduralDisposition());
            }
        }
    }

    public synchronized void removeSection(RenderSection section) {
        proceduralOccupancy.remove(section);
    }

    public synchronized void clear() {
        proceduralOccupancy.clear();
    }

    private static int configuredBrickSize() {
        int configured = Integer.getInteger("vulkanite.voxelBrickSize", VoxelBrickGeometry.DEFAULT_BRICK_SIZE);
        return configured == 4 || configured == 8 || configured == 16
                ? configured
                : VoxelBrickGeometry.DEFAULT_BRICK_SIZE;
    }

    private int selectedBrickSize(long[] occupancy, int previousSize) {
        if (!"adaptive".equalsIgnoreCase(System.getProperty("vulkanite.voxelBrickMode", "fixed"))) {
            return configuredBrickSize();
        }
        AdaptiveBrickSizePolicy.Selection selection = adaptiveBrickPolicy.select(occupancy, previousSize);
        adaptiveBrickTelemetry.record(selection, previousSize);
        return selection.brickSize();
    }

    private static void closeJob(BLASBuildJob job) {
        job.data().geometryBuffer().close();
        job.shadowTriangleInput().ifPresent(ShadowTriangleBLASInput::close);
        job.proceduralInput().ifPresent(ProceduralBLASInput::close);
    }

    private static boolean isEmpty(long[] occupancy) {
        for (long word : occupancy) {
            if (word != 0L) {
                return false;
            }
        }
        return true;
    }

    private void logEnqueue(int buildOutputs, int jobs, int geometryRanges, long geometryBytes,
            int proceduralBuilds, int proceduralAabbs, long proceduralUploadBytes,
            long shadowSourceQuads, long shadowRetainedQuads, long shadowRemovedQuads,
            long shadowUnresolvedQuads, long uploadExecution,
            long uploadSubmitNanos, long totalNanos) {
        long now = System.nanoTime();
        boolean info = (totalNanos >= SLOW_ENQUEUE_LOG_NANOS
                || now - lastInfoLogNanos >= INFO_LOG_INTERVAL_NANOS)
                && now - lastInfoLogNanos >= INFO_LOG_INTERVAL_NANOS;
        if (info) {
            lastInfoLogNanos = now;
            LOGGER.info("[Vulkanite] BLAS enqueue: buildOutputs={}, jobs={}, geometryRanges={}, geometryBytes={}, proceduralBuilds={}, proceduralAABBs={}, proceduralUploadBytes={}, shadowSourceQuads={}, shadowRetainedQuads={}, shadowRemovedQuads={}, shadowUnresolvedQuads={}, uploadExecution={}, uploadSubmit={} ms, total={} ms",
                    buildOutputs, jobs, geometryRanges, geometryBytes,
                    proceduralBuilds, proceduralAabbs, proceduralUploadBytes,
                    shadowSourceQuads, shadowRetainedQuads, shadowRemovedQuads,
                    shadowUnresolvedQuads, uploadExecution,
                    formatMillis(uploadSubmitNanos), formatMillis(totalNanos));
            ShadowTriangleBLAS.Statistics shadowStats = ShadowTriangleBLAS.statistics();
            ProceduralBLAS.Statistics proceduralStats = ProceduralBLAS.statistics();
            LOGGER.info("[Vulkanite][Phase8] liveShadowTriangleBlas={} shadowPersistentBytes={} retainedQuads={} removedQuads={} proceduralBlas={} proceduralAabbs={} proceduralPersistentBytes={}",
                    shadowStats.live(), shadowStats.liveBytes(), shadowStats.liveQuads(),
                    shadowStats.removedQuads(), proceduralStats.live(), proceduralStats.liveAabbs(),
                    proceduralStats.liveBytes());
            if ("adaptive".equalsIgnoreCase(System.getProperty("vulkanite.voxelBrickMode", "fixed"))) {
                LOGGER.info("[Vulkanite][Phase9] adaptiveBrickTelemetry {}",
                        adaptiveBrickTelemetry.snapshot().structuredSummary());
                for (int brickSize : AdaptiveBrickSizePolicy.SUPPORTED_SIZES) {
                    ProceduralBLAS.SizeStatistics sizes = ProceduralBLAS.sizeStatistics(brickSize);
                    LOGGER.info("[Vulkanite][Phase9] liveBrickSize={} sections={} aabbs={} asBytes={} payloadBytes={}",
                            brickSize, sizes.live(), sizes.liveAabbs(),
                            sizes.liveAccelerationStructureBytes(), sizes.livePayloadBytes());
                }
            }
        } else {
            LOGGER.debug("[Vulkanite] BLAS enqueue: buildOutputs={}, jobs={}, geometryRanges={}, geometryBytes={}, proceduralBuilds={}, proceduralAABBs={}, proceduralUploadBytes={}, shadowSourceQuads={}, shadowRetainedQuads={}, shadowRemovedQuads={}, shadowUnresolvedQuads={}, uploadExecution={}, uploadSubmit={} ms, total={} ms",
                    buildOutputs, jobs, geometryRanges, geometryBytes,
                    proceduralBuilds, proceduralAabbs, proceduralUploadBytes,
                    shadowSourceQuads, shadowRetainedQuads, shadowRemovedQuads,
                    shadowUnresolvedQuads, uploadExecution,
                    formatMillis(uploadSubmitNanos), formatMillis(totalNanos));
        }
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static int geometryFlags(TerrainRenderPass pass) {
        return pass == DefaultTerrainRenderPasses.SOLID ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0;
    }

    private static BLASTriangleData.GeometryKind geometryKind(TerrainRenderPass pass) {
        if (pass == DefaultTerrainRenderPasses.SOLID) {
            return BLASTriangleData.GeometryKind.TERRAIN_SOLID;
        }

        String passName = String.valueOf(pass).toLowerCase(Locale.ROOT);
        if (passName.contains("water")) {
            return BLASTriangleData.GeometryKind.TERRAIN_WATER;
        }

        return BLASTriangleData.GeometryKind.TERRAIN_TRANSLUCENT;
    }
}

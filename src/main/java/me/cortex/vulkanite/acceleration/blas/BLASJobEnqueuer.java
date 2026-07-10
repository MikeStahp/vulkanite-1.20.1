package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.JobPassThroughData;
import me.cortex.vulkanite.acceleration.voxel.VoxelBrickGeometry;
import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.compat.ISectionLightBuildResult;
import me.cortex.vulkanite.compat.SectionLightTable;
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
    private long lastInfoLogNanos;

    public BLASJobEnqueuer(VContext context,
            int queueId,
            Semaphore awaitingJobBatches,
            ConcurrentLinkedDeque<List<BLASBuildJob>> batchedJobs) {
        this.context = context;
        this.queueId = queueId;
        this.awaitingJobBatches = awaitingJobBatches;
        this.batchedJobs = batchedJobs;
        this.proceduralBlasEnabled = Boolean.parseBoolean(
                System.getProperty("vulkanite.proceduralBlas", "true"))
                && context.capabilities.proceduralAabbBlas();
        if (proceduralBlasEnabled) {
            LOGGER.info("[Procedural BLAS] Debug-only section builds enabled; triangle TLAS remains authoritative");
        } else {
            LOGGER.info("[Procedural BLAS] Disabled (requested={}, accelerationStructure={}, rtPipeline={}, bufferDeviceAddress={})",
                    System.getProperty("vulkanite.proceduralBlas", "true"),
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
        try {
            for (ChunkBuildOutput cbr : batch) {
                var sodiumGeometry = ((IAccelerationBuildResult) cbr).getAccelerationGeometry();
                if (sodiumGeometry == null || sodiumGeometry.isEmpty()) {
                    continue;
                }
                totalGeometryBytes += sodiumGeometry.totalSizeBytes();
                geometryRanges += sodiumGeometry.geometries().size();

                // VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR is
                // REQUIRED for BLAS input buffers
                // NOTE: Do NOT add HOST_VISIBLE to DEVICE_LOCAL - this combination is not
                // guaranteed on discrete GPUs and can cause VK_ERROR_DEVICE_LOST or access violations.
                var geomBuffer = context.memory.createBuffer(sodiumGeometry.totalSizeBytes(),
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR
                                | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                geomBuffer.get().setDebugUtilsObjectName("Terrain geometry buffer");

                List<BLASTriangleData> buildData = new ArrayList<>();
                List<Long> bufferOffsets = new ArrayList<>();
                long destOffset = 0;
                for (SodiumGeometry geometry : sodiumGeometry.geometries()) {
                    int flag = geometryFlags(geometry.pass());
                    buildData.add(new BLASTriangleData(geometry.quadCount(), flag, geometryKind(geometry.pass())));

                    cmd.get().encodeDataUpload(context.memory,
                            MemoryUtil.memAddress(geometry.vertexData().getDirectBuffer()), geomBuffer, destOffset,
                            geometry.sizeBytes());
                    bufferOffsets.add(destOffset);

                    destOffset += geometry.sizeBytes();
                }

                ProceduralBLASDisposition proceduralDisposition = ProceduralBLASDisposition.CLEAR;
                Optional<ProceduralBLASInput> proceduralInput = Optional.empty();
                if (proceduralBlasEnabled) {
                    SectionLightTable sectionData = cbr instanceof ISectionLightBuildResult lightResult
                            ? lightResult.getSectionLights()
                            : null;
                    long[] occupancy = sectionData == null
                            ? SectionLightTable.newOpacityMask()
                            : sectionData.opaqueBlocks();
                    ProceduralOccupancyState occupancyState = proceduralOccupancy.computeIfAbsent(
                            cbr.render, ignored -> new ProceduralOccupancyState());
                    proceduralDisposition = occupancyState.record(occupancy, cbr.buildTime);
                    if (proceduralDisposition == ProceduralBLASDisposition.REPLACE) {
                        VoxelBrickGeometry voxelGeometry = VoxelBrickGeometry.fromOpacityMask(
                                occupancy, configuredBrickSize());
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
                        new JobPassThroughData(cbr.render, cbr.buildTime, geomBuffer, bufferOffsets,
                                enqueueStartNanos),
                        proceduralInput,
                        proceduralDisposition));
                ((IAccelerationBuildResult) cbr).setAccelerationGeometry(null);
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

    private static void closeJob(BLASBuildJob job) {
        job.data().geometryBuffer().close();
        job.proceduralInput().ifPresent(ProceduralBLASInput::close);
    }

    private void logEnqueue(int buildOutputs, int jobs, int geometryRanges, long geometryBytes,
            int proceduralBuilds, int proceduralAabbs, long proceduralUploadBytes, long uploadExecution,
            long uploadSubmitNanos, long totalNanos) {
        long now = System.nanoTime();
        boolean info = (totalNanos >= SLOW_ENQUEUE_LOG_NANOS
                || now - lastInfoLogNanos >= INFO_LOG_INTERVAL_NANOS)
                && now - lastInfoLogNanos >= INFO_LOG_INTERVAL_NANOS;
        if (info) {
            lastInfoLogNanos = now;
            LOGGER.info("[Vulkanite] BLAS enqueue: buildOutputs={}, jobs={}, geometryRanges={}, geometryBytes={}, proceduralBuilds={}, proceduralAABBs={}, proceduralUploadBytes={}, uploadExecution={}, uploadSubmit={} ms, total={} ms",
                    buildOutputs, jobs, geometryRanges, geometryBytes,
                    proceduralBuilds, proceduralAabbs, proceduralUploadBytes, uploadExecution,
                    formatMillis(uploadSubmitNanos), formatMillis(totalNanos));
        } else {
            LOGGER.debug("[Vulkanite] BLAS enqueue: buildOutputs={}, jobs={}, geometryRanges={}, geometryBytes={}, proceduralBuilds={}, proceduralAABBs={}, proceduralUploadBytes={}, uploadExecution={}, uploadSubmit={} ms, total={} ms",
                    buildOutputs, jobs, geometryRanges, geometryBytes,
                    proceduralBuilds, proceduralAabbs, proceduralUploadBytes, uploadExecution,
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

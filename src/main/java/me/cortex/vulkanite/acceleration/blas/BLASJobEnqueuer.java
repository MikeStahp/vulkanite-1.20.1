package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.JobPassThroughData;
import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.compat.SodiumGeometry;
import me.cortex.vulkanite.lib.base.VContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final long SLOW_ENQUEUE_LOG_NANOS = 2_000_000L;

    private final VContext context;
    private final int queueId;
    private final Semaphore awaitingJobBatches;
    private final ConcurrentLinkedDeque<List<BLASBuildJob>> batchedJobs;
    private long lastInfoLogNanos;

    public BLASJobEnqueuer(VContext context,
            int queueId,
            Semaphore awaitingJobBatches,
            ConcurrentLinkedDeque<List<BLASBuildJob>> batchedJobs) {
        this.context = context;
        this.queueId = queueId;
        this.awaitingJobBatches = awaitingJobBatches;
        this.batchedJobs = batchedJobs;
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

                jobs.add(new BLASBuildJob(buildData,
                        new JobPassThroughData(cbr.render, cbr.buildTime, geomBuffer, bufferOffsets,
                                enqueueStartNanos)));
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
            logEnqueue(batch.size(), jobs.size(), geometryRanges, totalGeometryBytes, uploadExecution,
                    uploadSubmitNanos, totalNanos);
        } catch (Throwable t) {
            LOGGER.error("[BLAS Enqueue] Failed to enqueue chunk BLAS jobs; skipping acceleration for this batch", t);
            cmd.close();
            if (!queued) {
                for (BLASBuildJob job : jobs) {
                    job.data().geometryBuffer().close();
                }
            }
            for (ChunkBuildOutput cbr : batch) {
                ((IAccelerationBuildResult) cbr).setAccelerationGeometry(null);
            }
        }
    }

    private void logEnqueue(int buildOutputs, int jobs, int geometryRanges, long geometryBytes, long uploadExecution,
            long uploadSubmitNanos, long totalNanos) {
        long now = System.nanoTime();
        boolean info = jobs <= 4
                || totalNanos >= SLOW_ENQUEUE_LOG_NANOS
                || now - lastInfoLogNanos >= INFO_LOG_INTERVAL_NANOS;
        if (info) {
            lastInfoLogNanos = now;
            LOGGER.info("[Vulkanite] BLAS enqueue: buildOutputs={}, jobs={}, geometryRanges={}, geometryBytes={}, uploadExecution={}, uploadSubmit={} ms, total={} ms",
                    buildOutputs, jobs, geometryRanges, geometryBytes, uploadExecution,
                    formatMillis(uploadSubmitNanos), formatMillis(totalNanos));
        } else {
            LOGGER.debug("[Vulkanite] BLAS enqueue: buildOutputs={}, jobs={}, geometryRanges={}, geometryBytes={}, uploadExecution={}, uploadSubmit={} ms, total={} ms",
                    buildOutputs, jobs, geometryRanges, geometryBytes, uploadExecution,
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

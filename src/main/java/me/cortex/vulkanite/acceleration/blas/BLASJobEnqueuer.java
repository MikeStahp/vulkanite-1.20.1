package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.JobPassThroughData;
import me.cortex.vulkanite.compat.GeometryData;
import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.compat.NativeBufferTracker;
import me.cortex.vulkanite.lib.base.VContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
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

    private final VContext context;
    private final int queueId;
    private final Semaphore awaitingJobBatches;
    private final ConcurrentLinkedDeque<List<BLASBuildJob>> batchedJobs;

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
        var cmd = context.cmd.getSingleUsePool().createCommandBuffer();
        boolean hasJobs = false;

        List<BLASBuildJob> jobs = new ArrayList<>(batch.size());
        boolean queued = false;
        try {
            for (ChunkBuildOutput cbr : batch) {
                var acbr = ((IAccelerationBuildResult) cbr).getAccelerationGeometryData();
                if (acbr == null) {
                    continue;
                }

                List<TerrainGeometry> geometries = new ArrayList<>();

                long totalSize = 0;
                for (var entry : acbr.entrySet()) {
                    var geometry = cbr.getMesh(entry.getKey());
                    if (geometry == null || entry.getValue().quadCount() <= 0) {
                        continue;
                    }
                    var dataSize = geometry.getVertexData().getLength();
                    if (dataSize <= 0) {
                        continue;
                    }
                    totalSize += dataSize;
                    geometries.add(new TerrainGeometry(entry.getKey(), geometry, entry.getValue()));
                }

                if (geometries.isEmpty()) {
                    NativeBufferTracker.getInstance().untrackBuffers(cbr);
                    continue;
                }

                hasJobs = true;

                // VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR is
                // REQUIRED for BLAS input buffers
                // NOTE: Do NOT add HOST_VISIBLE to DEVICE_LOCAL - this combination is not
                // guaranteed on discrete GPUs and can cause VK_ERROR_DEVICE_LOST or access violations.
                var geomBuffer = context.memory.createBuffer(totalSize,
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR
                                | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                geomBuffer.get().setDebugUtilsObjectName("Terrain geometry buffer");

                List<BLASTriangleData> buildData = new ArrayList<>();
                List<Long> bufferOffsets = new ArrayList<>();
                long destOffset = 0;
                for (var terrainGeometry : geometries) {
                    int flag = geometryFlags(terrainGeometry.pass());
                    buildData.add(new BLASTriangleData(terrainGeometry.geometryData().quadCount(), flag,
                            geometryKind(terrainGeometry.pass())));

                    var meshParts = terrainGeometry.mesh();
                    long dataSize = meshParts.getVertexData().getLength();

                    cmd.get().encodeDataUpload(context.memory,
                            MemoryUtil.memAddress(meshParts.getVertexData().getDirectBuffer()), geomBuffer, destOffset,
                            dataSize);
                    bufferOffsets.add(destOffset);

                    destOffset += dataSize;
                }

                if (!buildData.isEmpty()) {
                    jobs.add(new BLASBuildJob(buildData,
                            new JobPassThroughData(cbr.render, cbr.buildTime, geomBuffer, bufferOffsets)));
                }

                // Notify tracker that buffers are being processed
                NativeBufferTracker.getInstance().untrackBuffers(cbr);
            }

            if (hasJobs) {
                context.cmd.submit(queueId, cmd);
            }
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
            LOGGER.debug("[BLAS Enqueue] Queued {} chunk BLAS jobs from {} chunk build outputs", jobs.size(),
                    batch.size());
        } catch (Throwable t) {
            LOGGER.error("[BLAS Enqueue] Failed to enqueue chunk BLAS jobs; skipping acceleration for this batch", t);
            cmd.close();
            if (!queued) {
                for (BLASBuildJob job : jobs) {
                    job.data().geometryBuffer().close();
                }
            }
            for (ChunkBuildOutput cbr : batch) {
                NativeBufferTracker.getInstance().untrackBuffers(cbr);
            }
        }
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

    private record TerrainGeometry(TerrainRenderPass pass, BuiltSectionMeshParts mesh, GeometryData geometryData) {
    }
}

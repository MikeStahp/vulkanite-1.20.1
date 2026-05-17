package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.JobPassThroughData;
import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.lib.base.VContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Handles enqueueing of chunk build outputs for BLAS construction.
 * Processes incoming chunk data, uploads geometry, and queues build jobs.
 */
public class BLASJobEnqueuer {
    private final VContext context;
    private final Semaphore awaitingJobBatches;
    private final ConcurrentLinkedDeque<List<BLASBuildJob>> batchedJobs;

    public BLASJobEnqueuer(VContext context,
            Semaphore awaitingJobBatches,
            ConcurrentLinkedDeque<List<BLASBuildJob>> batchedJobs) {
        this.context = context;
        this.awaitingJobBatches = awaitingJobBatches;
        this.batchedJobs = batchedJobs;
    }

    /**
     * Enqueues jobs of section blas builds.
     * NOTE: This is called from a different thread!
     */
    public void enqueue(Collection<ChunkBuildOutput> batch) {
        var cmd = context.cmd.getSingleUsePool().createCommandBuffer();
        boolean hasJobs = false;

        List<BLASBuildJob> jobs = new ArrayList<>(batch.size());
        for (ChunkBuildOutput cbr : batch) {
            var acbr = ((IAccelerationBuildResult) cbr).getAccelerationGeometryData();
            if (acbr == null)
                continue;

            List<BuiltSectionMeshParts> geometries = new ArrayList<>();

            long totalSize = 0;
            for (var entry : acbr.entrySet()) {
                var geometry = cbr.getMesh(entry.getKey());
                var dataSize = geometry.getVertexData().getLength();
                if (dataSize == 0) {
                    throw new IllegalStateException();
                }
                if (!hasJobs) {
                    hasJobs = true;
                }
                totalSize += dataSize;
                geometries.add(geometry);
            }

            var geomBuffer = context.memory.createBuffer(totalSize,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            geomBuffer.get().setDebugUtilsObjectName("Terrain geometry buffer");

            List<BLASTriangleData> buildData = new ArrayList<>();
            List<Long> bufferOffsets = new ArrayList<>();
            int i = 0;
            long destOffset = 0;
            for (var entry : acbr.entrySet()) {
                int flag = entry.getKey() == DefaultTerrainRenderPasses.SOLID ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0;
                buildData.add(new BLASTriangleData(entry.getValue().quadCount(), flag));

                var meshParts = geometries.get(i);
                long dataSize = meshParts.getVertexData().getLength();

                cmd.get().encodeDataUpload(context.memory,
                        MemoryUtil.memAddress(meshParts.getVertexData().getDirectBuffer()), geomBuffer, destOffset,
                        dataSize);
                bufferOffsets.add(destOffset);

                destOffset += dataSize;
                i++;
            }

            if (!buildData.isEmpty()) {
                jobs.add(new BLASBuildJob(buildData,
                        new JobPassThroughData(cbr.render, cbr.buildTime, geomBuffer, bufferOffsets)));
            }
        }

        if (hasJobs) {
            context.cmd.submitOnceAndWait(1, cmd);
        }
        cmd.close();

        if (jobs.isEmpty()) {
            return;
        }
        batchedJobs.add(jobs);
        awaitingJobBatches.release();
    }
}

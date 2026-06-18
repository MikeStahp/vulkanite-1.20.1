package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.acceleration.blas.BLASBatchResult;
import me.cortex.vulkanite.acceleration.blas.BLASBuildResult;
import me.cortex.vulkanite.client.rendering.EntityCapture;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSet;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

public class AccelerationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccelerationManager.class);

    private final VContext ctx;

    private final AccelerationBlasBuilder blasBuilder;
    private final ConcurrentLinkedDeque<BLASBatchResult> blasResults = new ConcurrentLinkedDeque<>();

    private final AccelerationTLASManager tlasManager;

    public AccelerationManager(VContext context, int blasBuildQueue) {
        this.ctx = context;
        this.blasBuilder = new AccelerationBlasBuilder(context, blasBuildQueue, blasResults::add);
        this.tlasManager = new AccelerationTLASManager(context, blasBuildQueue);// Use the same queue as BLAS building
    }

    public void chunkBuilds(List<ChunkBuildOutput> results) {
        blasBuilder.enqueue(results);
    }

    public void setEntityData(EntityCapture.Frame data) {
        tlasManager.setEntityData(data);
    }

    public List<VRef<VGImage>> getEntityTextureImages() {
        return tlasManager.getEntityTextureImages();
    }

    private final List<Long> blasExecutions = new LinkedList<>();

    // This updates the tlas internal structure, DOES NOT INCLUDING BUILDING THE
    // TLAS
    public void updateTick() {
        long startNanos = System.nanoTime();
        BLASBatchResult batch;
        List<BLASBuildResult> results = new ArrayList<>();
        while ((batch = blasResults.poll()) != null) {
            results.addAll(batch.results());
            if (batch.execution() != 0L) {
                blasExecutions.add(batch.execution());
            }
        }

        if (!results.isEmpty()) {
            tlasManager.updateSections(results);
            LOGGER.info("[Vulkanite] BLAS results consumed: sections={}, maxEnqueueToTlas={} ms, updateTick={} ms",
                    results.size(), formatMillis(maxEnqueueAgeNanos(results)),
                    formatMillis(System.nanoTime() - startNanos));
        }
    }

    public VRef<VAccelerationStructure> buildTLAS(int queueId, VCmdBuff cmd) {
        long waitStartNanos = System.nanoTime();
        int waitCount = blasExecutions.size();
        ctx.cmd.queueWaitForExecutions(queueId, blasBuilder.getAsyncQueue(), blasExecutions);
        blasExecutions.clear();
        if (waitCount > 0) {
            LOGGER.info("[Vulkanite] TLAS queued wait for {} BLAS executions in {} ms",
                    waitCount, formatMillis(System.nanoTime() - waitStartNanos));
        }
        return tlasManager.buildTLAS(cmd);
    }

    public void sectionRemove(RenderSection section) {
        tlasManager.removeSection(section);
    }

    public VRef<VDescriptorSet> getGeometrySet() {
        return tlasManager.getGeometrySet();
    }

    public VRef<VDescriptorSetLayout> getGeometryLayout() {
        return tlasManager.getGeometryLayout();
    }

    public void destroy() {
        tlasManager.destroy();
    }

    private static long maxEnqueueAgeNanos(List<BLASBuildResult> results) {
        long now = System.nanoTime();
        long max = 0L;
        for (BLASBuildResult result : results) {
            max = Math.max(max, Math.max(0L, now - result.data().enqueuedNanos()));
        }
        return max;
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }
}

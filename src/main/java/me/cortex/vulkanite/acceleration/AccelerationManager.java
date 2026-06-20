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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class AccelerationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccelerationManager.class);
    private static final int MAX_BLAS_SECTIONS_TO_INSTALL_PER_TICK = 32;
    private static final long INFO_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final long SLOW_UPDATE_LOG_NANOS =
            Long.getLong("vulkanite.blasSlowInstallLogMs", 8L) * 1_000_000L;

    private final VContext ctx;

    private final AccelerationBlasBuilder blasBuilder;
    private final ConcurrentLinkedDeque<BLASBatchResult> blasResults = new ConcurrentLinkedDeque<>();
    private final ArrayDeque<BLASBatchResult> pendingBlasResults = new ArrayDeque<>();
    private final Set<RenderSection> retiredSections = ConcurrentHashMap.newKeySet();
    private final Map<RenderSection, Long> latestBuildTimes = new ConcurrentHashMap<>();

    private final AccelerationTLASManager tlasManager;
    private long lastBlasInstallLogNanos;

    public AccelerationManager(VContext context, int blasBuildQueue) {
        this.ctx = context;
        this.blasBuilder = new AccelerationBlasBuilder(context, blasBuildQueue, blasResults::add);
        this.tlasManager = new AccelerationTLASManager(context, blasBuildQueue);// Use the same queue as BLAS building
    }

    public void chunkBuilds(List<ChunkBuildOutput> results) {
        for (ChunkBuildOutput result : results) {
            retiredSections.remove(result.render);
            latestBuildTimes.put(result.render, (long) result.buildTime);
        }
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
        int receivedBatches = 0;
        int receivedSections = 0;
        while ((batch = blasResults.poll()) != null) {
            pendingBlasResults.addLast(batch);
            receivedBatches++;
            receivedSections += batch.results().size();
        }

        List<BLASBuildResult> results = new ArrayList<>();
        long completedBlasExecution = ctx.cmd.getQueueCurrentExecution(blasBuilder.getAsyncQueue());
        int installedBatches = 0;
        while ((batch = pendingBlasResults.peekFirst()) != null
                && batchReady(batch, completedBlasExecution)
                && results.size() < MAX_BLAS_SECTIONS_TO_INSTALL_PER_TICK) {
            if (!results.isEmpty()
                    && results.size() + batch.results().size() > MAX_BLAS_SECTIONS_TO_INSTALL_PER_TICK) {
                break;
            }
            pendingBlasResults.removeFirst();
            int accepted = appendCurrentResults(batch.results(), results);
            if (accepted > 0 && batch.execution() != 0L) {
                blasExecutions.add(batch.execution());
            }
            installedBatches++;
        }

        if (!results.isEmpty()) {
            tlasManager.updateSections(results);
        }

        logBlasInstall(receivedBatches, receivedSections, installedBatches, results,
                completedBlasExecution, System.nanoTime() - startNanos);
    }

    public VRef<VAccelerationStructure> buildTLAS(int queueId, VCmdBuff cmd) {
        long waitStartNanos = System.nanoTime();
        int waitCount = blasExecutions.size();
        ctx.cmd.queueWaitForExecutions(queueId, blasBuilder.getAsyncQueue(), blasExecutions);
        blasExecutions.clear();
        if (waitCount > 0) {
            LOGGER.debug("[Vulkanite] TLAS queued wait for {} BLAS executions in {} ms",
                    waitCount, formatMillis(System.nanoTime() - waitStartNanos));
        }
        return tlasManager.buildTLAS(cmd);
    }

    public void sectionRemove(RenderSection section) {
        retiredSections.add(section);
        latestBuildTimes.remove(section);
        tlasManager.removeSection(section);
    }

    public VRef<VDescriptorSet> getGeometrySet() {
        return tlasManager.getGeometrySet();
    }

    public VRef<VDescriptorSetLayout> getGeometryLayout() {
        return tlasManager.getGeometryLayout();
    }

    public void destroy() {
        blasBuilder.destroy();
        closePendingResults();
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

    private static boolean batchReady(BLASBatchResult batch, long completedBlasExecution) {
        return batch.execution() == 0L || batch.execution() <= completedBlasExecution;
    }

    private int appendCurrentResults(List<BLASBuildResult> source, List<BLASBuildResult> destination) {
        int accepted = 0;
        for (BLASBuildResult result : source) {
            if (isCurrentResult(result)) {
                destination.add(result);
                accepted++;
            } else {
                closeResult(result);
            }
        }
        return accepted;
    }

    private boolean isCurrentResult(BLASBuildResult result) {
        RenderSection section = result.data().section();
        if (retiredSections.contains(section)) {
            return false;
        }
        Long latestBuildTime = latestBuildTimes.get(section);
        return latestBuildTime == null || latestBuildTime == result.data().time();
    }

    private void closePendingResults() {
        ctx.cmd.waitQueueIdle(blasBuilder.getAsyncQueue());

        BLASBatchResult batch;
        while ((batch = blasResults.poll()) != null) {
            closeResults(batch.results());
        }
        while ((batch = pendingBlasResults.pollFirst()) != null) {
            closeResults(batch.results());
        }
        blasExecutions.clear();
        retiredSections.clear();
        latestBuildTimes.clear();
    }

    private static void closeResults(List<BLASBuildResult> results) {
        for (BLASBuildResult result : results) {
            closeResult(result);
        }
    }

    private static void closeResult(BLASBuildResult result) {
        result.structure().close();
        result.data().geometryBuffer().close();
    }

    private void logBlasInstall(
            int receivedBatches,
            int receivedSections,
            int installedBatches,
            List<BLASBuildResult> installedResults,
            long completedBlasExecution,
            long updateNanos) {
        if (receivedBatches == 0 && installedResults.isEmpty() && updateNanos < SLOW_UPDATE_LOG_NANOS) {
            return;
        }

        long now = System.nanoTime();
        boolean info = (updateNanos >= SLOW_UPDATE_LOG_NANOS
                || now - lastBlasInstallLogNanos >= INFO_LOG_INTERVAL_NANOS)
                && now - lastBlasInstallLogNanos >= INFO_LOG_INTERVAL_NANOS;
        if (info) {
            lastBlasInstallLogNanos = now;
            LOGGER.info("[Vulkanite] BLAS results: received batches={}, received sections={}, installed batches={}, installed sections={}, pending batches={}, completedAsyncExecution={}, maxEnqueueToTlas={} ms, updateTick={} ms",
                    receivedBatches, receivedSections, installedBatches, installedResults.size(),
                    pendingBlasResults.size(), completedBlasExecution,
                    formatMillis(maxEnqueueAgeNanos(installedResults)), formatMillis(updateNanos));
        } else {
            LOGGER.debug("[Vulkanite] BLAS results: received batches={}, received sections={}, installed batches={}, installed sections={}, pending batches={}, completedAsyncExecution={}, maxEnqueueToTlas={} ms, updateTick={} ms",
                    receivedBatches, receivedSections, installedBatches, installedResults.size(),
                    pendingBlasResults.size(), completedBlasExecution,
                    formatMillis(maxEnqueueAgeNanos(installedResults)), formatMillis(updateNanos));
        }
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }
}

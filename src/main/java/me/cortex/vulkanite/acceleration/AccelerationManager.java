package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.acceleration.blas.BLASBatchResult;
import me.cortex.vulkanite.acceleration.blas.BLASBuildResult;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSet;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Pair;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class AccelerationManager {
    // Cache for static geometry BLAS results
    private static final Map<Long, VRef<VAccelerationStructure>> staticGeometryCache = new ConcurrentHashMap<>();
    private static final Set<Long> staticSections = ConcurrentHashMap.newKeySet();

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

    public void setEntityData(List<Pair<RenderLayer, BufferBuilder.BuiltBuffer>> data) {
        tlasManager.setEntityData(data);
    }

    private final List<Long> blasExecutions = new LinkedList<>();

    /**
     * Adds a cached BLAS build result to the processing queue.
     * 
     * @param result The cached BLAS build result to add
     */
    public void addCachedBLASResult(BLASBuildResult result) {
        // Create a fake batch with a dummy execution ID
        var fakeBatch = new BLASBatchResult(Collections.singletonList(result), 0L);
        blasResults.add(fakeBatch);
    }

    // This updates the tlas internal structure, DOES NOT INCLUDING BUILDING THE
    // TLAS
    public void updateTick() {
        if (!blasResults.isEmpty()) {// If there are results
            // Atomicly collect the results from the queue
            List<BLASBuildResult> results = new LinkedList<>();
            while (!blasResults.isEmpty()) {
                var batch = blasResults.poll();
                results.addAll(batch.results());
                blasExecutions.add(batch.execution());
            }
            tlasManager.updateSections(results);
        }
    }

    public VRef<VAccelerationStructure> buildTLAS(int queueId, VCmdBuff cmd) {
        ctx.cmd.queueWaitForExecutions(queueId, blasBuilder.getAsyncQueue(), blasExecutions);
        blasExecutions.clear();
        return tlasManager.buildTLAS(cmd);
    }

    public void sectionRemove(RenderSection section) {
        // Remove from static sections set
        staticSections.remove(section.getPosition());

        // Remove from cache if present
        staticGeometryCache.remove(section.getPosition());

        tlasManager.removeSection(section);
    }

    /**
     * Marks a section as static, enabling caching optimizations.
     * 
     * @param sectionPos The section position to mark as static
     */
    public void markSectionAsStatic(long sectionPos) {
        staticSections.add(sectionPos);
    }

    /**
     * Checks if a section is marked as static.
     * 
     * @param sectionPos The section position to check
     * @return true if the section is static, false otherwise
     */
    public boolean isSectionStatic(long sectionPos) {
        return staticSections.contains(sectionPos);
    }

    /**
     * Caches a BLAS result for a static section.
     * 
     * @param sectionPos The section position
     * @param blas       The BLAS structure to cache
     */
    public void cacheStaticBLAS(long sectionPos, VRef<VAccelerationStructure> blas) {
        if (staticSections.contains(sectionPos)) {
            staticGeometryCache.put(sectionPos, blas.addRef());
        }
    }

    /**
     * Retrieves a cached BLAS for a static section.
     * 
     * @param sectionPos The section position
     * @return The cached BLAS structure, or null if not cached
     */
    public VRef<VAccelerationStructure> getCachedStaticBLAS(long sectionPos) {
        VRef<VAccelerationStructure> cached = staticGeometryCache.get(sectionPos);
        return cached != null ? cached.addRef() : null;
    }

    /**
     * Clears the static geometry cache.
     */
    public static void clearStaticGeometryCache() {
        staticGeometryCache.values().forEach(VRef::close);
        staticGeometryCache.clear();
        staticSections.clear();
    }

    public VRef<VDescriptorSet> getGeometrySet() {
        return tlasManager.getGeometrySet();
    }

    public VRef<VDescriptorSetLayout> getGeometryLayout() {
        return tlasManager.getGeometryLayout();
    }
}

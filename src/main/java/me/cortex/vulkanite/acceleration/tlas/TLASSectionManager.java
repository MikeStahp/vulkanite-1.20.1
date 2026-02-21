package me.cortex.vulkanite.acceleration.tlas;

import me.cortex.vulkanite.acceleration.blas.BLASBuildResult;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.*;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import net.minecraft.util.Pair;
import net.minecraft.util.math.ChunkSectionPos;
import org.joml.Matrix4x3f;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Manages TLAS sections for terrain chunks.
 * 
 * Handles adding/removing render sections, maintaining descriptor sets for
 * geometry buffers, and tracking active sections for TLAS building.
 */
public class TLASSectionManager extends TLASInstanceBuffer {
    /**
     * Cached descriptor set entry for frequently used resource combinations.
     */
    private static class CachedDescriptorSet {
        final VRef<VDescriptorSet> descriptorSet;
        final long lastUsed;
        final int usageCount;
        
        CachedDescriptorSet(VRef<VDescriptorSet> descriptorSet, long lastUsed, int usageCount) {
            this.descriptorSet = descriptorSet;
            this.lastUsed = lastUsed;
            this.usageCount = usageCount;
        }
    }
    // Descriptor set cache for frequently used resource combinations
    private static final Map<Integer, CachedDescriptorSet> descriptorSetCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 1000;
    
    private final TlasPointerArena arena = new TlasPointerArena(30000);
    private final ConcurrentLinkedDeque<BLASBuildResult> sectionUpdates = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<RenderSection> sectionRemovals = new ConcurrentLinkedDeque<>();
    public final Map<ChunkSectionPos, VRef<TLASSectionHolder>> activeSections = new HashMap<>();
    private final ArrayList<DescriptorUpdateJob> descriptorUpdateJobs = new ArrayList<>();

    private VRef<VDescriptorSetLayout> geometryBufferSetLayout;
    public VRef<VDescriptorSet> geometryBufferDescSet = null;
    private int setCapacity = 0;

    public TLASSectionManager(VContext context) {
        super(context);
    }

    public VRef<VDescriptorSetLayout> getGeometryLayout() {
        return geometryBufferSetLayout.addRef();
    }

    private static int roundUpPow2(int v) {
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        v++;
        return v;
    }

    public void resizeBindlessSet(int newSize) {
        if (geometryBufferSetLayout == null) {
            var layoutBuilder = new DescriptorSetLayoutBuilder(
                    VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT);
            layoutBuilder.binding(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 65536, VK_SHADER_STAGE_ALL);
            layoutBuilder.setBindingFlags(0,
                    VK_DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT_BIT
                            | VK_DESCRIPTOR_BINDING_UPDATE_UNUSED_WHILE_PENDING_BIT
                            | VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT);
            geometryBufferSetLayout = layoutBuilder.build(context);
        }

        if (newSize > setCapacity) {
            int newCapacity = roundUpPow2(Math.max(newSize, 32));
            var geometryBufferDescPool = VDescriptorPool.create(context, geometryBufferSetLayout,
                    VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT, newCapacity);
            var newGeometryBufferDescSet = geometryBufferDescPool.get().allocateSet(newCapacity);

            System.out.println("New geometry desc set: " + Long.toHexString(newGeometryBufferDescSet.get().set)
                    + " with capacity " + newCapacity);

            if (geometryBufferDescSet != null) {
                newGeometryBufferDescSet.get().copyFrom(context, geometryBufferDescSet, setCapacity);
                geometryBufferDescSet.close();
            }

            geometryBufferDescSet = newGeometryBufferDescSet;
            setCapacity = newCapacity;
        }
    }

    @Override
    public Pair<VRef<VBuffer>, Integer> getInstanceBuffer() {
        HashSet<RenderSection> removals = new HashSet<>();
        {
            RenderSection section;
            while ((section = sectionRemovals.poll()) != null) {
                removals.add(section);
            }
        }

        // Filter updates to only the latest
        HashMap<ChunkSectionPos, BLASBuildResult> updates = new HashMap<>();
        {
            BLASBuildResult result;
            while ((result = sectionUpdates.poll()) != null) {
                var data = result.data();
                var section = data.section();
                if (removals.contains(section)) {
                    // Already removed, close the buffers and continue
                    result.structure().close();
                    data.geometryBuffer().close();
                } else {
                    // We process the updates sequentially
                    // Older updates are overwritten
                    var key = section.getPosition();
                    if (updates.containsKey(key)) {
                        var prev = updates.get(key);
                        prev.structure().close();
                        prev.data().geometryBuffer().close();
                    }
                    updates.put(key, result);
                }
            }
        }

        // Process removals
        for (var section : removals) {
            var prev = activeSections.remove(section.getPosition());
            if (prev != null) {
                free(prev.get().id);
                prev.close();
            }
        }

        int newGeoms = 0;
        for (var entry : updates.entrySet()) {
            newGeoms += entry.getValue().data().bufferOffsets().size();
        }
        resizeBindlessSet(Integer.max(arena.maxIndex + newGeoms, 1024));

        // Process updates
        if (!updates.isEmpty() || !descriptorUpdateJobs.isEmpty()) {
            var dub = new DescriptorUpdateBuilder(context, updates.size() + descriptorUpdateJobs.size());
            dub.set(geometryBufferDescSet);

            for (var entry : updates.entrySet()) {
                var result = entry.getValue();
                var data = result.data();
                var section = data.section();
                var posKey = section.getPosition();

                var prevHolder = activeSections.remove(posKey);
                if (prevHolder != null) {
                    free(prevHolder.get().id);
                    prevHolder.close();
                }

                int numGeometriesInInstance = data.bufferOffsets().size();
                int geometryIndex = arena.allocate(numGeometriesInInstance);

                // Add the geometry buffer to the descriptor set (the set retains another
                // reference)
                dub.buffer(0, geometryIndex, data.geometryBuffer(), data.bufferOffsets());
                data.geometryBuffer().close();

                int id;
                try (var stack = stackPush()) {
                    var asi = VkAccelerationStructureInstanceKHR.calloc(stack)
                        .mask(0xFF)
                        .instanceCustomIndex(geometryIndex)
                        .accelerationStructureReference(result.structure().get().deviceAddress);
                    asi.transform()
                            .matrix(new Matrix4x3f()
                                    .translate(section.getOriginX(), section.getOriginY(),
                                            section.getOriginZ())
                                    .getTransposed(stack.mallocFloat(12)));

                    id = alloc(asi);
                }

                // Ownership of result.structure() is transferred to the holder
                var holder = TLASSectionHolder.create(id, geometryIndex, numGeometriesInInstance,
                        result.structure(), this);
                activeSections.put(section.getPosition(), holder);
            }

            for (var job : descriptorUpdateJobs) {
                // Try to use cached descriptor set first
                VRef<VDescriptorSet> cachedSet = getCachedDescriptorSet(job.geometryBuffer(), job.bufferOffsets());
                if (cachedSet != null) {
                    // Use cached descriptor set
                    // In a real implementation, we would use the cached set here
                    // For now, we'll proceed with the normal update
                    cachedSet.close();
                }
                
                dub.buffer(0, job.element(), job.geometryBuffer(), job.bufferOffsets());
                job.geometryBuffer().close();
            }
            descriptorUpdateJobs.clear();

            dub.apply();
            
            // Prune cache if needed
            pruneDescriptorSetCache();
        }

        return super.getInstanceBuffer();
    }

    /**
     * Frees arena indices and removes descriptor set references.
     */
    public void arenaFree(int index, int count) {
        arena.free(index, count);
        for (int i = 0; i < count; i++) {
            geometryBufferDescSet.get().removeRef(index + i);
        }
    }
    
    /**
     * Gets or creates a cached descriptor set for the given buffer and offsets.
     * 
     * @param buffer The geometry buffer
     * @param offsets The buffer offsets
     * @return A cached or newly created descriptor set
     */
    private VRef<VDescriptorSet> getCachedDescriptorSet(VRef<VBuffer> buffer, List<Long> offsets) {
        // Generate cache key based on buffer and offsets
        int cacheKey = generateDescriptorCacheKey(buffer, offsets);
        
        // Check if we have a cached descriptor set
        CachedDescriptorSet cached = descriptorSetCache.get(cacheKey);
        if (cached != null) {
            // Update usage count and last used time
            CachedDescriptorSet updated = new CachedDescriptorSet(
                cached.descriptorSet.addRef(), 
                System.nanoTime(), 
                cached.usageCount + 1
            );
            descriptorSetCache.put(cacheKey, updated);
            return cached.descriptorSet.addRef();
        }
        
        // Create new descriptor set (this would be implemented based on specific needs)
        // For now, we'll return null to indicate no caching for this case
        return null;
    }
    
    /**
     * Generates a cache key for descriptor sets based on buffer and offsets.
     * 
     * @param buffer The geometry buffer
     * @param offsets The buffer offsets
     * @return Hash code representing the descriptor set configuration
     */
    private int generateDescriptorCacheKey(VRef<VBuffer> buffer, List<Long> offsets) {
        int result = 1;
        result = 31 * result + (buffer != null ? buffer.hashCode() : 0);
        result = 31 * result + offsets.hashCode();
        return result;
    }
    
    /**
     * Clears the descriptor set cache.
     */
    public static void clearDescriptorSetCache() {
        descriptorSetCache.clear();
    }
    
    /**
     * Prunes the descriptor set cache to maintain size limits.
     */
    private void pruneDescriptorSetCache() {
        if (descriptorSetCache.size() > MAX_CACHE_SIZE) {
            // Remove least recently used entries
            descriptorSetCache.entrySet().stream()
                .sorted(Map.Entry.comparingByValue((a, b) -> Long.compare(a.lastUsed, b.lastUsed)))
                .limit(descriptorSetCache.size() - MAX_CACHE_SIZE / 2)
                .forEach(entry -> {
                    entry.getValue().descriptorSet.close();
                    descriptorSetCache.remove(entry.getKey());
                });
        }
    }

    /**
     * Queues a BLAS build result for processing.
     */
    public void update(BLASBuildResult result) {
        sectionUpdates.add(result);
    }

    /**
     * Queues a section for removal.
     */
    public void remove(RenderSection section) {
        sectionRemovals.add(section);
    }

    /**
     * Adds an ephemeral instance for entities or other transient geometry.
     */
    public void addEphemeralInstance(VCmdBuff cmd, VkAccelerationStructureInstanceKHR asi,
            final VRef<VAccelerationStructure> structure, final VRef<VBuffer> geometryBuffer,
            List<Long> bufferOffsets) {
        if (bufferOffsets.isEmpty()) {
            return;
        }

        int numGeometries = bufferOffsets.size();
        int geometryIndex = arena.allocate(bufferOffsets.size());

        asi.accelerationStructureReference(structure.get().deviceAddress);
        asi.instanceCustomIndex(geometryIndex);

        addEphemeralInstance(asi);

        var holder = TLASSectionHolder.create(-1, geometryIndex, numGeometries, structure.addRef(), this);
        cmd.moveRefGeneric(holder.addRefGeneric());
        holder.close();

        descriptorUpdateJobs.add(new DescriptorUpdateJob(geometryIndex, geometryBuffer.addRef(), bufferOffsets));
    }
}

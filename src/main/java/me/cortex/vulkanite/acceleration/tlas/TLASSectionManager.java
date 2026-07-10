package me.cortex.vulkanite.acceleration.tlas;

import me.cortex.vulkanite.acceleration.blas.BLASBuildResult;
import me.cortex.vulkanite.acceleration.blas.ProceduralBLAS;
import me.cortex.vulkanite.acceleration.blas.ProceduralBLASDisposition;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(TLASSectionManager.class);
    private static final long INFO_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final long SLOW_SECTION_TABLE_LOG_NANOS = 2_000_000L;

    private final TlasPointerArena arena = new TlasPointerArena(30000);
    private final ConcurrentLinkedDeque<BLASBuildResult> sectionUpdates = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<RenderSection> sectionRemovals = new ConcurrentLinkedDeque<>();
    public final Map<ChunkSectionPos, VRef<TLASSectionHolder>> activeSections = new HashMap<>();
    private final ArrayList<DescriptorUpdateJob> descriptorUpdateJobs = new ArrayList<>();

    private VRef<VDescriptorSetLayout> geometryBufferSetLayout;
    public VRef<VDescriptorSet> geometryBufferDescSet = null;
    private int setCapacity = 0;
    private long lastSectionTableInfoLogNanos;

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

            LOGGER.debug("New geometry desc set: {} with capacity {}",
                    Long.toHexString(newGeometryBufferDescSet.get().set), newCapacity);

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
        long startNanos = System.nanoTime();
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
                    result.close();
                } else {
                    // We process the updates sequentially
                    // Older updates are overwritten
                    var key = section.getPosition();
                    if (updates.containsKey(key)) {
                        var prev = updates.get(key);
                        result = mergeRetainedProceduralResult(result, prev);
                        prev.close();
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
        int updateCount = updates.size();
        int removalCount = removals.size();
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
                VRef<ProceduralBLAS> proceduralBlas = resolveProceduralBlas(result, prevHolder);
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
                        result.structure(), proceduralBlas, geometryBufferDescSet.addRef(), this);
                activeSections.put(section.getPosition(), holder);
            }

            for (var job : descriptorUpdateJobs) {
                job.holder().get().attachGeometryDescriptorSet(geometryBufferDescSet.addRef());
                dub.buffer(0, job.element(), job.geometryBuffer(), job.bufferOffsets());
                job.geometryBuffer().close();
                job.holder().close();
            }
            descriptorUpdateJobs.clear();

            dub.apply();
        }

        Pair<VRef<VBuffer>, Integer> instanceBuffer = super.getInstanceBuffer();
        if (updateCount > 0 || removalCount > 0) {
            long cpuNanos = System.nanoTime() - startNanos;
            long now = System.nanoTime();
            boolean info = cpuNanos >= SLOW_SECTION_TABLE_LOG_NANOS
                    || now - lastSectionTableInfoLogNanos >= INFO_LOG_INTERVAL_NANOS;
            if (info) {
                lastSectionTableInfoLogNanos = now;
                LOGGER.info("[Vulkanite] TLAS section table: updates={}, removals={}, newGeometryRanges={}, activeSections={}, instances={}, cpu={} ms",
                        updateCount, removalCount, newGeoms, activeSections.size(), instanceBuffer.getRight(),
                        formatMillis(cpuNanos));
            } else {
                LOGGER.debug("[Vulkanite] TLAS section table: updates={}, removals={}, newGeometryRanges={}, activeSections={}, instances={}, cpu={} ms",
                        updateCount, removalCount, newGeoms, activeSections.size(), instanceBuffer.getRight(),
                        formatMillis(cpuNanos));
            }
        }
        return instanceBuffer;
    }

    /**
     * Frees arena indices and removes descriptor set references.
     */
    public void arenaFree(int index, int count, VRef<VDescriptorSet> descriptorSet) {
        arena.free(index, count);
        VRef<VDescriptorSet> set = descriptorSet != null ? descriptorSet : geometryBufferDescSet;
        if (set != null) {
            for (int i = 0; i < count; i++) {
                set.get().removeRef(index + i);
            }
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
    public VRef<TLASSectionHolder> addEphemeralInstance(VkAccelerationStructureInstanceKHR asi,
            final VRef<VAccelerationStructure> structure, final VRef<VBuffer> geometryBuffer,
            List<Long> bufferOffsets) {
        if (bufferOffsets.isEmpty()) {
            throw new IllegalArgumentException("Cannot add an ephemeral TLAS instance with no geometry");
        }

        int numGeometries = bufferOffsets.size();
        int geometryIndex = arena.allocate(bufferOffsets.size());

        asi.accelerationStructureReference(structure.get().deviceAddress);
        asi.instanceCustomIndex(geometryIndex);

        addEphemeralInstance(asi);

        var holder = TLASSectionHolder.create(-1, geometryIndex, numGeometries,
                structure.addRef(), null, null, this);
        descriptorUpdateJobs.add(new DescriptorUpdateJob(geometryIndex, geometryBuffer.addRef(), bufferOffsets,
                holder.addRef()));
        return holder;
    }

    public void destroy() {
        for (var holder : List.copyOf(activeSections.values())) {
            holder.close();
        }
        activeSections.clear();

        BLASBuildResult update;
        while ((update = sectionUpdates.poll()) != null) {
            update.close();
        }
        sectionRemovals.clear();

        for (var job : descriptorUpdateJobs) {
            job.geometryBuffer().close();
            job.holder().close();
        }
        descriptorUpdateJobs.clear();

        if (geometryBufferDescSet != null) {
            geometryBufferDescSet.close();
            geometryBufferDescSet = null;
        }
        setCapacity = 0;
        if (geometryBufferSetLayout != null) {
            geometryBufferSetLayout.close();
            geometryBufferSetLayout = null;
        }
        super.destroy();
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static BLASBuildResult mergeRetainedProceduralResult(
            BLASBuildResult result,
            BLASBuildResult previous) {
        if (result.proceduralDisposition() != ProceduralBLASDisposition.RETAIN) {
            return result;
        }

        return switch (previous.proceduralDisposition()) {
            case REPLACE -> new BLASBuildResult(
                    result.structure(),
                    result.data(),
                    Optional.of(previous.proceduralBlas().orElseThrow().addRef()),
                    ProceduralBLASDisposition.REPLACE);
            case CLEAR -> new BLASBuildResult(
                    result.structure(),
                    result.data(),
                    Optional.empty(),
                    ProceduralBLASDisposition.CLEAR);
            case RETAIN -> result;
        };
    }

    private static VRef<ProceduralBLAS> resolveProceduralBlas(
            BLASBuildResult result,
            VRef<TLASSectionHolder> previousHolder) {
        return switch (result.proceduralDisposition()) {
            case REPLACE -> result.proceduralBlas().orElseThrow();
            case CLEAR -> null;
            case RETAIN -> previousHolder == null ? null : previousHolder.get().proceduralBlas();
        };
    }
}

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
                dub.buffer(0, job.element(), job.geometryBuffer(), job.bufferOffsets());
                job.geometryBuffer().close();
            }
            descriptorUpdateJobs.clear();

            dub.apply();
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

        descriptorUpdateJobs.add(new DescriptorUpdateJob(geometryIndex, geometryBuffer.addRef(), bufferOffsets));
        return TLASSectionHolder.create(-1, geometryIndex, numGeometries, structure.addRef(), this);
    }

    public void destroy() {
        for (var holder : List.copyOf(activeSections.values())) {
            holder.close();
        }
        activeSections.clear();

        BLASBuildResult update;
        while ((update = sectionUpdates.poll()) != null) {
            update.structure().close();
            update.data().geometryBuffer().close();
        }
        sectionRemovals.clear();

        for (var job : descriptorUpdateJobs) {
            job.geometryBuffer().close();
        }
        descriptorUpdateJobs.clear();

        if (geometryBufferDescSet != null) {
            geometryBufferDescSet.close();
            geometryBufferDescSet = null;
        }
        if (geometryBufferSetLayout != null) {
            geometryBufferSetLayout.close();
            geometryBufferSetLayout = null;
        }
        super.destroy();
    }
}

package me.cortex.vulkanite.acceleration.tlas;

import me.cortex.vulkanite.acceleration.HybridAccelerationConfig;
import me.cortex.vulkanite.acceleration.HybridSbtLayout;
import me.cortex.vulkanite.acceleration.blas.BLASBuildResult;
import me.cortex.vulkanite.acceleration.blas.ProceduralBLAS;
import me.cortex.vulkanite.acceleration.blas.ProceduralBLASDisposition;
import me.cortex.vulkanite.acceleration.blas.ShadowTriangleBLAS;
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
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
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
    private static final int PROCEDURAL_INSTANCE_UPLOAD_SLOTS = 3;
    private final boolean filteredShadowGeometry =
            HybridAccelerationConfig.fromSystemProperties().filterShadowGeometry();

    private final TlasPointerArena arena = new TlasPointerArena(30000);
    private final ConcurrentLinkedDeque<BLASBuildResult> sectionUpdates = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<RenderSection> sectionRemovals = new ConcurrentLinkedDeque<>();
    public final Map<ChunkSectionPos, VRef<TLASSectionHolder>> activeSections = new HashMap<>();
    private final ArrayList<DescriptorUpdateJob> descriptorUpdateJobs = new ArrayList<>();

    private VRef<VDescriptorSetLayout> geometryBufferSetLayout;
    public VRef<VDescriptorSet> geometryBufferDescSet = null;
    private int setCapacity = 0;
    private long lastSectionTableInfoLogNanos;
    private final VRef<VBuffer>[] proceduralInstanceUploadBuffers;
    private final long[] proceduralInstanceUploadCapacities = new long[PROCEDURAL_INSTANCE_UPLOAD_SLOTS];
    private int proceduralInstanceUploadCursor;
    private final VRef<VBuffer>[] hybridInstanceUploadBuffers;
    private final long[] hybridInstanceUploadCapacities = new long[PROCEDURAL_INSTANCE_UPLOAD_SLOTS];
    private int hybridInstanceUploadCursor;
    private final VRef<VBuffer>[] reflectionInstanceUploadBuffers;
    private final long[] reflectionInstanceUploadCapacities = new long[PROCEDURAL_INSTANCE_UPLOAD_SLOTS];
    private int reflectionInstanceUploadCursor;

    @SuppressWarnings("unchecked")
    public TLASSectionManager(VContext context) {
        super(context);
        proceduralInstanceUploadBuffers = new VRef[PROCEDURAL_INSTANCE_UPLOAD_SLOTS];
        hybridInstanceUploadBuffers = new VRef[PROCEDURAL_INSTANCE_UPLOAD_SLOTS];
        reflectionInstanceUploadBuffers = new VRef[PROCEDURAL_INSTANCE_UPLOAD_SLOTS];
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
            // Reserve one additional bindless slot when the update may install a
            // procedural payload. RETAIN can inherit one from the prior holder.
            BLASBuildResult result = entry.getValue();
            int shadowDescriptors = result.shadowTriangleBlas()
                    .map(ref -> ref.get().geometryCount())
                    .orElse(0);
            newGeoms += result.data().bufferOffsets().size() + shadowDescriptors + 1;
        }
        int updateCount = updates.size();
        int removalCount = removals.size();
        resizeBindlessSet(Integer.max(arena.maxIndex + newGeoms, 1024));

        // Process updates
        if (!updates.isEmpty() || !descriptorUpdateJobs.isEmpty()) {
            var dub = new DescriptorUpdateBuilder(
                    context,
                    updates.size() * 3 + descriptorUpdateJobs.size());
            dub.set(geometryBufferDescSet);

            for (var entry : updates.entrySet()) {
                var result = entry.getValue();
                var data = result.data();
                var section = data.section();
                var posKey = section.getPosition();

                var prevHolder = activeSections.remove(posKey);
                VRef<ProceduralBLAS> proceduralBlas = resolveProceduralBlas(result, prevHolder);
                VRef<ShadowTriangleBLAS> shadowTriangleBlas = result.shadowTriangleBlas().orElse(null);
                if (prevHolder != null) {
                    free(prevHolder.get().id);
                    prevHolder.close();
                }

                int numGeometriesInInstance = data.bufferOffsets().size();
                int shadowGeometryCount = shadowTriangleBlas == null
                        ? 0
                        : shadowTriangleBlas.get().geometryCount();
                int descriptorCount = numGeometriesInInstance
                        + (proceduralBlas == null ? 0 : 1)
                        + shadowGeometryCount;
                int geometryIndex = arena.allocate(descriptorCount);
                int proceduralGeometryIndex = proceduralBlas == null
                        ? -1
                        : geometryIndex + numGeometriesInInstance;
                int shadowGeometryIndex = shadowTriangleBlas == null
                        ? -1
                        : geometryIndex + numGeometriesInInstance + (proceduralBlas == null ? 0 : 1);

                // Add the geometry buffer to the descriptor set (the set retains another
                // reference)
                dub.buffer(0, geometryIndex, data.geometryBuffer(), data.bufferOffsets());
                data.geometryBuffer().close();
                if (proceduralBlas != null) {
                    VRef<VBuffer> payloadBuffer = proceduralBlas.get().payloadBuffer();
                    try {
                        dub.buffer(0, proceduralGeometryIndex, payloadBuffer, List.of(0L));
                    } finally {
                        payloadBuffer.close();
                    }
                }
                if (shadowTriangleBlas != null) {
                    VRef<VBuffer> shadowBuffer = shadowTriangleBlas.get().geometryBuffer();
                    try {
                        dub.buffer(0, shadowGeometryIndex, shadowBuffer,
                                shadowTriangleBlas.get().bufferOffsets());
                    } finally {
                        shadowBuffer.close();
                    }
                }

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
                        descriptorCount, proceduralGeometryIndex,
                        shadowGeometryIndex, shadowGeometryCount, result.filteredShadowGeometry(),
                        result.structure(), shadowTriangleBlas, proceduralBlas,
                        geometryBufferDescSet.addRef(), this);
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
                numGeometries, -1,
                -1, 0, false,
                structure.addRef(), null, null, null, this);
        descriptorUpdateJobs.add(new DescriptorUpdateJob(geometryIndex, geometryBuffer.addRef(), bufferOffsets,
                holder.addRef()));
        return holder;
    }

    /**
     * Uploads one instance per section-owned procedural BLAS. The instance
     * custom index addresses that section's packed brick payload in the same
     * bindless descriptor array used by triangle geometry.
     */
    public Pair<VRef<VBuffer>, Integer> getProceduralInstanceBuffer() {
        int proceduralCount = 0;
        for (VRef<TLASSectionHolder> holder : activeSections.values()) {
            if (holder.get().hasProceduralBlas()) {
                proceduralCount++;
            }
        }

        long size = Math.max(
                VkAccelerationStructureInstanceKHR.SIZEOF,
                VkAccelerationStructureInstanceKHR.SIZEOF * (long) proceduralCount);
        VRef<VBuffer> data = getProceduralUploadBuffer(size);
        long pointer = data.get().map();
        try (var stack = stackPush()) {
            var instance = VkAccelerationStructureInstanceKHR.calloc(stack);
            var transform = stack.mallocFloat(12);
            for (var entry : activeSections.entrySet()) {
                TLASSectionHolder holder = entry.getValue().get();
                if (!holder.hasProceduralBlas()) {
                    continue;
                }

                VRef<ProceduralBLAS> proceduralBlas = holder.proceduralBlas();
                VRef<VAccelerationStructure> structure = proceduralBlas.get().structure();
                try {
                    var sectionPos = entry.getKey();
                    transform.clear();
                    new Matrix4x3f()
                            .translate(sectionPos.getMinX(), sectionPos.getMinY(), sectionPos.getMinZ())
                            .getTransposed(transform);
                    transform.rewind();

                    instance
                            .mask(HybridSbtLayout.PROCEDURAL_INSTANCE_MASK)
                            .flags(0)
                            .instanceCustomIndex(holder.proceduralGeometryIndex())
                            .instanceShaderBindingTableRecordOffset(
                                    HybridSbtLayout.PROCEDURAL_TERRAIN_HIT_GROUP)
                            .accelerationStructureReference(structure.get().deviceAddress);
                    instance.transform().matrix(transform);

                    if (instance.instanceShaderBindingTableRecordOffset()
                            != HybridSbtLayout.PROCEDURAL_TERRAIN_HIT_GROUP) {
                        throw new IllegalStateException("Procedural TLAS instance selected the wrong SBT record");
                    }
                    MemoryUtil.memCopy(instance.address(), pointer,
                            VkAccelerationStructureInstanceKHR.SIZEOF);
                    pointer += VkAccelerationStructureInstanceKHR.SIZEOF;
                } finally {
                    structure.close();
                    proceduralBlas.close();
                }
            }
        } finally {
            data.get().unmap();
            data.get().flush();
        }
        return new Pair<>(data, proceduralCount);
    }

    /** Emits terrain triangles, entity triangles, and procedural terrain for the production shadow TLAS. */
    public Pair<VRef<VBuffer>, Integer> getHybridShadowInstanceBuffer() {
        int proceduralCount = 0;
        int terrainTriangleCount = 0;
        for (VRef<TLASSectionHolder> holder : activeSections.values()) {
            TLASSectionHolder section = holder.get();
            boolean canUseFiltered = filteredShadowGeometry
                    && section.hasFilteredShadowGeometry()
                    && section.hasProceduralBlas();
            if (canUseFiltered) proceduralCount++;
            if (!canUseFiltered || section.hasShadowTriangleBlas()) {
                terrainTriangleCount++;
            }
        }
        int totalCount = terrainTriangleCount + ephemeralInstances.size() + proceduralCount;
        long size = Math.max(VkAccelerationStructureInstanceKHR.SIZEOF,
                VkAccelerationStructureInstanceKHR.SIZEOF * (long) totalCount);
        VRef<VBuffer> data = getHybridUploadBuffer(size);
        long pointer = data.get().map();
        try (var stack = stackPush()) {
            var rewritten = VkAccelerationStructureInstanceKHR.calloc(stack);
            var transform = stack.mallocFloat(12);
            for (var entry : activeSections.entrySet()) {
                TLASSectionHolder holder = entry.getValue().get();
                boolean canUseFiltered = filteredShadowGeometry
                        && holder.hasFilteredShadowGeometry()
                        && holder.hasProceduralBlas();
                if (canUseFiltered && !holder.hasShadowTriangleBlas()) {
                    // Every authoritative shadow quad in this section was replaced by
                    // the procedural representation.
                    continue;
                }

                VRef<VAccelerationStructure> triangleStructure;
                int geometryIndex;
                if (canUseFiltered) {
                    VRef<ShadowTriangleBLAS> shadow = holder.shadowTriangleBlas();
                    try {
                        triangleStructure = shadow.get().structure();
                    } finally {
                        shadow.close();
                    }
                    geometryIndex = holder.shadowGeometryIndex();
                } else {
                    triangleStructure = holder.triangleBlas();
                    geometryIndex = holder.geometryIndex;
                }
                try {
                    transform.clear();
                    var pos = entry.getKey();
                    new Matrix4x3f().translate(pos.getMinX(), pos.getMinY(), pos.getMinZ())
                            .getTransposed(transform);
                    transform.rewind();
                    rewritten.transform().matrix(transform);
                    rewritten.mask(HybridSbtLayout.TERRAIN_TRIANGLE_INSTANCE_MASK)
                            .flags(0)
                            .instanceCustomIndex(geometryIndex)
                            .instanceShaderBindingTableRecordOffset(
                                    HybridSbtLayout.SHADOW_TRIANGLE_TERRAIN_HIT_GROUP)
                            .accelerationStructureReference(triangleStructure.get().deviceAddress);
                    HybridSbtLayout.assertInstanceCompatibility(HybridSbtLayout.GeometryType.TRIANGLES,
                            rewritten.instanceShaderBindingTableRecordOffset(), rewritten.mask());
                    MemoryUtil.memCopy(rewritten.address(), pointer, VkAccelerationStructureInstanceKHR.SIZEOF);
                    pointer += VkAccelerationStructureInstanceKHR.SIZEOF;
                } finally {
                    triangleStructure.close();
                }
            }
            for (var entity : ephemeralInstances) {
                rewritten.set(entity);
                rewritten.mask(HybridSbtLayout.ENTITY_TRIANGLE_INSTANCE_MASK)
                        .instanceShaderBindingTableRecordOffset(
                                HybridSbtLayout.SHADOW_ENTITY_TRIANGLE_HIT_GROUP);
                HybridSbtLayout.assertInstanceCompatibility(HybridSbtLayout.GeometryType.TRIANGLES,
                        rewritten.instanceShaderBindingTableRecordOffset(), rewritten.mask());
                MemoryUtil.memCopy(rewritten.address(), pointer, VkAccelerationStructureInstanceKHR.SIZEOF);
                pointer += VkAccelerationStructureInstanceKHR.SIZEOF;
            }
            for (var entry : activeSections.entrySet()) {
                TLASSectionHolder holder = entry.getValue().get();
                boolean canUseFiltered = filteredShadowGeometry
                        && holder.hasFilteredShadowGeometry()
                        && holder.hasProceduralBlas();
                if (!canUseFiltered) continue;
                VRef<ProceduralBLAS> procedural = holder.proceduralBlas();
                VRef<VAccelerationStructure> structure = procedural.get().structure();
                try {
                    transform.clear();
                    var pos = entry.getKey();
                    new Matrix4x3f().translate(pos.getMinX(), pos.getMinY(), pos.getMinZ())
                            .getTransposed(transform);
                    transform.rewind();
                    rewritten.transform().matrix(transform);
                    rewritten.mask(HybridSbtLayout.PROCEDURAL_INSTANCE_MASK)
                            .flags(0)
                            .instanceCustomIndex(holder.proceduralGeometryIndex())
                            .instanceShaderBindingTableRecordOffset(
                                    HybridSbtLayout.SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP)
                            .accelerationStructureReference(structure.get().deviceAddress);
                    HybridSbtLayout.assertInstanceCompatibility(HybridSbtLayout.GeometryType.AABBS,
                            rewritten.instanceShaderBindingTableRecordOffset(), rewritten.mask());
                    MemoryUtil.memCopy(rewritten.address(), pointer, VkAccelerationStructureInstanceKHR.SIZEOF);
                    pointer += VkAccelerationStructureInstanceKHR.SIZEOF;
                } finally {
                    structure.close();
                    procedural.close();
                }
            }
        } finally {
            data.get().unmap();
            data.get().flush();
            clearEphemeralInstances();
        }
        return new Pair<>(data, totalCount);
    }

    public int hybridShadowTerrainTriangleCount() {
        int result = 0;
        for (VRef<TLASSectionHolder> ref : activeSections.values()) {
            TLASSectionHolder holder = ref.get();
            boolean canUseFiltered = filteredShadowGeometry
                    && holder.hasFilteredShadowGeometry()
                    && holder.hasProceduralBlas();
            if (!canUseFiltered || holder.hasShadowTriangleBlas()) {
                result++;
            }
        }
        return result;
    }

    public int hybridShadowProceduralCount() {
        int result = 0;
        for (VRef<TLASSectionHolder> holder : activeSections.values()) {
            TLASSectionHolder section = holder.get();
            if (filteredShadowGeometry
                    && section.hasFilteredShadowGeometry()
                    && section.hasProceduralBlas()) {
                result++;
            }
        }
        return result;
    }

    /**
     * Emits a material TLAS snapshot that keeps special terrain and entities on
     * triangles while selecting the procedural reflection hit group for regular
     * opaque cubes. Callers retain the full material BLAS in every holder as a
     * runtime fallback.
     */
    public Pair<VRef<VBuffer>, Integer> getHybridReflectionInstanceBuffer() {
        int terrainTriangleCount = 0;
        int proceduralCount = 0;
        for (VRef<TLASSectionHolder> ref : activeSections.values()) {
            TLASSectionHolder holder = ref.get();
            boolean proceduralReflection = canUseProceduralReflection(holder);
            if (!proceduralReflection || holder.hasShadowTriangleBlas()) terrainTriangleCount++;
            if (proceduralReflection) proceduralCount++;
        }
        int totalCount = terrainTriangleCount + proceduralCount + ephemeralInstances.size();
        long size = Math.max(VkAccelerationStructureInstanceKHR.SIZEOF,
                VkAccelerationStructureInstanceKHR.SIZEOF * (long) totalCount);
        VRef<VBuffer> data = getReflectionUploadBuffer(size);
        long pointer = data.get().map();
        try (var stack = stackPush()) {
            var instance = VkAccelerationStructureInstanceKHR.calloc(stack);
            var transform = stack.mallocFloat(12);
            for (var entry : activeSections.entrySet()) {
                TLASSectionHolder holder = entry.getValue().get();
                boolean proceduralReflection = canUseProceduralReflection(holder);
                transform.clear();
                var pos = entry.getKey();
                new Matrix4x3f().translate(pos.getMinX(), pos.getMinY(), pos.getMinZ())
                        .getTransposed(transform);
                transform.rewind();

                if (!proceduralReflection || holder.hasShadowTriangleBlas()) {
                    VRef<VAccelerationStructure> triangles;
                    int geometryIndex;
                    if (proceduralReflection) {
                        VRef<ShadowTriangleBLAS> shadow = holder.shadowTriangleBlas();
                        try {
                            triangles = shadow.get().structure();
                        } finally {
                            shadow.close();
                        }
                        geometryIndex = holder.shadowGeometryIndex();
                    } else {
                        triangles = holder.triangleBlas();
                        geometryIndex = holder.geometryIndex;
                    }
                    try {
                        instance.transform().matrix(transform);
                        instance.mask(HybridSbtLayout.TERRAIN_TRIANGLE_INSTANCE_MASK)
                                .flags(0)
                                .instanceCustomIndex(geometryIndex)
                                .instanceShaderBindingTableRecordOffset(
                                        HybridSbtLayout.TRIANGLE_TERRAIN_HIT_GROUP)
                                .accelerationStructureReference(triangles.get().deviceAddress);
                        HybridSbtLayout.assertInstanceCompatibility(HybridSbtLayout.GeometryType.TRIANGLES,
                                instance.instanceShaderBindingTableRecordOffset(), instance.mask());
                        MemoryUtil.memCopy(instance.address(), pointer, VkAccelerationStructureInstanceKHR.SIZEOF);
                        pointer += VkAccelerationStructureInstanceKHR.SIZEOF;
                    } finally {
                        triangles.close();
                    }
                }

                if (proceduralReflection) {
                    VRef<ProceduralBLAS> procedural = holder.proceduralBlas();
                    VRef<VAccelerationStructure> structure = procedural.get().structure();
                    try {
                        transform.rewind();
                        instance.transform().matrix(transform);
                        instance.mask(HybridSbtLayout.PROCEDURAL_INSTANCE_MASK)
                                .flags(0)
                                .instanceCustomIndex(holder.proceduralGeometryIndex())
                                .instanceShaderBindingTableRecordOffset(
                                        HybridSbtLayout.REFLECTION_PROCEDURAL_TERRAIN_HIT_GROUP)
                                .accelerationStructureReference(structure.get().deviceAddress);
                        HybridSbtLayout.assertInstanceCompatibility(HybridSbtLayout.GeometryType.AABBS,
                                instance.instanceShaderBindingTableRecordOffset(), instance.mask());
                        MemoryUtil.memCopy(instance.address(), pointer, VkAccelerationStructureInstanceKHR.SIZEOF);
                        pointer += VkAccelerationStructureInstanceKHR.SIZEOF;
                    } finally {
                        structure.close();
                        procedural.close();
                    }
                }
            }
            for (var entity : ephemeralInstances) {
                instance.set(entity);
                instance.mask(HybridSbtLayout.ENTITY_TRIANGLE_INSTANCE_MASK)
                        .instanceShaderBindingTableRecordOffset(HybridSbtLayout.ENTITY_TRIANGLE_HIT_GROUP);
                HybridSbtLayout.assertInstanceCompatibility(HybridSbtLayout.GeometryType.TRIANGLES,
                        instance.instanceShaderBindingTableRecordOffset(), instance.mask());
                MemoryUtil.memCopy(instance.address(), pointer, VkAccelerationStructureInstanceKHR.SIZEOF);
                pointer += VkAccelerationStructureInstanceKHR.SIZEOF;
            }
        } finally {
            data.get().unmap();
            data.get().flush();
        }
        return new Pair<>(data, totalCount);
    }

    private static boolean canUseProceduralReflection(TLASSectionHolder holder) {
        if (!holder.hasFilteredShadowGeometry() || !holder.hasProceduralBlas()) {
            return false;
        }
        VRef<ProceduralBLAS> procedural = holder.proceduralBlas();
        try {
            return procedural != null && procedural.get().hasCompleteReflectionMaterialPayload();
        } finally {
            if (procedural != null) {
                procedural.close();
            }
        }
    }

    public void discardHybridShadowInstanceSnapshot() {
        clearEphemeralInstances();
    }

    private VRef<VBuffer> getHybridUploadBuffer(long size) {
        int slot = hybridInstanceUploadCursor;
        hybridInstanceUploadCursor = (hybridInstanceUploadCursor + 1) % PROCEDURAL_INSTANCE_UPLOAD_SLOTS;
        if (hybridInstanceUploadBuffers[slot] == null || hybridInstanceUploadCapacities[slot] < size) {
            if (hybridInstanceUploadBuffers[slot] != null) hybridInstanceUploadBuffers[slot].close();
            long capacity = roundUpPow2(size);
            hybridInstanceUploadBuffers[slot] = context.memory.createBuffer(capacity,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            hybridInstanceUploadBuffers[slot].get()
                    .setDebugUtilsObjectName("Hybrid Shadow TLAS Instance Buffer " + slot);
            hybridInstanceUploadCapacities[slot] = capacity;
        }
        return hybridInstanceUploadBuffers[slot].addRef();
    }

    private VRef<VBuffer> getProceduralUploadBuffer(long size) {
        int slot = proceduralInstanceUploadCursor;
        proceduralInstanceUploadCursor =
                (proceduralInstanceUploadCursor + 1) % PROCEDURAL_INSTANCE_UPLOAD_SLOTS;
        if (proceduralInstanceUploadBuffers[slot] == null
                || proceduralInstanceUploadCapacities[slot] < size) {
            if (proceduralInstanceUploadBuffers[slot] != null) {
                proceduralInstanceUploadBuffers[slot].close();
            }
            long capacity = roundUpPow2(size);
            proceduralInstanceUploadBuffers[slot] = context.memory.createBuffer(
                    capacity,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    0,
                    VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            proceduralInstanceUploadBuffers[slot].get()
                    .setDebugUtilsObjectName("Procedural Debug TLAS Instance Buffer " + slot);
            proceduralInstanceUploadCapacities[slot] = capacity;
        }
        return proceduralInstanceUploadBuffers[slot].addRef();
    }

    private VRef<VBuffer> getReflectionUploadBuffer(long size) {
        int slot = reflectionInstanceUploadCursor;
        reflectionInstanceUploadCursor =
                (reflectionInstanceUploadCursor + 1) % PROCEDURAL_INSTANCE_UPLOAD_SLOTS;
        if (reflectionInstanceUploadBuffers[slot] == null
                || reflectionInstanceUploadCapacities[slot] < size) {
            if (reflectionInstanceUploadBuffers[slot] != null) {
                reflectionInstanceUploadBuffers[slot].close();
            }
            long capacity = roundUpPow2(size);
            reflectionInstanceUploadBuffers[slot] = context.memory.createBuffer(
                    capacity,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    0,
                    VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            reflectionInstanceUploadBuffers[slot].get()
                    .setDebugUtilsObjectName("Hybrid Reflection TLAS Instance Buffer " + slot);
            reflectionInstanceUploadCapacities[slot] = capacity;
        }
        return reflectionInstanceUploadBuffers[slot].addRef();
    }

    private static long roundUpPow2(long value) {
        value = Math.max(value, VkAccelerationStructureInstanceKHR.SIZEOF);
        long highest = Long.highestOneBit(value);
        return highest == value ? value : highest << 1;
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

        for (int i = 0; i < proceduralInstanceUploadBuffers.length; i++) {
            if (proceduralInstanceUploadBuffers[i] != null) {
                proceduralInstanceUploadBuffers[i].close();
                proceduralInstanceUploadBuffers[i] = null;
            }
            proceduralInstanceUploadCapacities[i] = 0L;
        }
        proceduralInstanceUploadCursor = 0;
        for (int i = 0; i < hybridInstanceUploadBuffers.length; i++) {
            if (hybridInstanceUploadBuffers[i] != null) {
                hybridInstanceUploadBuffers[i].close();
                hybridInstanceUploadBuffers[i] = null;
            }
            hybridInstanceUploadCapacities[i] = 0L;
        }
        hybridInstanceUploadCursor = 0;
        for (int i = 0; i < reflectionInstanceUploadBuffers.length; i++) {
            if (reflectionInstanceUploadBuffers[i] != null) {
                reflectionInstanceUploadBuffers[i].close();
                reflectionInstanceUploadBuffers[i] = null;
            }
            reflectionInstanceUploadCapacities[i] = 0L;
        }
        reflectionInstanceUploadCursor = 0;

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
                    result.shadowTriangleBlas(),
                    result.filteredShadowGeometry(),
                    Optional.of(previous.proceduralBlas().orElseThrow().addRef()),
                    ProceduralBLASDisposition.REPLACE);
            case CLEAR -> new BLASBuildResult(
                    result.structure(),
                    result.data(),
                    result.shadowTriangleBlas(),
                    result.filteredShadowGeometry(),
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

package me.cortex.vulkanite.acceleration.tlas;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VBuffer;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minecraft.util.Pair;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages the VkAccelerationStructureInstanceKHR buffer for TLAS building.
 * Uses a dense array with ID mapping for efficient instance management.
 * 
 * The buffer is kept dense (no holes) for efficient GPU access. When an
 * instance is freed, the last element is moved to fill the hole.
 */
public class TLASInstanceBuffer {
    private static final int INSTANCE_UPLOAD_SLOTS = 3;
    private static final int INITIAL_INSTANCE_CAPACITY = 32768;

    protected final VContext context;

    private final IntArrayFIFOQueue freeIds = new IntArrayFIFOQueue();
    private int maxInstances = 0;
    private VkAccelerationStructureInstanceKHR.Buffer instances = null;
    protected int count = 0;

    // Maps each location in the instance buffer to an id
    private int[] loc2id = new int[maxInstances];
    // Maps each id to a location in the instance buffer
    private int[] id2loc = new int[maxInstances];

    private final List<VkAccelerationStructureInstanceKHR> ephemeralInstances = new ArrayList<>();
    private final VRef<VBuffer>[] instanceUploadBuffers;
    private final long[] instanceUploadCapacities = new long[INSTANCE_UPLOAD_SLOTS];
    private int instanceUploadCursor = 0;

    @SuppressWarnings("unchecked")
    public TLASInstanceBuffer(VContext context) {
        this.context = context;
        this.instanceUploadBuffers = new VRef[INSTANCE_UPLOAD_SLOTS];
        resize(INITIAL_INSTANCE_CAPACITY);
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

    private static long roundUpPow2(long v) {
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        v |= v >> 32;
        v++;
        return v;
    }

    /**
     * Resizes internal buffers if needed.
     * 
     * @param newSize Required minimum size
     */
    public void resize(int newSize) {
        // Early return if already large enough
        if (newSize <= maxInstances && instances != null) {
            return;
        }

        int oldMaxInstances = maxInstances;
        int oldCount = count;
        boolean mappingsValid = loc2id.length >= oldCount && id2loc.length >= oldMaxInstances;
        if (!mappingsValid) {
            freeIds.clear();
            oldMaxInstances = 0;
            oldCount = 0;
            count = 0;
        }

        newSize = roundUpPow2(newSize);

        // Resize the instance buffer
        VkAccelerationStructureInstanceKHR.Buffer newBuffer = VkAccelerationStructureInstanceKHR.calloc(newSize);
        if (instances != null && mappingsValid) {
            newBuffer.put(instances);
            newBuffer.rewind();
        }
        if (instances != null) {
            instances.free();
        }
        instances = newBuffer;

        // Add new ids to the free list
        for (int i = oldMaxInstances; i < newSize; i++) {
            freeIds.enqueue(i);
        }

        // Resize the id mapping arrays
        int[] newLoc2Id = new int[newSize];
        int[] newId2Loc = new int[newSize];
        Arrays.fill(newLoc2Id, -1);
        Arrays.fill(newId2Loc, -1);
        System.arraycopy(loc2id, 0, newLoc2Id, 0, Math.min(oldCount, newLoc2Id.length));
        System.arraycopy(id2loc, 0, newId2Loc, 0, Math.min(oldMaxInstances, newId2Loc.length));
        loc2id = newLoc2Id;
        id2loc = newId2Loc;

        maxInstances = newSize;
    }

    /**
     * Allocates a new instance slot and copies the instance data.
     * 
     * @param instance The instance data to copy
     * @return The allocated instance ID
     */
    protected int alloc(VkAccelerationStructureInstanceKHR instance) {
        int loc = count;
        resize(Math.max(loc + 1, INITIAL_INSTANCE_CAPACITY));

        int id = freeIds.dequeueInt();

        // Append to the end (dense buffer)
        loc2id[loc] = id;
        id2loc[id] = loc;

        // Copy the instance to the buffer
        MemoryUtil.memCopy(instance.address(), instances.address(loc),
                VkAccelerationStructureInstanceKHR.SIZEOF);

        count = loc + 1;
        return id;
    }

    /**
     * Frees an instance by ID.
     * 
     * @param id The instance ID to free
     */
    protected void free(int id) {
        if (id < 0 || id >= maxInstances || id2loc[id] < 0) {
            throw new IllegalArgumentException("Invalid id");
        }

        freeIds.enqueue(id);

        int loc = id2loc[id];
        id2loc[id] = -1;
        loc2id[loc] = -1;

        count--;
        if (loc != count) {
            // Move the last element to fill the hole
            int lastId = loc2id[count];
            loc2id[count] = -1;
            loc2id[loc] = lastId;
            id2loc[lastId] = loc;
            MemoryUtil.memCopy(instances.address(count), instances.address(loc),
                    VkAccelerationStructureInstanceKHR.SIZEOF);
        }
    }

    /**
     * Adds an ephemeral instance that lasts only for the current frame.
     * 
     * @param asi The acceleration structure instance to add
     */
    public void addEphemeralInstance(VkAccelerationStructureInstanceKHR asi) {
        var newASI = VkAccelerationStructureInstanceKHR.calloc();
        newASI.set(asi);
        ephemeralInstances.add(newASI);
    }

    /**
     * Creates the GPU instance buffer for TLAS building.
     * Combines persistent instances with ephemeral instances.
     * 
     * @return Pair of the buffer reference and instance count
     */
    public Pair<VRef<VBuffer>, Integer> getInstanceBuffer() {
        int totalCount = this.count + ephemeralInstances.size();

        long size = VkAccelerationStructureInstanceKHR.SIZEOF * (long) totalCount;
        if (size == 0) {
            size = VkAccelerationStructureInstanceKHR.SIZEOF;
        }

        VRef<VBuffer> data = getUploadBuffer(size);

        long persistentSize = VkAccelerationStructureInstanceKHR.SIZEOF * (long) this.count;
        long ptr = data.get().map();
        if (persistentSize > 0) {
            MemoryUtil.memCopy(this.instances.address(0), ptr, persistentSize);
        }

        ptr = ptr + persistentSize;
        for (var asi : ephemeralInstances) {
            MemoryUtil.memCopy(asi.address(), ptr, VkAccelerationStructureInstanceKHR.SIZEOF);
            ptr += VkAccelerationStructureInstanceKHR.SIZEOF;
            asi.free();
        }
        ephemeralInstances.clear();

        data.get().unmap();
        data.get().flush();

        return new Pair<>(data, totalCount);
    }

    private VRef<VBuffer> getUploadBuffer(long size) {
        int slot = instanceUploadCursor;
        instanceUploadCursor = (instanceUploadCursor + 1) % INSTANCE_UPLOAD_SLOTS;

        if (instanceUploadBuffers[slot] == null || instanceUploadCapacities[slot] < size) {
            if (instanceUploadBuffers[slot] != null) {
                instanceUploadBuffers[slot].close();
            }

            long capacity = roundUpPow2(Math.max(size, VkAccelerationStructureInstanceKHR.SIZEOF));
            instanceUploadBuffers[slot] = context.memory.createBuffer(capacity,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            instanceUploadBuffers[slot].get().setDebugUtilsObjectName("TLAS Instance Buffer " + slot);
            instanceUploadCapacities[slot] = capacity;
        }

        return instanceUploadBuffers[slot].addRef();
    }

    public void destroy() {
        for (var asi : ephemeralInstances) {
            asi.free();
        }
        ephemeralInstances.clear();

        if (instances != null) {
            instances.free();
            instances = null;
        }
        freeIds.clear();
        maxInstances = 0;
        count = 0;
        loc2id = new int[0];
        id2loc = new int[0];

        for (int i = 0; i < instanceUploadBuffers.length; i++) {
            if (instanceUploadBuffers[i] != null) {
                instanceUploadBuffers[i].close();
                instanceUploadBuffers[i] = null;
            }
            instanceUploadCapacities[i] = 0L;
        }
        instanceUploadCursor = 0;
    }
}

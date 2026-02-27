package me.cortex.vulkanite.acceleration.tlas;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VBuffer;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minecraft.util.Pair;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;

import java.util.ArrayList;
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

    // We assume 3 frames in flight as that's typical for vulkan renderers including this one (VInitializer uses 3)
    private static final int FRAMES_IN_FLIGHT = 3;
    private final List<VRef<VBuffer>> reusableBuffers;
    private int bufferIndex = 0;

    public TLASInstanceBuffer(VContext context) {
        this.context = context;
        resize(32768);

        reusableBuffers = new ArrayList<>(FRAMES_IN_FLIGHT);
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            reusableBuffers.add(null);
        }
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

    /**
     * Resizes internal buffers if needed.
     * 
     * @param newSize Required minimum size
     */
    public void resize(int newSize) {
        // Early return if already large enough
        if (newSize <= maxInstances) {
            return;
        }

        newSize = roundUpPow2(newSize);

        // Resize the instance buffer
        VkAccelerationStructureInstanceKHR.Buffer newBuffer = VkAccelerationStructureInstanceKHR.calloc(newSize);
        if (instances != null) {
            newBuffer.put(instances);
            newBuffer.rewind();
            instances.free();
        }
        instances = newBuffer;

        // Add new ids to the free list
        for (int i = maxInstances; i < newSize; i++) {
            freeIds.enqueue(i);
        }

        // Resize the id mapping arrays
        int[] newLoc2Id = new int[newSize];
        int[] newId2Loc = new int[newSize];
        System.arraycopy(loc2id, 0, newLoc2Id, 0, count);
        System.arraycopy(id2loc, 0, newId2Loc, 0, count);
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
        count++;
        resize(count);

        int id = freeIds.dequeueInt();

        // Append to the end (dense buffer)
        loc2id[count - 1] = id;
        id2loc[id] = count - 1;

        // Copy the instance to the buffer
        MemoryUtil.memCopy(instance.address(), instances.address(count - 1),
                VkAccelerationStructureInstanceKHR.SIZEOF);

        return id;
    }

    /**
     * Frees an instance by ID.
     * 
     * @param id The instance ID to free
     */
    protected void free(int id) {
        if (id < 0) {
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

        // Reuse buffer logic
        VRef<VBuffer> data = reusableBuffers.get(bufferIndex);
        if (data == null || data.get().size() < size) {
            if (data != null) {
                data.close();
            }
            data = context.memory.createBuffer(size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                    VK_MEMORY_HEAP_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            data.get().setDebugUtilsObjectName("TLAS Instance Buffer");
            reusableBuffers.set(bufferIndex, data);
        }

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

        // Increment buffer index for next frame
        bufferIndex = (bufferIndex + 1) % FRAMES_IN_FLIGHT;

        return new Pair<>(data.addRef(), totalCount);
    }

    /**
     * Clean up resources.
     */
    public void free() {
        if (instances != null) {
            instances.free();
            instances = null;
        }
        for (int i = 0; i < reusableBuffers.size(); i++) {
            if (reusableBuffers.get(i) != null) {
                reusableBuffers.get(i).close();
                reusableBuffers.set(i, null);
            }
        }
        reusableBuffers.clear();
    }
}

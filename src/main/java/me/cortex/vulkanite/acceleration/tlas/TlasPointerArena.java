package me.cortex.vulkanite.acceleration.tlas;

import java.util.BitSet;

/**
 * BitSet-based arena allocator for managing TLAS instance indices.
 * Supports allocation of contiguous index ranges and efficient free-list
 * management.
 */
public final class TlasPointerArena {
    private final BitSet vacant;
    public int maxIndex = 0;
    private int capacity;

    public TlasPointerArena(int size) {
        size *= 3;
        capacity = size;
        vacant = new BitSet(size);
        vacant.set(0, size);
    }

    /**
     * Allocates a contiguous range of indices.
     * 
     * @param count Number of contiguous indices needed
     * @return Starting index of the allocated range
     * @throws IllegalStateException if no contiguous range is available
     */
    public int allocate(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }

        int pos = vacant.nextSetBit(0);
        while (pos != -1) {
            // Check if we have 'count' contiguous set bits starting at pos
            int endPos = pos + count;
            int nextClear = vacant.nextClearBit(pos);
            if (nextClear >= endPos) {
                // Found a contiguous range
                break;
            }
            // Jump to the next set bit after the clear bit
            pos = vacant.nextSetBit(nextClear + 1);
        }

        if (pos == -1) {
            pos = capacity;
            int newCapacity = capacity;
            while (newCapacity - pos < count) {
                newCapacity *= 2;
            }
            vacant.set(capacity, newCapacity);
            capacity = newCapacity;
        }

        vacant.clear(pos, pos + count);
        maxIndex = Math.max(maxIndex, pos + count);
        return pos;
    }

    /**
     * Frees a previously allocated range of indices.
     * 
     * @param pos   Starting index of the range
     * @param count Number of indices to free
     */
    public void free(int pos, int count) {
        vacant.set(pos, pos + count);
        maxIndex = vacant.previousClearBit(maxIndex) + 1;
    }
}

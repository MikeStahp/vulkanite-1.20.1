package me.cortex.vulkanite.acceleration.tlas;

import me.cortex.vulkanite.lib.base.VObject;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;

/**
 * Holds a TLAS section's acceleration structure and geometry references.
 * 
 * This VObject manages the lifecycle of a section's resources, ensuring
 * proper cleanup when the section is no longer needed (either freed explicitly
 * or when the GPU is done using it).
 */
public final class TLASSectionHolder extends VObject {
    public final int id;
    private final TLASSectionManager manager;
    public final int geometryIndex;
    public final int numGeometries;
    private final VRef<VAccelerationStructure> structure;

    private TLASSectionHolder(int id, int geometryIndex, int numGeometries,
            VRef<VAccelerationStructure> structure, TLASSectionManager manager) {
        this.id = id;
        this.geometryIndex = geometryIndex;
        this.numGeometries = numGeometries;
        this.structure = structure;
        this.manager = manager;
    }

    /**
     * Creates a new holder with a reference.
     */
    public static VRef<TLASSectionHolder> create(int id, int geometryIndex, int numGeometries,
            VRef<VAccelerationStructure> structure, TLASSectionManager manager) {
        return new VRef<>(new TLASSectionHolder(id, geometryIndex, numGeometries, structure, manager));
    }

    @Override
    protected void free() {
        structure.close();
        // Remove from the geometry buffer & descriptor set
        manager.arenaFree(geometryIndex, numGeometries);
    }
}

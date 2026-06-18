package me.cortex.vulkanite.acceleration.tlas;

import me.cortex.vulkanite.lib.base.VObject;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSet;
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
    private VRef<VDescriptorSet> geometryDescriptorSet;

    private TLASSectionHolder(int id, int geometryIndex, int numGeometries,
            VRef<VAccelerationStructure> structure, VRef<VDescriptorSet> geometryDescriptorSet,
            TLASSectionManager manager) {
        this.id = id;
        this.geometryIndex = geometryIndex;
        this.numGeometries = numGeometries;
        this.structure = structure;
        this.geometryDescriptorSet = geometryDescriptorSet;
        this.manager = manager;
    }

    /**
     * Creates a new holder with a reference.
     */
    public static VRef<TLASSectionHolder> create(int id, int geometryIndex, int numGeometries,
            VRef<VAccelerationStructure> structure, VRef<VDescriptorSet> geometryDescriptorSet,
            TLASSectionManager manager) {
        return new VRef<>(new TLASSectionHolder(id, geometryIndex, numGeometries, structure,
                geometryDescriptorSet, manager));
    }

    public void attachGeometryDescriptorSet(VRef<VDescriptorSet> descriptorSet) {
        if (geometryDescriptorSet != null) {
            geometryDescriptorSet.close();
        }
        geometryDescriptorSet = descriptorSet;
    }

    @Override
    protected void free() {
        structure.close();
        manager.arenaFree(geometryIndex, numGeometries, geometryDescriptorSet);
        if (geometryDescriptorSet != null) {
            geometryDescriptorSet.close();
            geometryDescriptorSet = null;
        }
    }
}

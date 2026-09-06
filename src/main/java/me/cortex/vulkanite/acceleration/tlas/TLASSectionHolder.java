package me.cortex.vulkanite.acceleration.tlas;

import me.cortex.vulkanite.acceleration.blas.ProceduralBLAS;
import me.cortex.vulkanite.acceleration.blas.ShadowTriangleBLAS;
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
    private final int descriptorCount;
    private final int proceduralGeometryIndex;
    private final int shadowGeometryIndex;
    private final int shadowGeometryCount;
    private final boolean filteredShadowGeometry;
    private final VRef<VAccelerationStructure> structure;
    private final VRef<ShadowTriangleBLAS> shadowTriangleBlas;
    private final VRef<ProceduralBLAS> proceduralBlas;
    private VRef<VDescriptorSet> geometryDescriptorSet;

    private TLASSectionHolder(int id, int geometryIndex, int numGeometries,
            int descriptorCount, int proceduralGeometryIndex,
            int shadowGeometryIndex, int shadowGeometryCount, boolean filteredShadowGeometry,
            VRef<VAccelerationStructure> structure, VRef<ShadowTriangleBLAS> shadowTriangleBlas,
            VRef<ProceduralBLAS> proceduralBlas,
            VRef<VDescriptorSet> geometryDescriptorSet,
            TLASSectionManager manager) {
        this.id = id;
        this.geometryIndex = geometryIndex;
        this.numGeometries = numGeometries;
        this.descriptorCount = descriptorCount;
        this.proceduralGeometryIndex = proceduralGeometryIndex;
        this.shadowGeometryIndex = shadowGeometryIndex;
        this.shadowGeometryCount = shadowGeometryCount;
        this.filteredShadowGeometry = filteredShadowGeometry;
        this.structure = structure;
        this.shadowTriangleBlas = shadowTriangleBlas;
        this.proceduralBlas = proceduralBlas;
        this.geometryDescriptorSet = geometryDescriptorSet;
        this.manager = manager;
    }

    /**
     * Creates a new holder with a reference.
     */
    public static VRef<TLASSectionHolder> create(int id, int geometryIndex, int numGeometries,
            int descriptorCount, int proceduralGeometryIndex,
            int shadowGeometryIndex, int shadowGeometryCount, boolean filteredShadowGeometry,
            VRef<VAccelerationStructure> structure, VRef<ShadowTriangleBLAS> shadowTriangleBlas,
            VRef<ProceduralBLAS> proceduralBlas,
            VRef<VDescriptorSet> geometryDescriptorSet,
            TLASSectionManager manager) {
        if ((proceduralBlas == null) != (proceduralGeometryIndex < 0)) {
            throw new IllegalArgumentException("Procedural payload index must match procedural BLAS presence");
        }
        if ((shadowTriangleBlas == null) != (shadowGeometryIndex < 0 || shadowGeometryCount <= 0)) {
            throw new IllegalArgumentException("Shadow geometry range must match shadow BLAS presence");
        }
        if (shadowTriangleBlas != null && shadowGeometryCount != shadowTriangleBlas.get().geometryCount()) {
            throw new IllegalArgumentException("Shadow geometry descriptor count does not match its BLAS");
        }
        if (shadowTriangleBlas != null && !filteredShadowGeometry) {
            throw new IllegalArgumentException("Shadow triangle BLAS requires filtered ownership");
        }
        if (descriptorCount < numGeometries || descriptorCount <= 0) {
            throw new IllegalArgumentException("Descriptor count cannot omit triangle geometry");
        }
        return new VRef<>(new TLASSectionHolder(id, geometryIndex, numGeometries,
                descriptorCount, proceduralGeometryIndex,
                shadowGeometryIndex, shadowGeometryCount, filteredShadowGeometry,
                structure, shadowTriangleBlas, proceduralBlas, geometryDescriptorSet, manager));
    }

    public boolean hasProceduralBlas() {
        return proceduralBlas != null;
    }

    public int proceduralGeometryIndex() {
        if (proceduralBlas == null) {
            throw new IllegalStateException("Section does not have procedural geometry");
        }
        return proceduralGeometryIndex;
    }

    public VRef<ProceduralBLAS> proceduralBlas() {
        return proceduralBlas == null ? null : proceduralBlas.addRef();
    }

    public boolean hasFilteredShadowGeometry() {
        return filteredShadowGeometry;
    }

    public boolean hasShadowTriangleBlas() {
        return shadowTriangleBlas != null;
    }

    public int shadowGeometryIndex() {
        if (shadowTriangleBlas == null) {
            throw new IllegalStateException("Section has no filtered shadow triangles");
        }
        return shadowGeometryIndex;
    }

    public int shadowGeometryCount() {
        return shadowGeometryCount;
    }

    public VRef<ShadowTriangleBLAS> shadowTriangleBlas() {
        return shadowTriangleBlas == null ? null : shadowTriangleBlas.addRef();
    }

    public VRef<VAccelerationStructure> triangleBlas() {
        return structure.addRef();
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
        if (shadowTriangleBlas != null) {
            shadowTriangleBlas.close();
        }
        if (proceduralBlas != null) {
            proceduralBlas.close();
        }
        manager.arenaFree(geometryIndex, descriptorCount, geometryDescriptorSet);
        if (geometryDescriptorSet != null) {
            geometryDescriptorSet.close();
            geometryDescriptorSet = null;
        }
    }
}

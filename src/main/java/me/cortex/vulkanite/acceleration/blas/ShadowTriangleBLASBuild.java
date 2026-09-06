package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;

/** Pending section shadow BLAS plus the geometry buffer it was built from. */
public final class ShadowTriangleBLASBuild {
    private VRef<VAccelerationStructure> structure;
    private ShadowTriangleBLASInput input;
    private final long accelerationStructureBytes;

    public ShadowTriangleBLASBuild(
            VRef<VAccelerationStructure> structure,
            ShadowTriangleBLASInput input,
            long accelerationStructureBytes) {
        this.structure = structure;
        this.input = input;
        this.accelerationStructureBytes = accelerationStructureBytes;
    }

    public VRef<ShadowTriangleBLAS> complete() {
        if (structure == null || input == null) {
            throw new IllegalStateException("Shadow triangle BLAS build was already consumed");
        }
        VRef<VBuffer> retainedGeometry = null;
        try {
            retainedGeometry = input.geometryBuffer().addRef();
            VRef<ShadowTriangleBLAS> result = ShadowTriangleBLAS.create(
                    structure,
                    retainedGeometry,
                    input.geometries(),
                    input.bufferOffsets(),
                    input.sourceQuadCount(),
                    input.shadowQuadCount(),
                    accelerationStructureBytes,
                    input.debugName());
            // The resident resource retained the compact quad buffer. Release
            // worker-local input ownership after the build submission completes.
            input.close();
            structure = null;
            input = null;
            return result;
        } catch (RuntimeException | Error failure) {
            if (retainedGeometry != null) {
                retainedGeometry.close();
            }
            discard();
            throw failure;
        }
    }

    public void discard() {
        if (structure != null) {
            structure.close();
            structure = null;
        }
        if (input != null) {
            input.close();
            input = null;
        }
    }
}

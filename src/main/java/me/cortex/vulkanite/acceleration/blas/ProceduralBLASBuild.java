package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;

/** Worker-local ownership for a procedural BLAS between encode and publish. */
final class ProceduralBLASBuild {
    private final VRef<VAccelerationStructure> structure;
    private final ProceduralBLASInput input;
    private final long accelerationStructureBytes;
    private boolean completed;

    ProceduralBLASBuild(
            VRef<VAccelerationStructure> structure,
            ProceduralBLASInput input,
            long accelerationStructureBytes) {
        this.structure = structure;
        this.input = input;
        this.accelerationStructureBytes = accelerationStructureBytes;
    }

    VRef<ProceduralBLAS> complete() {
        if (completed) {
            throw new IllegalStateException("Procedural BLAS build was already completed");
        }
        VRef<VBuffer> retainedPayload = null;
        try {
            retainedPayload = input.payloadBuffer().addRef();
            VRef<ProceduralBLAS> resource = ProceduralBLAS.create(
                    structure,
                    retainedPayload,
                    input.geometry().brickCount(),
                    input.geometry().brickSize(),
                    input.geometry().hasMaterialPayload(),
                    input.geometry().hasCompleteMaterialPayload(),
                    input.geometry().materialPayload().cellMaterialCount(),
                    input.geometry().materialPayload().faceMaterialCount(),
                    input.geometry().materialPayload().layerCount(),
                    accelerationStructureBytes,
                    input.debugName());
            // The AABB buffer is no longer needed after the build completes.
            input.close();
            completed = true;
            return resource;
        } catch (RuntimeException | Error failure) {
            if (retainedPayload != null) {
                retainedPayload.close();
            }
            discard();
            throw failure;
        }
    }

    void discard() {
        if (completed) {
            return;
        }
        completed = true;
        structure.close();
        input.close();
    }
}

package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;

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
        completed = true;
        var resource = ProceduralBLAS.create(
                structure,
                input.payloadBuffer().addRef(),
                input.geometry().brickCount(),
                accelerationStructureBytes,
                input.debugName());
        // The AABB buffer is no longer needed after vkCmdBuildAccelerationStructuresKHR
        // completes. The resource retained its own payload reference above.
        input.close();
        return resource;
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

package me.cortex.vulkanite.acceleration.blas;

/**
 * Describes how a section update affects its debug-only procedural BLAS.
 */
public enum ProceduralBLASDisposition {
    /** A newly built procedural BLAS replaces the section's previous one. */
    REPLACE,
    /** The opaque occupancy is unchanged, so the previous resource is retained. */
    RETAIN,
    /** The section has no procedural occupancy and any previous resource is removed. */
    CLEAR
}

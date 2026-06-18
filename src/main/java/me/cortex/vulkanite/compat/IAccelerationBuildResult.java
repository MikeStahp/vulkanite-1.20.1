package me.cortex.vulkanite.compat;

public interface IAccelerationBuildResult {
    void setAccelerationGeometry(SodiumGeometryBatch geometry);
    SodiumGeometryBatch getAccelerationGeometry();
}

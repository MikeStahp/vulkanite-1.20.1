package me.cortex.vulkanite.compat;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;

import java.util.List;
import java.util.Map;

public interface IAccelerationBuildResult {
    void setAccelerationGeometryData(Map<TerrainRenderPass, GeometryData> map);
    Map<TerrainRenderPass, GeometryData> getAccelerationGeometryData();
    ChunkVertexType getVertexFormat();
    void setVertexFormat(ChunkVertexType format);
    
    // New methods for native buffer tracking
    void setNativeBuffers(List<NativeBuffer> buffers);
    List<NativeBuffer> getNativeBuffers();
}

package me.cortex.vulkanite.compat;

import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

import java.util.ArrayList;
import java.util.List;

public class SodiumResultAdapter {
    public static void compute(ChunkBuildOutput buildResult, ChunkVertexType vertexFormat) {
        ((IAccelerationBuildResult) buildResult).setAccelerationGeometry(capture(buildResult, vertexFormat));
    }

    public static SodiumGeometryBatch capture(ChunkBuildOutput buildResult, ChunkVertexType vertexFormat) {
        if (vertexFormat == null) {
            throw new IllegalStateException("Missing Sodium chunk vertex format");
        }

        int stride = vertexFormat.getVertexFormat().getStride();
        if (stride <= 0) {
            throw new IllegalStateException("Invalid Sodium vertex stride: " + stride);
        }

        List<SodiumGeometry> geometries = new ArrayList<>();
        long totalSizeBytes = 0;

        for (var entry : buildResult.meshes.entrySet()) {
            var mesh = entry.getValue();
            if (mesh == null) {
                continue;
            }

            var vertexData = mesh.getVertexData();
            if (vertexData == null || vertexData.getLength() <= 0) {
                continue;
            }

            int sizeBytes = vertexData.getLength();
            if (sizeBytes % stride != 0) {
                throw new IllegalStateException("Sodium mesh length " + sizeBytes
                        + " is not a multiple of vertex stride " + stride);
            }

            int vertices = sizeBytes / stride;
            if (vertices % 4 != 0) {
                throw new IllegalStateException("Sodium mesh vertex count " + vertices
                        + " is not quad aligned");
            }

            int quadCount = vertices >> 2;
            geometries.add(new SodiumGeometry(entry.getKey(), vertexData, quadCount, sizeBytes));
            totalSizeBytes += sizeBytes;
        }

        if (geometries.isEmpty()) {
            return null;
        }

        return new SodiumGeometryBatch(geometries, totalSizeBytes);
    }
}

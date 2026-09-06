package me.cortex.vulkanite.acceleration.voxel;

import me.cortex.vulkanite.compat.ShadowGeometryFilter;
import me.cortex.vulkanite.compat.SectionLight;
import me.cortex.vulkanite.compat.SectionLightTable;
import me.cortex.vulkanite.compat.SodiumGeometry;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts the byte-exact full-cube faces captured by the shadow splitter into
 * the version-2 procedural material tables.
 *
 * <p>The extractor deliberately consumes the same 40-byte Iris/Sodium vertex
 * records that remain authoritative for triangle reflections. It does not try
 * to resolve block models a second time: atlas UVs, baked tint, light UVs,
 * tangent handedness, shader block id, and render type are copied from the
 * captured quad. Missing or ambiguous faces remain absent from the payload and
 * therefore keep the triangle path authoritative for production reflection.</p>
 */
public final class ProceduralMaterialExtractor {
    private static final int VERTEX_BYTES = ShadowGeometryFilter.VERTEX_BYTES;
    private static final int QUAD_BYTES = ShadowGeometryFilter.QUAD_BYTES;
    private static final int POSITION_X_OFFSET = 0;
    private static final int POSITION_Y_OFFSET = 2;
    private static final int POSITION_Z_OFFSET = 4;
    private static final int COLOR_OFFSET = 8;
    private static final int BLOCK_U_OFFSET = 12;
    private static final int BLOCK_V_OFFSET = 14;
    private static final int LIGHT_U_OFFSET = 16;
    private static final int LIGHT_V_OFFSET = 18;
    private static final int TANGENT_OFFSET = 24;
    private static final int NORMAL_X_OFFSET = 28;
    private static final int NORMAL_Y_OFFSET = 29;
    private static final int NORMAL_Z_OFFSET = 30;
    private static final int BLOCK_ID_OFFSET = 32;
    private static final int RENDER_TYPE_OFFSET = 34;
    private static final float POSITION_SCALE = 32.0f / 65536.0f;
    private static final float POSITION_BIAS = -8.0f;
    private static final float UV_SCALE = 1.0f / 65536.0f;

    /** Layer came from an opaque terrain pass. */
    public static final int LAYER_FLAG_OPAQUE = 1;
    /** More than one captured quad contributes to the same cube face. */
    public static final int FACE_FLAG_LAYERED = 1;

    private ProceduralMaterialExtractor() {
    }

    public static ProceduralMaterialPayload extract(ShadowGeometryFilter.Result split) {
        return extract(split, null);
    }

    public static ProceduralMaterialPayload extract(
            ShadowGeometryFilter.Result split,
            SectionLightTable sectionData) {
        Objects.requireNonNull(split, "split");
        if (split.isClosed()) {
            throw new IllegalStateException("Cannot extract material data from a closed split");
        }

        float[] emissionByCell = intrinsicEmissions(sectionData);
        Map<Integer, MutableCell> cells = new LinkedHashMap<>();
        for (ShadowGeometryFilter.ProceduralFaceCapture capture : split.proceduralFaces()) {
            ProceduralMaterialPayload.Face face = mapFace(capture.face());
            if (face == null) {
                continue;
            }
            SodiumGeometry range = split.proceduralFaceGeometry().geometries()
                    .get(capture.capturedRangeIndex());
            ByteBuffer vertices = range.vertexData().getDirectBuffer().duplicate()
                    .order(ByteOrder.nativeOrder());
            int quadOffset = Math.multiplyExact(capture.capturedQuadIndex(), QUAD_BYTES);
            if (quadOffset < 0 || quadOffset + QUAD_BYTES > range.sizeBytes()) {
                throw new IllegalStateException("Captured procedural face is outside its geometry range");
            }

            int cellIndex = cellIndex(capture.owner());
            MutableCell cell = cells.computeIfAbsent(cellIndex,
                    ignored -> new MutableCell(
                            capture.blockId(),
                            capture.renderType(),
                            intrinsicEmission(emissionByCell, capture.owner())));
            if (cell.shaderBlockId != capture.blockId()
                    || cell.materialId != capture.renderType()) {
                // Conflicting raw material identities are not representable as
                // one procedural cube. Leave this cell without reflection data.
                cell.conflicted = true;
                continue;
            }
            if (!quadMetadataConsistent(vertices, quadOffset, capture, face)) {
                cell.conflicted = true;
                continue;
            }

            int[] cornerOrder = faceCornerOrder(vertices, quadOffset, face);
            if (cornerOrder == null) {
                cell.face(face).conflicted = true;
                continue;
            }

            ProceduralMaterialPayload.FaceLayer layer = new ProceduralMaterialPayload.FaceLayer(
                    uv(vertices, quadOffset, cornerOrder[0]),
                    uv(vertices, quadOffset, cornerOrder[1]),
                    uv(vertices, quadOffset, cornerOrder[2]),
                    uv(vertices, quadOffset, cornerOrder[3]),
                    vertices.getInt(quadOffset + COLOR_OFFSET),
                    isOpaquePass(capture) ? LAYER_FLAG_OPAQUE : 0);
            ProceduralMaterialPayload.LightCorners light = new ProceduralMaterialPayload.LightCorners(
                    lightUv(vertices, quadOffset, cornerOrder[0]),
                    lightUv(vertices, quadOffset, cornerOrder[1]),
                    lightUv(vertices, quadOffset, cornerOrder[2]),
                    lightUv(vertices, quadOffset, cornerOrder[3]));
            int tangent = vertices.getInt(quadOffset + TANGENT_OFFSET);
            cell.face(face).add(tangent, light, layer);
        }

        ProceduralMaterialPayload.Builder payload = ProceduralMaterialPayload.builder();
        for (Map.Entry<Integer, MutableCell> entry : cells.entrySet()) {
            MutableCell cell = entry.getValue();
            if (cell.conflicted) {
                continue;
            }
            Map<ProceduralMaterialPayload.Face, ProceduralMaterialPayload.FaceMaterial> faces =
                    new EnumMap<>(ProceduralMaterialPayload.Face.class);
            for (Map.Entry<ProceduralMaterialPayload.Face, MutableFace> faceEntry
                    : cell.faces.entrySet()) {
                MutableFace face = faceEntry.getValue();
                if (!face.conflicted && !face.layers.isEmpty()) {
                    faces.put(faceEntry.getKey(), face.build());
                }
            }
            if (faces.isEmpty()) {
                continue;
            }
            int packedCell = entry.getKey();
            int x = packedCell & 15;
            int z = (packedCell >>> 4) & 15;
            int y = (packedCell >>> 8) & 15;
            payload.setCell(x, y, z, ProceduralMaterialPayload.CellMaterial.of(
                     cell.shaderBlockId,
                     cell.materialId,
                     cell.intrinsicEmission,
                    0,
                    faces));
        }
        return payload.build();
    }

    private static boolean isOpaquePass(ShadowGeometryFilter.ProceduralFaceCapture capture) {
        return capture.pass() == DefaultTerrainRenderPasses.SOLID;
    }

    private static ProceduralMaterialPayload.Uv uv(
            ByteBuffer vertices, int quadOffset, int vertexIndex) {
        int offset = quadOffset + vertexIndex * VERTEX_BYTES;
        return new ProceduralMaterialPayload.Uv(
                Short.toUnsignedInt(vertices.getShort(offset + BLOCK_U_OFFSET)) * UV_SCALE,
                Short.toUnsignedInt(vertices.getShort(offset + BLOCK_V_OFFSET)) * UV_SCALE);
    }

    private static ProceduralMaterialPayload.LightUv lightUv(
            ByteBuffer vertices, int quadOffset, int vertexIndex) {
        int offset = quadOffset + vertexIndex * VERTEX_BYTES;
        return new ProceduralMaterialPayload.LightUv(
                Short.toUnsignedInt(vertices.getShort(offset + LIGHT_U_OFFSET)),
                Short.toUnsignedInt(vertices.getShort(offset + LIGHT_V_OFFSET)));
    }

    private static int[] faceCornerOrder(
            ByteBuffer vertices,
            int quadOffset,
            ProceduralMaterialPayload.Face face) {
        int uOffset = switch (face) {
            case POSITIVE_X, NEGATIVE_X -> POSITION_Z_OFFSET;
            case POSITIVE_Y, NEGATIVE_Y, POSITIVE_Z, NEGATIVE_Z -> POSITION_X_OFFSET;
        };
        int vOffset = switch (face) {
            case POSITIVE_X, NEGATIVE_X, POSITIVE_Z, NEGATIVE_Z -> POSITION_Y_OFFSET;
            case POSITIVE_Y, NEGATIVE_Y -> POSITION_Z_OFFSET;
        };
        float[] u = new float[4];
        float[] v = new float[4];
        float minU = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < 4; vertex++) {
            int vertexOffset = quadOffset + vertex * VERTEX_BYTES;
            u[vertex] = decodedPosition(vertices, vertexOffset, uOffset);
            v[vertex] = decodedPosition(vertices, vertexOffset, vOffset);
            minU = Math.min(minU, u[vertex]);
            maxU = Math.max(maxU, u[vertex]);
            minV = Math.min(minV, v[vertex]);
            maxV = Math.max(maxV, v[vertex]);
        }
        if (!(maxU - minU > 0.5f) || !(maxV - minV > 0.5f)) {
            return null;
        }

        float midU = (minU + maxU) * 0.5f;
        float midV = (minV + maxV) * 0.5f;
        int[] order = {-1, -1, -1, -1};
        for (int vertex = 0; vertex < 4; vertex++) {
            boolean highU = u[vertex] > midU;
            boolean highV = v[vertex] > midV;
            int canonical = highV ? (highU ? 2 : 3) : (highU ? 1 : 0);
            if (order[canonical] >= 0) {
                return null;
            }
            order[canonical] = vertex;
        }
        for (int vertex : order) {
            if (vertex < 0) {
                return null;
            }
        }
        return order;
    }

    private static boolean quadMetadataConsistent(
            ByteBuffer vertices,
            int quadOffset,
            ShadowGeometryFilter.ProceduralFaceCapture capture,
            ProceduralMaterialPayload.Face expectedFace) {
        int expectedColor = vertices.getInt(quadOffset + COLOR_OFFSET);
        int expectedTangent = vertices.getInt(quadOffset + TANGENT_OFFSET);
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = quadOffset + vertex * VERTEX_BYTES;
            if (vertices.getShort(offset + BLOCK_ID_OFFSET) != capture.blockId()
                    || vertices.getShort(offset + RENDER_TYPE_OFFSET) != capture.renderType()
                    || vertices.getInt(offset + COLOR_OFFSET) != expectedColor
                    || vertices.getInt(offset + TANGENT_OFFSET) != expectedTangent) {
                return false;
            }
            int normalX = vertices.get(offset + NORMAL_X_OFFSET);
            int normalY = vertices.get(offset + NORMAL_Y_OFFSET);
            int normalZ = vertices.get(offset + NORMAL_Z_OFFSET);
            if (faceFromNormal(normalX, normalY, normalZ) != expectedFace) {
                return false;
            }
        }
        return true;
    }

    private static float[] intrinsicEmissions(SectionLightTable sectionData) {
        if (sectionData == null) {
            return null;
        }
        float[] result = new float[16 * 16 * 16];
        for (SectionLight light : sectionData.lights()) {
            int cellIndex = cellIndex(light.localX(), light.localY(), light.localZ());
            result[cellIndex] = Math.max(result[cellIndex], light.emission());
        }
        return result;
    }

    private static float intrinsicEmission(
            float[] emissionByCell,
            ShadowGeometryFilter.OwnerCell owner) {
        return emissionByCell == null ? 0.0f : emissionByCell[cellIndex(owner)];
    }

    private static ProceduralMaterialPayload.Face faceFromNormal(int x, int y, int z) {
        int absX = Math.abs(x);
        int absY = Math.abs(y);
        int absZ = Math.abs(z);
        if (absX > absY && absX > absZ) {
            return x > 0 ? ProceduralMaterialPayload.Face.POSITIVE_X
                    : ProceduralMaterialPayload.Face.NEGATIVE_X;
        }
        if (absY > absX && absY > absZ) {
            return y > 0 ? ProceduralMaterialPayload.Face.POSITIVE_Y
                    : ProceduralMaterialPayload.Face.NEGATIVE_Y;
        }
        if (absZ > absX && absZ > absY) {
            return z > 0 ? ProceduralMaterialPayload.Face.POSITIVE_Z
                    : ProceduralMaterialPayload.Face.NEGATIVE_Z;
        }
        return null;
    }

    private static float decodedPosition(ByteBuffer vertices, int vertexOffset, int componentOffset) {
        return Short.toUnsignedInt(vertices.getShort(vertexOffset + componentOffset))
                * POSITION_SCALE + POSITION_BIAS;
    }

    private static int cellIndex(ShadowGeometryFilter.OwnerCell owner) {
        return cellIndex(owner.x(), owner.y(), owner.z());
    }

    private static int cellIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static ProceduralMaterialPayload.Face mapFace(ShadowGeometryFilter.Face face) {
        return switch (face) {
            case POSITIVE_X -> ProceduralMaterialPayload.Face.POSITIVE_X;
            case NEGATIVE_X -> ProceduralMaterialPayload.Face.NEGATIVE_X;
            case POSITIVE_Y -> ProceduralMaterialPayload.Face.POSITIVE_Y;
            case NEGATIVE_Y -> ProceduralMaterialPayload.Face.NEGATIVE_Y;
            case POSITIVE_Z -> ProceduralMaterialPayload.Face.POSITIVE_Z;
            case NEGATIVE_Z -> ProceduralMaterialPayload.Face.NEGATIVE_Z;
            case UNKNOWN -> null;
        };
    }

    private static final class MutableCell {
        private final int shaderBlockId;
        private final int materialId;
        private final float intrinsicEmission;
        private final Map<ProceduralMaterialPayload.Face, MutableFace> faces =
                new EnumMap<>(ProceduralMaterialPayload.Face.class);
        private boolean conflicted;

        private MutableCell(short shaderBlockId, short materialId, float intrinsicEmission) {
            this.shaderBlockId = shaderBlockId;
            this.materialId = materialId;
            this.intrinsicEmission = intrinsicEmission;
        }

        private MutableFace face(ProceduralMaterialPayload.Face face) {
            return faces.computeIfAbsent(face, ignored -> new MutableFace());
        }
    }

    private static final class MutableFace {
        private int tangent;
        private ProceduralMaterialPayload.LightCorners light;
        private final List<ProceduralMaterialPayload.FaceLayer> layers = new ArrayList<>();
        private boolean conflicted;

        private void add(
                int newTangent,
                ProceduralMaterialPayload.LightCorners newLight,
                ProceduralMaterialPayload.FaceLayer layer) {
            if (layers.isEmpty()) {
                tangent = newTangent;
                light = newLight;
            } else if (tangent != newTangent || !light.equals(newLight)) {
                conflicted = true;
                return;
            }
            if (!layers.contains(layer)) {
                layers.add(layer);
            }
        }

        private ProceduralMaterialPayload.FaceMaterial build() {
            int flags = layers.size() > 1 ? FACE_FLAG_LAYERED : 0;
            return new ProceduralMaterialPayload.FaceMaterial(tangent, flags, light, layers);
        }
    }
}

package me.cortex.vulkanite.acceleration.voxel;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable per-cell material metadata for procedural full-cube terrain.
 *
 * <p>The payload is deliberately independent from Vulkan resource ownership.
 * Chunk extraction may populate it from the exact Sodium/Iris quad metadata or
 * from a conservative model resolver, while {@link VoxelBrickGeometry} owns
 * only the byte-level GPU contract. Missing cells or faces are valid and mean
 * that reflection geometry must keep using the triangle fallback.</p>
 *
 * <p>Cells are indexed in the same X-fastest order as procedural occupancy:
 * {@code ((y * 16 + z) * 16) + x}. A cell references a deduplicated material;
 * each material references up to six deduplicated face records, and a face may
 * contain multiple texture layers (for example a base texture plus a tinted
 * overlay). This keeps the fixed per-section cell table small while preserving
 * face texture selection, UV rotation, tint, and normal-map orientation.</p>
 *
 * <p>The packed tables use native byte order. Cell references are 4,096
 * unsigned 16-bit indices ({@code 0xffff} means triangle fallback); shaders
 * without 16-bit storage support may read pairs through the surrounding
 * 32-bit-word buffer. Cell-material records contain four scalar words, six
 * face indices in {@link Face} order, and two reserved words. Face-material
 * records contain layer offset, layer count, packed tangent, flags, and four
 * packed unsigned-16 light-coordinate pairs. Face-layer records contain four
 * UV pairs followed by packed tint, flags, and two reserved words.</p>
 */
public final class ProceduralMaterialPayload {
    public static final int SECTION_SIZE = 16;
    public static final int CELL_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    public static final int FACE_COUNT = 6;

    /** Unsigned-16 sentinel used by the packed cell-to-material table. */
    public static final int INVALID_CELL_MATERIAL = 0xffff;
    /** Unsigned-32 sentinel used by material-to-face references. */
    public static final int INVALID_FACE_MATERIAL = -1;

    public static final int CELL_REFERENCE_BYTES = CELL_COUNT * Short.BYTES;
    public static final int CELL_MATERIAL_RECORD_BYTES = 48;
    public static final int FACE_MATERIAL_RECORD_BYTES = 32;
    public static final int FACE_LAYER_RECORD_BYTES = 48;

    private static final ProceduralMaterialPayload EMPTY = new Builder().build();

    private final int[] cellMaterialIndices;
    private final List<CellMaterial> cellMaterials;
    private final List<FaceMaterial> faceMaterials;
    private final int[][] cellFaceMaterialIndices;
    private final int[] faceLayerOffsets;
    private final int layerCount;

    private ProceduralMaterialPayload(int[] cellMaterialIndices, List<CellMaterial> cellMaterials) {
        this.cellMaterialIndices = cellMaterialIndices.clone();
        this.cellMaterials = List.copyOf(cellMaterials);

        Map<FaceMaterial, Integer> faceIndices = new LinkedHashMap<>();
        this.cellFaceMaterialIndices = new int[cellMaterials.size()][FACE_COUNT];
        for (int materialIndex = 0; materialIndex < cellMaterials.size(); materialIndex++) {
            CellMaterial material = cellMaterials.get(materialIndex);
            int[] indices = this.cellFaceMaterialIndices[materialIndex];
            Arrays.fill(indices, INVALID_FACE_MATERIAL);
            for (Face face : Face.values()) {
                FaceMaterial faceMaterial = material.face(face);
                if (faceMaterial == null) {
                    continue;
                }
                int faceIndex = faceIndices.computeIfAbsent(faceMaterial, ignored -> faceIndices.size());
                indices[face.index()] = faceIndex;
            }
        }
        this.faceMaterials = List.copyOf(faceIndices.keySet());

        this.faceLayerOffsets = new int[faceMaterials.size()];
        int nextLayer = 0;
        for (int faceIndex = 0; faceIndex < faceMaterials.size(); faceIndex++) {
            faceLayerOffsets[faceIndex] = nextLayer;
            nextLayer = Math.addExact(nextLayer, faceMaterials.get(faceIndex).layers().size());
        }
        this.layerCount = nextLayer;
    }

    public static ProceduralMaterialPayload empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return cellMaterials.isEmpty();
    }

    public boolean hasCell(int localX, int localY, int localZ) {
        return cellMaterialIndex(localX, localY, localZ) != INVALID_CELL_MATERIAL;
    }

    public int cellMaterialIndex(int localX, int localY, int localZ) {
        return cellMaterialIndices[cellIndex(localX, localY, localZ)];
    }

    public CellMaterial cellMaterial(int localX, int localY, int localZ) {
        int index = cellMaterialIndex(localX, localY, localZ);
        return index == INVALID_CELL_MATERIAL ? null : cellMaterials.get(index);
    }

    public int cellMaterialCount() {
        return cellMaterials.size();
    }

    public int faceMaterialCount() {
        return faceMaterials.size();
    }

    public int layerCount() {
        return layerCount;
    }

    public List<CellMaterial> cellMaterials() {
        return cellMaterials;
    }

    public List<FaceMaterial> faceMaterials() {
        return faceMaterials;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProceduralMaterialPayload payload)) {
            return false;
        }
        return Arrays.equals(cellMaterialIndices, payload.cellMaterialIndices)
                && cellMaterials.equals(payload.cellMaterials);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(cellMaterialIndices) + cellMaterials.hashCode();
    }

    /** Writes the four version-2 material tables at their precomputed offsets. */
    void writeTables(
            ByteBuffer data,
            int cellReferenceOffset,
            int cellMaterialOffset,
            int faceMaterialOffset,
            int faceLayerOffset) {
        Objects.requireNonNull(data, "data");
        if (isEmpty()) {
            throw new IllegalStateException("An empty material payload has no version-2 tables");
        }

        data.position(cellReferenceOffset);
        for (int index : cellMaterialIndices) {
            if (index < 0 || index > INVALID_CELL_MATERIAL) {
                throw new IllegalStateException("Packed cell material index exceeds unsigned-16 range: " + index);
            }
            data.putShort((short) index);
        }

        data.position(cellMaterialOffset);
        for (int materialIndex = 0; materialIndex < cellMaterials.size(); materialIndex++) {
            CellMaterial material = cellMaterials.get(materialIndex);
            data.putInt(material.shaderBlockId());
            data.putInt(material.materialId());
            data.putFloat(material.intrinsicEmission());
            data.putInt(material.flags());
            for (int faceIndex : cellFaceMaterialIndices[materialIndex]) {
                data.putInt(faceIndex);
            }
            // Keep records 16-byte aligned and leave room for future per-cell fields.
            data.putInt(0);
            data.putInt(0);
        }

        data.position(faceMaterialOffset);
        for (int faceIndex = 0; faceIndex < faceMaterials.size(); faceIndex++) {
            FaceMaterial face = faceMaterials.get(faceIndex);
            data.putInt(faceLayerOffsets[faceIndex]);
            data.putInt(face.layers().size());
            data.putInt(face.packedTangent());
            data.putInt(face.flags());
            data.putInt(face.light().uv00().packed());
            data.putInt(face.light().uv10().packed());
            data.putInt(face.light().uv11().packed());
            data.putInt(face.light().uv01().packed());
        }

        data.position(faceLayerOffset);
        for (FaceMaterial face : faceMaterials) {
            for (FaceLayer layer : face.layers()) {
                putUv(data, layer.uv00());
                putUv(data, layer.uv10());
                putUv(data, layer.uv11());
                putUv(data, layer.uv01());
                data.putInt(layer.tintRgba());
                data.putInt(layer.flags());
                // Keep records 16-byte aligned and reserve two words for blend metadata.
                data.putInt(0);
                data.putInt(0);
            }
        }
    }

    private static void putUv(ByteBuffer data, Uv uv) {
        data.putFloat(uv.u());
        data.putFloat(uv.v());
    }

    static int cellIndex(int localX, int localY, int localZ) {
        validateLocal(localX, "x");
        validateLocal(localY, "y");
        validateLocal(localZ, "z");
        return ((localY * SECTION_SIZE + localZ) * SECTION_SIZE) + localX;
    }

    private static void validateLocal(int value, String axis) {
        if (value < 0 || value >= SECTION_SIZE) {
            throw new IndexOutOfBoundsException("Local " + axis + " coordinate must be in [0, 15]: " + value);
        }
    }

    /** Matches the shader's flat-axis bucket order. */
    public enum Face {
        POSITIVE_X(0),
        NEGATIVE_X(1),
        POSITIVE_Y(2),
        NEGATIVE_Y(3),
        POSITIVE_Z(4),
        NEGATIVE_Z(5);

        private final int index;

        Face(int index) {
            this.index = index;
        }

        public int index() {
            return index;
        }
    }

    /** Atlas UV in normalized texture coordinates. */
    public record Uv(float u, float v) {
        public Uv {
            if (!Float.isFinite(u) || !Float.isFinite(v)) {
                throw new IllegalArgumentException("Face UV coordinates must be finite");
            }
        }
    }

    /** One raw terrain-vertex light coordinate, packed as unsigned-16 U/V. */
    public record LightUv(int u, int v) {
        public LightUv {
            if ((u & ~0xffff) != 0 || (v & ~0xffff) != 0) {
                throw new IllegalArgumentException("Light coordinates must fit unsigned 16-bit values");
            }
        }

        public int packed() {
            return u | (v << 16);
        }
    }

    /** Per-corner light coordinates in the same face-local order as atlas UVs. */
    public record LightCorners(LightUv uv00, LightUv uv10, LightUv uv11, LightUv uv01) {
        public static final LightCorners ZERO = new LightCorners(
                new LightUv(0, 0),
                new LightUv(0, 0),
                new LightUv(0, 0),
                new LightUv(0, 0));

        public LightCorners {
            Objects.requireNonNull(uv00, "uv00");
            Objects.requireNonNull(uv10, "uv10");
            Objects.requireNonNull(uv11, "uv11");
            Objects.requireNonNull(uv01, "uv01");
        }
    }

    /**
     * One sampled layer of a cube face.
     *
     * <p>UV corners use face-local order (0,0), (1,0), (1,1), (0,1). The
     * canonical face coordinates are (z,y) for either X face, (x,z) for
     * either Y face, and (x,y) for either Z face; stored corner UVs retain any
     * texture rotation or mirroring. The same order applies to light corners.
     * {@code tintRgba} packs R in bits 0-7, G in 8-15, B in 16-23, and A in
     * 24-31. Layer flags are intentionally renderer-defined so extraction can
     * distinguish opaque, cutout, and overlay composition without changing
     * the base packing contract.</p>
     */
    public record FaceLayer(
            Uv uv00,
            Uv uv10,
            Uv uv11,
            Uv uv01,
            int tintRgba,
            int flags) {
        public FaceLayer {
            Objects.requireNonNull(uv00, "uv00");
            Objects.requireNonNull(uv10, "uv10");
            Objects.requireNonNull(uv11, "uv11");
            Objects.requireNonNull(uv01, "uv01");
        }
    }

    /** One face's normal-map basis, light interpolation, and atlas layers. */
    public record FaceMaterial(
            int packedTangent,
            int flags,
            LightCorners light,
            List<FaceLayer> layers) {
        public FaceMaterial {
            Objects.requireNonNull(light, "light");
            Objects.requireNonNull(layers, "layers");
            layers = List.copyOf(layers);
            if (layers.isEmpty()) {
                throw new IllegalArgumentException("A face material must contain at least one atlas layer");
            }
        }

        public FaceMaterial(int packedTangent, int flags, List<FaceLayer> layers) {
            this(packedTangent, flags, LightCorners.ZERO, layers);
        }

        public FaceMaterial(int packedTangent, int flags, FaceLayer layer) {
            this(packedTangent, flags, LightCorners.ZERO, List.of(layer));
        }

        public FaceMaterial(
                int packedTangent,
                int flags,
                LightCorners light,
                FaceLayer layer) {
            this(packedTangent, flags, light, List.of(layer));
        }
    }

    /**
     * One block-state/material identity and its six optional face definitions.
     *
     * <p>{@code shaderBlockId} is the Iris shaderpack block ID (or -1 when
     * unmapped). {@code materialId} is reserved for the extractor's stable
     * block-state/material palette identity. Missing face entries explicitly
     * require the triangle reflection fallback.</p>
     */
    public record CellMaterial(
            int shaderBlockId,
            int materialId,
            float intrinsicEmission,
            int flags,
            List<FaceMaterial> faces) {
        public CellMaterial {
            if (!Float.isFinite(intrinsicEmission) || intrinsicEmission < 0.0f) {
                throw new IllegalArgumentException("Intrinsic emission must be finite and non-negative");
            }
            Objects.requireNonNull(faces, "faces");
            if (faces.size() != FACE_COUNT) {
                throw new IllegalArgumentException("A cell material must define exactly six optional faces");
            }
            // List.copyOf rejects null, while null deliberately represents a
            // face that must remain triangle-owned.
            faces = Collections.unmodifiableList(new ArrayList<>(faces));
        }

        public FaceMaterial face(Face face) {
            return faces.get(Objects.requireNonNull(face, "face").index());
        }

        public boolean hasEveryFace() {
            for (FaceMaterial face : faces) {
                if (face == null) {
                    return false;
                }
            }
            return true;
        }

        public static CellMaterial of(
                int shaderBlockId,
                int materialId,
                float intrinsicEmission,
                int flags,
                Map<Face, FaceMaterial> faces) {
            Objects.requireNonNull(faces, "faces");
            List<FaceMaterial> ordered = new ArrayList<>(Collections.nCopies(FACE_COUNT, null));
            for (Map.Entry<Face, FaceMaterial> entry : faces.entrySet()) {
                ordered.set(Objects.requireNonNull(entry.getKey(), "face").index(), entry.getValue());
            }
            return new CellMaterial(shaderBlockId, materialId, intrinsicEmission, flags, ordered);
        }
    }

    public static final class Builder {
        private final CellMaterial[] cells = new CellMaterial[CELL_COUNT];

        public Builder setCell(int localX, int localY, int localZ, CellMaterial material) {
            cells[cellIndex(localX, localY, localZ)] = Objects.requireNonNull(material, "material");
            return this;
        }

        public Builder clearCell(int localX, int localY, int localZ) {
            cells[cellIndex(localX, localY, localZ)] = null;
            return this;
        }

        public ProceduralMaterialPayload build() {
            int[] indices = new int[CELL_COUNT];
            Arrays.fill(indices, INVALID_CELL_MATERIAL);
            Map<CellMaterial, Integer> unique = new LinkedHashMap<>();
            for (int cell = 0; cell < cells.length; cell++) {
                CellMaterial material = cells[cell];
                if (material == null) {
                    continue;
                }
                int index = unique.computeIfAbsent(material, ignored -> unique.size());
                if (index >= INVALID_CELL_MATERIAL) {
                    throw new IllegalStateException("Too many procedural cell materials for unsigned-16 indices");
                }
                indices[cell] = index;
            }
            return new ProceduralMaterialPayload(indices, new ArrayList<>(unique.keySet()));
        }
    }
}

package me.cortex.vulkanite.compat;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Splits Iris's extended 40-byte terrain vertices into shadow-triangle and
 * procedural-owned face streams.
 *
 * <p>The owning cell reconstruction deliberately mirrors
 * {@code ray0.rgen::reconstructTriangleOwnerCell}: each vertex position is
 * decoded from Sodium's packed unsigned shorts, its signed {@code mid_block}
 * vector is added at 1/64-block scale, and the four reconstructed centers are
 * averaged. The CPU path adds one conservative guard: all four independently
 * reconstructed centers must identify the same section-local cell. Ambiguous
 * metadata therefore stays on the triangle path.</p>
 */
public final class ShadowGeometryFilter {
    public static final int VERTEX_BYTES = 40;
    public static final int VERTICES_PER_QUAD = 4;
    public static final int QUAD_BYTES = VERTEX_BYTES * VERTICES_PER_QUAD;
    public static final int SECTION_SIZE = 16;
    public static final int OCCUPANCY_WORDS = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE / Long.SIZE;

    private static final int POSITION_X_OFFSET = 0;
    private static final int POSITION_Y_OFFSET = 2;
    private static final int POSITION_Z_OFFSET = 4;
    private static final int NORMAL_X_OFFSET = 28;
    private static final int NORMAL_Y_OFFSET = 29;
    private static final int NORMAL_Z_OFFSET = 30;
    private static final int BLOCK_ID_OFFSET = 32;
    private static final int RENDER_TYPE_OFFSET = 34;
    private static final int MID_BLOCK_X_OFFSET = 36;
    private static final int MID_BLOCK_Y_OFFSET = 37;
    private static final int MID_BLOCK_Z_OFFSET = 38;
    private static final float POSITION_SCALE = 32.0f / 65536.0f;
    private static final float POSITION_BIAS = -8.0f;
    private static final float MID_BLOCK_SCALE = 1.0f / 64.0f;

    private ShadowGeometryFilter() {
    }

    /**
     * Splits every source range while preserving its render pass and the exact
     * bytes of every retained quad.
     *
     * @param source borrowed raw Sodium geometry; it is never closed or mutated
     * @param proceduralBlocks the normalized 4096-bit procedural-ownership mask
     */
    public static Result filter(SodiumGeometryBatch source, long[] proceduralBlocks) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(proceduralBlocks, "proceduralBlocks");
        if (source.isClosed()) {
            throw new IllegalStateException("Cannot filter a closed Sodium geometry batch");
        }
        if (proceduralBlocks.length != OCCUPANCY_WORDS) {
            throw new IllegalArgumentException(
                    "Procedural occupancy must contain exactly " + OCCUPANCY_WORDS + " words");
        }

        List<SodiumGeometry> shadowRanges = new ArrayList<>();
        List<SodiumGeometry> proceduralFaceRanges = new ArrayList<>();
        List<PassCounts> passCounts = new ArrayList<>(source.geometries().size());
        List<ProceduralFaceCapture> proceduralFaces = new ArrayList<>();
        long shadowBytes = 0L;
        long proceduralFaceBytes = 0L;
        long sourceQuads = 0L;
        long shadowQuads = 0L;
        long proceduralFaceQuads = 0L;
        long unresolvedQuads = 0L;

        try {
            for (int rangeIndex = 0; rangeIndex < source.geometries().size(); rangeIndex++) {
                SodiumGeometry geometry = source.geometries().get(rangeIndex);
                ByteBuffer vertices = validatedVertices(geometry);
                RangeScan scan = scan(vertices, geometry.pass(), geometry.quadCount(), proceduralBlocks);

                int shadowRangeIndex = -1;
                NativeBuffer shadowBuffer = null;
                if (scan.shadowQuads() > 0) {
                    shadowBuffer = new NativeBuffer(bytesForQuads(scan.shadowQuads()));
                    shadowRangeIndex = shadowRanges.size();
                }

                int proceduralRangeIndex = -1;
                NativeBuffer proceduralBuffer = null;
                if (scan.proceduralFaceQuads() > 0) {
                    proceduralBuffer = new NativeBuffer(bytesForQuads(scan.proceduralFaceQuads()));
                    proceduralRangeIndex = proceduralFaceRanges.size();
                }

                try {
                    copyRange(
                            vertices,
                            geometry,
                            rangeIndex,
                            proceduralBlocks,
                            shadowBuffer,
                            shadowRangeIndex,
                            proceduralBuffer,
                            proceduralRangeIndex,
                            proceduralFaces);

                    if (shadowBuffer != null) {
                        long bytes = bytesForQuads(scan.shadowQuads());
                        shadowRanges.add(new SodiumGeometry(
                                geometry.pass(), shadowBuffer, scan.shadowQuads(), bytes));
                        shadowBuffer = null;
                        shadowBytes = Math.addExact(shadowBytes, bytes);
                    }
                    if (proceduralBuffer != null) {
                        long bytes = bytesForQuads(scan.proceduralFaceQuads());
                        proceduralFaceRanges.add(new SodiumGeometry(
                                geometry.pass(), proceduralBuffer, scan.proceduralFaceQuads(), bytes));
                        proceduralBuffer = null;
                        proceduralFaceBytes = Math.addExact(proceduralFaceBytes, bytes);
                    }
                } finally {
                    if (shadowBuffer != null) {
                        shadowBuffer.free();
                    }
                    if (proceduralBuffer != null) {
                        proceduralBuffer.free();
                    }
                }

                passCounts.add(new PassCounts(
                        rangeIndex,
                        geometry.pass(),
                        geometry.quadCount(),
                        scan.shadowQuads(),
                        scan.proceduralFaceQuads(),
                        scan.unresolvedQuads()));
                sourceQuads = Math.addExact(sourceQuads, geometry.quadCount());
                shadowQuads = Math.addExact(shadowQuads, scan.shadowQuads());
                proceduralFaceQuads = Math.addExact(
                        proceduralFaceQuads, scan.proceduralFaceQuads());
                unresolvedQuads = Math.addExact(unresolvedQuads, scan.unresolvedQuads());
            }

            SodiumGeometryBatch shadowBatch = SodiumGeometryBatch.owned(shadowRanges, shadowBytes);
            shadowRanges.clear();
            SodiumGeometryBatch proceduralFaceBatch = null;
            try {
                proceduralFaceBatch = SodiumGeometryBatch.owned(
                        proceduralFaceRanges, proceduralFaceBytes);
                proceduralFaceRanges.clear();
                return new Result(
                        shadowBatch,
                        proceduralFaceBatch,
                        passCounts,
                        proceduralFaces,
                        sourceQuads,
                        shadowQuads,
                        proceduralFaceQuads,
                        unresolvedQuads);
            } catch (Throwable throwable) {
                shadowBatch.close();
                if (proceduralFaceBatch != null) {
                    proceduralFaceBatch.close();
                }
                throw throwable;
            }
        } catch (Throwable throwable) {
            freeRanges(shadowRanges);
            freeRanges(proceduralFaceRanges);
            throw throwable;
        }
    }

    private static RangeScan scan(
            ByteBuffer vertices,
            TerrainRenderPass pass,
            int quadCount,
            long[] proceduralBlocks) {
        int shadowQuads = 0;
        int proceduralFaceQuads = 0;
        int unresolvedQuads = 0;
        for (int quadIndex = 0; quadIndex < quadCount; quadIndex++) {
            OwnerCell owner = reconstructOwner(vertices, quadIndex * QUAD_BYTES);
            if (owner == null) {
                shadowQuads++;
                unresolvedQuads++;
            } else if (pass == DefaultTerrainRenderPasses.SOLID
                    && isProceduralOwner(proceduralBlocks, owner)) {
                proceduralFaceQuads++;
            } else {
                shadowQuads++;
            }
        }
        return new RangeScan(shadowQuads, proceduralFaceQuads, unresolvedQuads);
    }

    private static void copyRange(
            ByteBuffer vertices,
            SodiumGeometry geometry,
            int sourceRangeIndex,
            long[] proceduralBlocks,
            NativeBuffer shadowBuffer,
            int shadowRangeIndex,
            NativeBuffer proceduralBuffer,
            int proceduralRangeIndex,
            List<ProceduralFaceCapture> proceduralFaces) {
        ByteBuffer shadow = writable(shadowBuffer);
        ByteBuffer procedural = writable(proceduralBuffer);
        int shadowQuadIndex = 0;
        int proceduralQuadIndex = 0;

        for (int sourceQuadIndex = 0; sourceQuadIndex < geometry.quadCount(); sourceQuadIndex++) {
            int quadOffset = sourceQuadIndex * QUAD_BYTES;
            OwnerCell owner = reconstructOwner(vertices, quadOffset);
            if (owner != null
                    && geometry.pass() == DefaultTerrainRenderPasses.SOLID
                    && isProceduralOwner(proceduralBlocks, owner)) {
                copyQuad(vertices, quadOffset, procedural);
                proceduralFaces.add(captureFace(
                        vertices,
                        quadOffset,
                        sourceRangeIndex,
                        sourceQuadIndex,
                        proceduralRangeIndex,
                        proceduralQuadIndex,
                        geometry.pass(),
                        owner));
                proceduralQuadIndex++;
            } else {
                copyQuad(vertices, quadOffset, shadow);
                shadowQuadIndex++;
            }
        }

        finishWrite(shadow, shadowBuffer, shadowQuadIndex);
        finishWrite(procedural, proceduralBuffer, proceduralQuadIndex);
        if (shadowBuffer != null && shadowRangeIndex < 0) {
            throw new IllegalStateException("Shadow output range index was not assigned");
        }
        if (proceduralBuffer != null && proceduralRangeIndex < 0) {
            throw new IllegalStateException("Procedural output range index was not assigned");
        }
    }

    private static ProceduralFaceCapture captureFace(
            ByteBuffer vertices,
            int quadOffset,
            int sourceRangeIndex,
            int sourceQuadIndex,
            int capturedRangeIndex,
            int capturedQuadIndex,
            TerrainRenderPass pass,
            OwnerCell owner) {
        int normalX = 0;
        int normalY = 0;
        int normalZ = 0;
        for (int vertex = 0; vertex < VERTICES_PER_QUAD; vertex++) {
            int vertexOffset = quadOffset + vertex * VERTEX_BYTES;
            normalX += vertices.get(vertexOffset + NORMAL_X_OFFSET);
            normalY += vertices.get(vertexOffset + NORMAL_Y_OFFSET);
            normalZ += vertices.get(vertexOffset + NORMAL_Z_OFFSET);
        }
        return new ProceduralFaceCapture(
                sourceRangeIndex,
                sourceQuadIndex,
                capturedRangeIndex,
                capturedQuadIndex,
                pass,
                owner,
                faceFromNormal(normalX, normalY, normalZ),
                vertices.getShort(quadOffset + BLOCK_ID_OFFSET),
                vertices.getShort(quadOffset + RENDER_TYPE_OFFSET));
    }

    private static Face faceFromNormal(int x, int y, int z) {
        int absX = Math.abs(x);
        int absY = Math.abs(y);
        int absZ = Math.abs(z);
        if (absX == 0 && absY == 0 && absZ == 0) {
            return Face.UNKNOWN;
        }
        if (absX > absY && absX > absZ) {
            return x < 0 ? Face.NEGATIVE_X : Face.POSITIVE_X;
        }
        if (absY > absX && absY > absZ) {
            return y < 0 ? Face.NEGATIVE_Y : Face.POSITIVE_Y;
        }
        if (absZ > absX && absZ > absY) {
            return z < 0 ? Face.NEGATIVE_Z : Face.POSITIVE_Z;
        }
        return Face.UNKNOWN;
    }

    private static OwnerCell reconstructOwner(ByteBuffer vertices, int quadOffset) {
        float centerSumX = 0.0f;
        float centerSumY = 0.0f;
        float centerSumZ = 0.0f;
        boolean hasMidBlock = false;
        int independentCellX = Integer.MIN_VALUE;
        int independentCellY = Integer.MIN_VALUE;
        int independentCellZ = Integer.MIN_VALUE;

        for (int vertex = 0; vertex < VERTICES_PER_QUAD; vertex++) {
            int vertexOffset = quadOffset + vertex * VERTEX_BYTES;
            int midX = vertices.get(vertexOffset + MID_BLOCK_X_OFFSET);
            int midY = vertices.get(vertexOffset + MID_BLOCK_Y_OFFSET);
            int midZ = vertices.get(vertexOffset + MID_BLOCK_Z_OFFSET);
            hasMidBlock |= midX != 0 || midY != 0 || midZ != 0;

            float centerX = decodePosition(vertices, vertexOffset + POSITION_X_OFFSET)
                    + midX * MID_BLOCK_SCALE;
            float centerY = decodePosition(vertices, vertexOffset + POSITION_Y_OFFSET)
                    + midY * MID_BLOCK_SCALE;
            float centerZ = decodePosition(vertices, vertexOffset + POSITION_Z_OFFSET)
                    + midZ * MID_BLOCK_SCALE;
            centerSumX += centerX;
            centerSumY += centerY;
            centerSumZ += centerZ;

            int cellX = floorToInt(centerX);
            int cellY = floorToInt(centerY);
            int cellZ = floorToInt(centerZ);
            if (vertex == 0) {
                independentCellX = cellX;
                independentCellY = cellY;
                independentCellZ = cellZ;
            } else if (cellX != independentCellX
                    || cellY != independentCellY
                    || cellZ != independentCellZ) {
                return null;
            }
        }

        if (!hasMidBlock) {
            return null;
        }

        int averagedCellX = floorToInt(centerSumX * 0.25f);
        int averagedCellY = floorToInt(centerSumY * 0.25f);
        int averagedCellZ = floorToInt(centerSumZ * 0.25f);
        if (averagedCellX != independentCellX
                || averagedCellY != independentCellY
                || averagedCellZ != independentCellZ
                || !isSectionLocal(averagedCellX)
                || !isSectionLocal(averagedCellY)
                || !isSectionLocal(averagedCellZ)) {
            return null;
        }
        return new OwnerCell(averagedCellX, averagedCellY, averagedCellZ);
    }

    private static float decodePosition(ByteBuffer vertices, int offset) {
        return Short.toUnsignedInt(vertices.getShort(offset)) * POSITION_SCALE + POSITION_BIAS;
    }

    private static boolean isProceduralOwner(long[] proceduralBlocks, OwnerCell owner) {
        int bit = ((owner.y() * SECTION_SIZE + owner.z()) * SECTION_SIZE) + owner.x();
        return (proceduralBlocks[bit >>> 6] & (1L << (bit & 63))) != 0L;
    }

    private static boolean isSectionLocal(int coordinate) {
        return coordinate >= 0 && coordinate < SECTION_SIZE;
    }

    private static int floorToInt(float value) {
        return (int) Math.floor(value);
    }

    private static ByteBuffer validatedVertices(SodiumGeometry geometry) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(geometry.pass(), "geometry.pass");
        NativeBuffer nativeBuffer = Objects.requireNonNull(
                geometry.vertexData(), "geometry.vertexData");
        if (geometry.quadCount() < 0) {
            throw new IllegalArgumentException("quadCount cannot be negative");
        }
        long expectedBytes = Math.multiplyExact((long) geometry.quadCount(), QUAD_BYTES);
        if (geometry.sizeBytes() != expectedBytes) {
            throw new IllegalArgumentException(
                    "Raw shadow filtering requires " + VERTEX_BYTES
                            + "-byte vertices: quads=" + geometry.quadCount()
                            + ", expectedBytes=" + expectedBytes
                            + ", actualBytes=" + geometry.sizeBytes());
        }
        if (expectedBytes > nativeBuffer.getLength()) {
            throw new IllegalArgumentException(
                    "Geometry range exceeds its native buffer: required=" + expectedBytes
                            + ", available=" + nativeBuffer.getLength());
        }
        ByteBuffer vertices = nativeBuffer.getDirectBuffer().duplicate().order(ByteOrder.nativeOrder());
        vertices.clear();
        vertices.limit(Math.toIntExact(expectedBytes));
        return vertices;
    }

    private static ByteBuffer writable(NativeBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        ByteBuffer writable = buffer.getDirectBuffer().duplicate().order(ByteOrder.nativeOrder());
        writable.clear();
        return writable;
    }

    private static void copyQuad(ByteBuffer source, int sourceOffset, ByteBuffer destination) {
        if (destination == null) {
            throw new IllegalStateException("Missing destination for classified quad");
        }
        ByteBuffer quad = source.duplicate().order(ByteOrder.nativeOrder());
        quad.position(sourceOffset);
        quad.limit(sourceOffset + QUAD_BYTES);
        destination.put(quad);
    }

    private static void finishWrite(ByteBuffer destination, NativeBuffer buffer, int writtenQuads) {
        if (buffer == null) {
            if (writtenQuads != 0) {
                throw new IllegalStateException("Classified quads were written without an output buffer");
            }
            return;
        }
        int expectedBytes = bytesForQuads(writtenQuads);
        if (destination.position() != expectedBytes || buffer.getLength() != expectedBytes) {
            throw new IllegalStateException(
                    "Filtered geometry size mismatch: written=" + destination.position()
                            + ", expected=" + expectedBytes
                            + ", allocated=" + buffer.getLength());
        }
        destination.position(0);
        destination.limit(expectedBytes);
    }

    private static int bytesForQuads(int quadCount) {
        return Math.multiplyExact(quadCount, QUAD_BYTES);
    }

    private static void freeRanges(List<SodiumGeometry> ranges) {
        for (SodiumGeometry geometry : ranges) {
            geometry.vertexData().free();
        }
        ranges.clear();
    }

    private record RangeScan(int shadowQuads, int proceduralFaceQuads, int unresolvedQuads) {
    }

    public record OwnerCell(int x, int y, int z) {
        public OwnerCell {
            if (!isSectionLocal(x) || !isSectionLocal(y) || !isSectionLocal(z)) {
                throw new IllegalArgumentException("Owner cell is outside the section");
            }
        }
    }

    public enum Face {
        NEGATIVE_X,
        POSITIVE_X,
        NEGATIVE_Y,
        POSITIVE_Y,
        NEGATIVE_Z,
        POSITIVE_Z,
        UNKNOWN
    }

    /** Per-source-range accounting. Zero-sized output ranges are not emitted. */
    public record PassCounts(
            int sourceRangeIndex,
            TerrainRenderPass pass,
            int sourceQuads,
            int shadowQuads,
            int proceduralFaceQuads,
            int unresolvedQuads) {
        public PassCounts {
            Objects.requireNonNull(pass, "pass");
            if (sourceRangeIndex < 0 || sourceQuads < 0 || shadowQuads < 0
                    || proceduralFaceQuads < 0 || unresolvedQuads < 0
                    || shadowQuads + proceduralFaceQuads != sourceQuads
                    || unresolvedQuads > shadowQuads) {
                throw new IllegalArgumentException("Invalid per-pass shadow split counts");
            }
        }
    }

    /**
     * Metadata parallel to one byte-exact quad in
     * {@link Result#proceduralFaceGeometry()}.
     */
    public record ProceduralFaceCapture(
            int sourceRangeIndex,
            int sourceQuadIndex,
            int capturedRangeIndex,
            int capturedQuadIndex,
            TerrainRenderPass pass,
            OwnerCell owner,
            Face face,
            short blockId,
            short renderType) {
        public ProceduralFaceCapture {
            if (sourceRangeIndex < 0 || sourceQuadIndex < 0
                    || capturedRangeIndex < 0 || capturedQuadIndex < 0) {
                throw new IllegalArgumentException("Face-capture indices cannot be negative");
            }
            Objects.requireNonNull(pass, "pass");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(face, "face");
        }
    }

    /** Owns both derived native-buffer batches and may be closed repeatedly. */
    public static final class Result implements AutoCloseable {
        private final SodiumGeometryBatch shadowGeometry;
        private final SodiumGeometryBatch proceduralFaceGeometry;
        private final List<PassCounts> passCounts;
        private final List<ProceduralFaceCapture> proceduralFaces;
        private final long sourceQuads;
        private final long shadowQuads;
        private final long proceduralFaceQuads;
        private final long unresolvedQuads;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Result(
                SodiumGeometryBatch shadowGeometry,
                SodiumGeometryBatch proceduralFaceGeometry,
                List<PassCounts> passCounts,
                List<ProceduralFaceCapture> proceduralFaces,
                long sourceQuads,
                long shadowQuads,
                long proceduralFaceQuads,
                long unresolvedQuads) {
            this.shadowGeometry = Objects.requireNonNull(shadowGeometry, "shadowGeometry");
            this.proceduralFaceGeometry = Objects.requireNonNull(
                    proceduralFaceGeometry, "proceduralFaceGeometry");
            this.passCounts = List.copyOf(passCounts);
            this.proceduralFaces = List.copyOf(proceduralFaces);
            this.sourceQuads = sourceQuads;
            this.shadowQuads = shadowQuads;
            this.proceduralFaceQuads = proceduralFaceQuads;
            this.unresolvedQuads = unresolvedQuads;
            if (shadowQuads + proceduralFaceQuads != sourceQuads
                    || unresolvedQuads > shadowQuads
                    || proceduralFaces.size() != proceduralFaceQuads) {
                throw new IllegalArgumentException("Invalid aggregate shadow split counts");
            }
        }

        public SodiumGeometryBatch shadowGeometry() {
            return shadowGeometry;
        }

        public SodiumGeometryBatch proceduralFaceGeometry() {
            return proceduralFaceGeometry;
        }

        public List<PassCounts> passCounts() {
            return passCounts;
        }

        public List<ProceduralFaceCapture> proceduralFaces() {
            return proceduralFaces;
        }

        public long sourceQuads() {
            return sourceQuads;
        }

        public long shadowQuads() {
            return shadowQuads;
        }

        public long proceduralFaceQuads() {
            return proceduralFaceQuads;
        }

        public long removedShadowQuads() {
            return proceduralFaceQuads;
        }

        public long sourceTriangles() {
            return Math.multiplyExact(sourceQuads, 2L);
        }

        public long shadowTriangles() {
            return Math.multiplyExact(shadowQuads, 2L);
        }

        public long removedShadowTriangles() {
            return Math.multiplyExact(proceduralFaceQuads, 2L);
        }

        public long unresolvedQuads() {
            return unresolvedQuads;
        }

        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            shadowGeometry.close();
            proceduralFaceGeometry.close();
        }
    }
}

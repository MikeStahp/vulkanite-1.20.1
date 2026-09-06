package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.acceleration.voxel.ProceduralMaterialExtractor;
import me.cortex.vulkanite.acceleration.voxel.ProceduralMaterialPayload;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowGeometryFilterTest {
    private static Field sodiumConfigField;
    private static Object previousSodiumConfig;

    @BeforeAll
    static void bootstrapMinecraftRegistries() throws ReflectiveOperationException {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        // NativeBuffer reads Sodium's memory-tracing option. Supply in-memory
        // defaults without starting a client or reading/writing a user's config.
        sodiumConfigField = SodiumClientMod.class.getDeclaredField("CONFIG");
        sodiumConfigField.setAccessible(true);
        previousSodiumConfig = sodiumConfigField.get(null);
        sodiumConfigField.set(null, SodiumGameOptions.defaults());
    }

    @AfterAll
    static void restoreSodiumConfig() throws IllegalAccessException {
        if (sodiumConfigField != null) {
            sodiumConfigField.set(null, previousSodiumConfig);
        }
    }

    @Test
    void splitsOwnedFacesAndPreservesBothRawQuadStreamsByteForByte() {
        byte[] proceduralQuad = positiveXFace(1, 2, 3, (short) 77, (short) 9, 0x21);
        byte[] shadowQuad = positiveXFace(4, 5, 6, (short) 88, (short) 10, 0x42);
        SodiumGeometryBatch source = ownedBatch(new Range(
                DefaultTerrainRenderPasses.SOLID, proceduralQuad, shadowQuad));
        long[] mask = mask(1, 2, 3);

        try (source; ShadowGeometryFilter.Result result = ShadowGeometryFilter.filter(source, mask)) {
            assertEquals(2, result.sourceQuads());
            assertEquals(1, result.shadowQuads());
            assertEquals(1, result.proceduralFaceQuads());
            assertEquals(4, result.sourceTriangles());
            assertEquals(2, result.shadowTriangles());
            assertEquals(2, result.removedShadowTriangles());
            assertEquals(0, result.unresolvedQuads());

            assertEquals(1, result.shadowGeometry().geometries().size());
            assertEquals(1, result.proceduralFaceGeometry().geometries().size());
            assertArrayEquals(shadowQuad, bytes(result.shadowGeometry().geometries().get(0)));
            assertArrayEquals(proceduralQuad,
                    bytes(result.proceduralFaceGeometry().geometries().get(0)));

            ShadowGeometryFilter.ProceduralFaceCapture face = result.proceduralFaces().get(0);
            assertEquals(0, face.sourceRangeIndex());
            assertEquals(0, face.sourceQuadIndex());
            assertEquals(0, face.capturedRangeIndex());
            assertEquals(0, face.capturedQuadIndex());
            assertEquals(new ShadowGeometryFilter.OwnerCell(1, 2, 3), face.owner());
            assertEquals(ShadowGeometryFilter.Face.POSITIVE_X, face.face());
            assertEquals((short) 77, face.blockId());
            assertEquals((short) 9, face.renderType());

            ShadowGeometryFilter.PassCounts counts = result.passCounts().get(0);
            assertEquals(2, counts.sourceQuads());
            assertEquals(1, counts.shadowQuads());
            assertEquals(1, counts.proceduralFaceQuads());
            assertEquals(0, counts.unresolvedQuads());
        }
    }

    @Test
    void omitsZeroSizedPerPassRangesWithoutReorderingRemainingPasses() {
        byte[] ownedSolid = positiveXFace(2, 3, 4, (short) 1, (short) 2, 0x11);
        byte[] triangleCutout = positiveXFace(7, 8, 9, (short) 3, (short) 4, 0x33);
        SodiumGeometryBatch source = ownedBatch(
                new Range(DefaultTerrainRenderPasses.SOLID, ownedSolid),
                new Range(DefaultTerrainRenderPasses.CUTOUT, triangleCutout));

        try (source; ShadowGeometryFilter.Result result = ShadowGeometryFilter.filter(
                source, mask(2, 3, 4))) {
            assertEquals(1, result.shadowGeometry().geometries().size());
            assertEquals(DefaultTerrainRenderPasses.CUTOUT,
                    result.shadowGeometry().geometries().get(0).pass());
            assertArrayEquals(triangleCutout,
                    bytes(result.shadowGeometry().geometries().get(0)));

            assertEquals(1, result.proceduralFaceGeometry().geometries().size());
            assertEquals(DefaultTerrainRenderPasses.SOLID,
                    result.proceduralFaceGeometry().geometries().get(0).pass());
            assertArrayEquals(ownedSolid,
                    bytes(result.proceduralFaceGeometry().geometries().get(0)));

            assertEquals(2, result.passCounts().size());
            assertEquals(0, result.passCounts().get(0).shadowQuads());
            assertEquals(0, result.passCounts().get(1).proceduralFaceQuads());
        }
    }

    @Test
    void representsAnAllProceduralSectionWithNoZeroSizedShadowRange() {
        byte[] ownedSolid = positiveXFace(3, 4, 5, (short) 7, (short) 2, 0x19);
        SodiumGeometryBatch source = ownedBatch(
                new Range(DefaultTerrainRenderPasses.SOLID, ownedSolid));

        try (source; ShadowGeometryFilter.Result result = ShadowGeometryFilter.filter(
                source, mask(3, 4, 5))) {
            assertTrue(result.shadowGeometry().isEmpty());
            assertEquals(0, result.shadowQuads());
            assertEquals(1, result.proceduralFaceQuads());
            assertEquals(2, result.removedShadowTriangles());
        }
    }

    @Test
    void materialExtractionCanonicalizesFaceCornersAndRejectsConflictingVertexIds() {
        byte[] ownedSolid = positiveXFace(1, 2, 3, (short) 77, (short) 9, 0x21);
        ByteBuffer quad = ByteBuffer.wrap(ownedSolid).order(ByteOrder.nativeOrder());
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * ShadowGeometryFilter.VERTEX_BYTES;
            quad.putShort(offset + 12, (short) (100 + vertex * 10));
            quad.putShort(offset + 14, (short) (200 + vertex * 10));
        }
        SodiumGeometryBatch source = ownedBatch(
                new Range(DefaultTerrainRenderPasses.SOLID, ownedSolid));

        try (source; ShadowGeometryFilter.Result result = ShadowGeometryFilter.filter(
                source, mask(1, 2, 3))) {
            ProceduralMaterialPayload payload = ProceduralMaterialExtractor.extract(result);
            ProceduralMaterialPayload.FaceLayer layer = payload.cellMaterial(1, 2, 3)
                    .face(ProceduralMaterialPayload.Face.POSITIVE_X).layers().get(0);
            float scale = 1.0f / 65536.0f;
            assertEquals(100 * scale, layer.uv00().u());
            assertEquals(130 * scale, layer.uv10().u());
            assertEquals(120 * scale, layer.uv11().u());
            assertEquals(110 * scale, layer.uv01().u());

            SectionLightTable sectionData = new SectionLightTable(
                    ChunkSectionPos.from(0, 0, 0),
                    List.of(new SectionLight(
                            SectionLight.packLocalBlockPos(1, 2, 3),
                            SectionLight.packRgbEmission(255, 255, 255, 12),
                            (short) 12,
                            (short) 0)),
                    mask(1, 2, 3));
            assertEquals(12.0f, ProceduralMaterialExtractor.extract(result, sectionData)
                    .cellMaterial(1, 2, 3).intrinsicEmission());
        }

        byte[] conflicting = positiveXFace(1, 2, 3, (short) 77, (short) 9, 0x22);
        ByteBuffer.wrap(conflicting).order(ByteOrder.nativeOrder())
                .putShort(ShadowGeometryFilter.VERTEX_BYTES + 32, (short) 78);
        SodiumGeometryBatch conflictingSource = ownedBatch(
                new Range(DefaultTerrainRenderPasses.SOLID, conflicting));
        try (conflictingSource; ShadowGeometryFilter.Result result = ShadowGeometryFilter.filter(
                conflictingSource, mask(1, 2, 3))) {
            assertTrue(ProceduralMaterialExtractor.extract(result).isEmpty());
        }

        byte[] varyingTint = positiveXFace(1, 2, 3, (short) 77, (short) 9, 0x23);
        ByteBuffer.wrap(varyingTint).order(ByteOrder.nativeOrder())
                .putInt(ShadowGeometryFilter.VERTEX_BYTES + 8, 0x01020304);
        SodiumGeometryBatch varyingTintSource = ownedBatch(
                new Range(DefaultTerrainRenderPasses.SOLID, varyingTint));
        try (varyingTintSource; ShadowGeometryFilter.Result result = ShadowGeometryFilter.filter(
                varyingTintSource, mask(1, 2, 3))) {
            assertTrue(ProceduralMaterialExtractor.extract(result).isEmpty());
        }
    }

    @Test
    void retainsMissingInconsistentAndOutOfSectionOwnershipConservatively() {
        byte[] missing = positiveXFace(1, 1, 1, (short) 11, (short) 0, 0x12);
        clearMidBlock(missing);

        byte[] inconsistent = positiveXFace(2, 2, 2, (short) 12, (short) 0, 0x23);
        // The first vertex now reconstructs a center in the adjacent X cell.
        inconsistent[36] = 32;

        byte[] outside = positiveXFace(16, 3, 3, (short) 13, (short) 0, 0x34);
        SodiumGeometryBatch source = ownedBatch(new Range(
                DefaultTerrainRenderPasses.SOLID, missing, inconsistent, outside));
        long[] mask = new long[ShadowGeometryFilter.OCCUPANCY_WORDS];
        set(mask, 1, 1, 1);
        set(mask, 2, 2, 2);

        try (source; ShadowGeometryFilter.Result result = ShadowGeometryFilter.filter(source, mask)) {
            assertEquals(3, result.shadowQuads());
            assertEquals(0, result.proceduralFaceQuads());
            assertEquals(3, result.unresolvedQuads());
            assertTrue(result.proceduralFaceGeometry().isEmpty());
            assertEquals(1, result.shadowGeometry().geometries().size());
            assertArrayEquals(concat(missing, inconsistent, outside),
                    bytes(result.shadowGeometry().geometries().get(0)));
        }
    }

    @Test
    void rejectsMalformedOccupancyAndNonFortyByteVertexRanges() {
        byte[] quad = positiveXFace(0, 0, 0, (short) 1, (short) 0, 0x55);
        SodiumGeometryBatch source = ownedBatch(new Range(DefaultTerrainRenderPasses.SOLID, quad));
        try (source) {
            assertThrows(IllegalArgumentException.class,
                    () -> ShadowGeometryFilter.filter(source, new long[63]));
        }

        NativeBuffer malformed = new NativeBuffer(ShadowGeometryFilter.QUAD_BYTES - 1);
        SodiumGeometryBatch malformedSource = SodiumGeometryBatch.owned(
                List.of(new SodiumGeometry(
                        DefaultTerrainRenderPasses.SOLID,
                        malformed,
                        1,
                        ShadowGeometryFilter.QUAD_BYTES - 1L)),
                ShadowGeometryFilter.QUAD_BYTES - 1L);
        try (malformedSource) {
            assertThrows(IllegalArgumentException.class,
                    () -> ShadowGeometryFilter.filter(
                            malformedSource,
                            new long[ShadowGeometryFilter.OCCUPANCY_WORDS]));
        }
    }

    @Test
    void derivedBufferOwnershipIsExplicitAndCloseIsIdempotent() {
        SodiumGeometryBatch source = ownedBatch(new Range(
                DefaultTerrainRenderPasses.TRANSLUCENT,
                positiveXFace(5, 5, 5, (short) 9, (short) 0, 0x66)));
        ShadowGeometryFilter.Result result = ShadowGeometryFilter.filter(
                source, new long[ShadowGeometryFilter.OCCUPANCY_WORDS]);
        try {
            assertTrue(result.shadowGeometry().ownsVertexData());
            assertTrue(result.proceduralFaceGeometry().ownsVertexData());
            assertFalse(result.isClosed());
            result.close();
            result.close();
            assertTrue(result.isClosed());
            assertTrue(result.shadowGeometry().isClosed());
            assertTrue(result.proceduralFaceGeometry().isClosed());
        } finally {
            result.close();
            source.close();
            source.close();
        }
    }

    private static SodiumGeometryBatch ownedBatch(Range... ranges) {
        List<SodiumGeometry> geometries = new ArrayList<>();
        long totalBytes = 0L;
        try {
            for (Range range : ranges) {
                byte[] bytes = concat(range.quads());
                NativeBuffer buffer = new NativeBuffer(bytes.length);
                ByteBuffer destination = buffer.getDirectBuffer().duplicate();
                destination.clear();
                destination.put(bytes);
                geometries.add(new SodiumGeometry(
                        range.pass(), buffer, range.quads().length, bytes.length));
                totalBytes += bytes.length;
            }
            return SodiumGeometryBatch.owned(geometries, totalBytes);
        } catch (Throwable throwable) {
            for (SodiumGeometry geometry : geometries) {
                geometry.vertexData().free();
            }
            throw throwable;
        }
    }

    private static byte[] bytes(SodiumGeometry geometry) {
        byte[] bytes = new byte[Math.toIntExact(geometry.sizeBytes())];
        ByteBuffer source = geometry.vertexData().getDirectBuffer().duplicate();
        source.clear();
        source.limit(bytes.length);
        source.get(bytes);
        return bytes;
    }

    private static byte[] positiveXFace(
            int cellX,
            int cellY,
            int cellZ,
            short blockId,
            short renderType,
            int seed) {
        float[][] positions = {
                {cellX + 1.0f, cellY, cellZ},
                {cellX + 1.0f, cellY + 1.0f, cellZ},
                {cellX + 1.0f, cellY + 1.0f, cellZ + 1.0f},
                {cellX + 1.0f, cellY, cellZ + 1.0f}
        };
        float centerX = cellX + 0.5f;
        float centerY = cellY + 0.5f;
        float centerZ = cellZ + 0.5f;
        ByteBuffer quad = ByteBuffer.allocate(ShadowGeometryFilter.QUAD_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < quad.capacity(); i++) {
            quad.put(i, (byte) (seed + i * 13));
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * ShadowGeometryFilter.VERTEX_BYTES;
            quad.putShort(offset, packPosition(positions[vertex][0]));
            quad.putShort(offset + 2, packPosition(positions[vertex][1]));
            quad.putShort(offset + 4, packPosition(positions[vertex][2]));
            quad.putInt(offset + 8, 0xffa0b0c0);
            quad.putInt(offset + 24, 0x7f00007f);
            quad.put(offset + 28, (byte) 127);
            quad.put(offset + 29, (byte) 0);
            quad.put(offset + 30, (byte) 0);
            quad.putShort(offset + 32, blockId);
            quad.putShort(offset + 34, renderType);
            quad.put(offset + 36, packMidBlock(centerX - positions[vertex][0]));
            quad.put(offset + 37, packMidBlock(centerY - positions[vertex][1]));
            quad.put(offset + 38, packMidBlock(centerZ - positions[vertex][2]));
        }
        return quad.array();
    }

    private static short packPosition(float coordinate) {
        int packed = Math.round((coordinate + 8.0f) * (65536.0f / 32.0f));
        return (short) packed;
    }

    private static byte packMidBlock(float centerMinusVertex) {
        return (byte) Math.round(centerMinusVertex * 64.0f);
    }

    private static void clearMidBlock(byte[] quad) {
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * ShadowGeometryFilter.VERTEX_BYTES + 36;
            quad[offset] = 0;
            quad[offset + 1] = 0;
            quad[offset + 2] = 0;
        }
    }

    private static long[] mask(int x, int y, int z) {
        long[] mask = new long[ShadowGeometryFilter.OCCUPANCY_WORDS];
        set(mask, x, y, z);
        return mask;
    }

    private static void set(long[] mask, int x, int y, int z) {
        int bit = ((y * 16 + z) * 16) + x;
        mask[bit >>> 6] |= 1L << (bit & 63);
    }

    private static byte[] concat(byte[]... arrays) {
        int size = 0;
        for (byte[] array : arrays) {
            size = Math.addExact(size, array.length);
        }
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private record Range(TerrainRenderPass pass, byte[]... quads) {
    }
}

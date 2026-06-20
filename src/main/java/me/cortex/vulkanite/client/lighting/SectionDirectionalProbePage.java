package me.cortex.vulkanite.client.lighting;

import me.cortex.vulkanite.compat.SectionLight;
import me.cortex.vulkanite.compat.SectionLightTable;
import net.minecraft.util.math.ChunkSectionPos;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

final class SectionDirectionalProbePage {
    static final int GRID_SIZE = 8;
    static final int FACE_COUNT = 6;
    static final int PROBE_COUNT = GRID_SIZE * GRID_SIZE * GRID_SIZE;
    static final int FACE_VALUE_COUNT = PROBE_COUNT * FACE_COUNT;
    static final int FACE_RGB_VALUE_COUNT = FACE_VALUE_COUNT * 3;
    static final int PACKED_VALUES_PER_RECORD = 4;
    static final int PACKED_RADIANCE_RECORD_COUNT =
            (FACE_VALUE_COUNT + PACKED_VALUES_PER_RECORD - 1) / PACKED_VALUES_PER_RECORD;
    static final int CONFIDENCE_VALUES_PER_WORD = 4;
    static final int PACKED_CONFIDENCE_WORD_COUNT =
            (FACE_VALUE_COUNT + CONFIDENCE_VALUES_PER_WORD - 1) / CONFIDENCE_VALUES_PER_WORD;
    static final int PACKED_CONFIDENCE_RECORD_COUNT =
            (PACKED_CONFIDENCE_WORD_COUNT + PACKED_VALUES_PER_RECORD - 1) / PACKED_VALUES_PER_RECORD;
    static final int PACKED_RECORD_COUNT = PACKED_RADIANCE_RECORD_COUNT + PACKED_CONFIDENCE_RECORD_COUNT;
    static final int PACKED_RECORD_BYTES = PACKED_VALUES_PER_RECORD * Integer.BYTES;
    static final int PACKED_FACE_DATA_INT_COUNT = FACE_VALUE_COUNT + PACKED_CONFIDENCE_WORD_COUNT;
    static final int PACKED_FACE_DATA_BYTES = PACKED_FACE_DATA_INT_COUNT * Integer.BYTES;
    static final int PACKED_PAGE_BYTES = PACKED_RECORD_COUNT * PACKED_RECORD_BYTES;

    private static final float SECTION_SIZE = 16.0f;
    private static final float PROBE_SPACING = SECTION_SIZE / GRID_SIZE;
    private static final float INV_255 = 1.0f / 255.0f;
    private static final float INV_15 = 1.0f / 15.0f;
    private static final float RGB9E5_MAX_VALUE = 65408.0f;
    private static final float FACE_RECEIVER_OFFSET = PROBE_SPACING * 0.45f;
    private static final int RGB9E5_MANTISSA_BITS = 9;
    private static final int RGB9E5_MANTISSA_MAX = (1 << RGB9E5_MANTISSA_BITS) - 1;
    private static final int RGB9E5_EXPONENT_BITS = 5;
    private static final int RGB9E5_EXPONENT_MAX = (1 << RGB9E5_EXPONENT_BITS) - 1;
    private static final int RGB9E5_EXPONENT_SHIFT = RGB9E5_MANTISSA_BITS * 3;
    private static final int RGB9E5_EXPONENT_BIAS = 15;
    private static final float TEMPORAL_MIN_HISTORY_WEIGHT = 0.18f;
    private static final float TEMPORAL_MAX_HISTORY_WEIGHT = 0.84f;
    private static final float TEMPORAL_MISSING_ESTIMATE_WEIGHT = 0.35f;
    private static final float TEMPORAL_RESET_DELTA = 1.35f;
    private static final float TEMPORAL_EPSILON = 0.0005f;
    private static final float RADIANCE_COMPRESSION_EPSILON = 0.0001f;
    private static final float VOXEL_TRAVERSAL_EPSILON = 0.00001f;
    private static final float MIN_VISIBILITY_CONFIDENCE = 0.02f;
    private static final float MIN_TRAVERSAL_CONFIDENCE = 0.08f;
    private static final float GRAZING_VISIBILITY_CONFIDENCE = 0.45f;
    private static final float LIGHT_SELECTION_CENTER_BIAS = 0.002f;
    private static final float BLOCKLIGHT_CASCADE_DISTANCE_SCALE = 1.75f;
    private static final float BLOCKLIGHT_CASCADE_MAX_DISTANCE = 40.0f;
    private static final int MAX_REUSABLE_SELECTED_LIGHTS = 64;
    private static final Comparator<SelectedLight> LOWEST_SCORE_FIRST =
            Comparator.comparingDouble(SelectedLight::score);

    private final ChunkSectionPos sectionPos;
    private final int[] packedFaceRgb9E5 = new int[FACE_VALUE_COUNT];
    private final int[] packedFaceConfidence = new int[PACKED_CONFIDENCE_WORD_COUNT];
    private final float[] faceRgb = new float[FACE_RGB_VALUE_COUNT];
    private final float[] faceConfidence = new float[FACE_VALUE_COUNT];
    private final float[] faceConfidenceWeight = new float[FACE_VALUE_COUNT];
    private final float[] historyFaceRgb = new float[FACE_RGB_VALUE_COUNT];
    private final float[] historyFaceConfidence = new float[FACE_VALUE_COUNT];
    private final PriorityQueue<SelectedLight> selectedLightQueue = new PriorityQueue<>(
            MAX_REUSABLE_SELECTED_LIGHTS,
            LOWEST_SCORE_FIRST);
    private final ArrayList<SelectedLight> selectedLights = new ArrayList<>(MAX_REUSABLE_SELECTED_LIGHTS);
    private boolean hasPackedRadiance;
    private boolean hasTemporalHistory;
    private int slot = -1;
    private int version;

    SectionDirectionalProbePage(ChunkSectionPos sectionPos) {
        this.sectionPos = sectionPos;
    }

    ChunkSectionPos sectionPos() {
        return sectionPos;
    }

    int slot() {
        return slot;
    }

    void setSlot(int slot) {
        this.slot = slot;
    }

    int version() {
        return version;
    }

    int packedFaceRgb9E5(int index) {
        return packedFaceRgb9E5[index];
    }

    boolean hasPackedRadiance() {
        return hasPackedRadiance;
    }

    void copyTemporalHistoryFrom(SectionDirectionalProbePage previous) {
        if (previous == null || !previous.hasTemporalHistory) {
            return;
        }
        System.arraycopy(previous.historyFaceRgb, 0, historyFaceRgb, 0, FACE_RGB_VALUE_COUNT);
        System.arraycopy(previous.historyFaceConfidence, 0, historyFaceConfidence, 0, FACE_VALUE_COUNT);
        hasTemporalHistory = true;
    }

    void writePackedFaceData(ByteBuffer destination) {
        int startPosition = destination.position();
        destination.asIntBuffer().put(packedFaceRgb9E5, 0, FACE_VALUE_COUNT);
        destination.position(destination.position() + FACE_VALUE_COUNT * Integer.BYTES);
        destination.asIntBuffer().put(packedFaceConfidence, 0, PACKED_CONFIDENCE_WORD_COUNT);
        destination.position(destination.position() + PACKED_CONFIDENCE_WORD_COUNT * Integer.BYTES);
        if (destination.position() != startPosition + PACKED_FACE_DATA_BYTES) {
            throw new IllegalStateException("Section probe packed face data size mismatch");
        }
        while (destination.position() < startPosition + PACKED_PAGE_BYTES) {
            destination.putInt(0);
        }
        if (destination.position() != startPosition + PACKED_PAGE_BYTES) {
            throw new IllegalStateException("Section probe packed page size mismatch");
        }
    }

    boolean regenerateFrom(List<SectionLightTable> tables, int maxLights) {
        if (tables == null || tables.isEmpty() || maxLights <= 0) {
            return clear();
        }

        List<SelectedLight> lights = collectSelectedLights(tables, maxLights);
        if (lights.isEmpty()) {
            return clear();
        }
        SectionOpacityLookup opacityLookup = new SectionOpacityLookup(sectionPos, tables);

        Arrays.fill(faceRgb, 0.0f);
        Arrays.fill(faceConfidence, 0.0f);
        Arrays.fill(faceConfidenceWeight, 0.0f);

        int originX = sectionPos.getMinX();
        int originY = sectionPos.getMinY();
        int originZ = sectionPos.getMinZ();

        boolean anyContribution = false;
        for (SelectedLight light : lights) {
            anyContribution |= accumulateLightIntoProbeBounds(faceRgb, faceConfidence, faceConfidenceWeight,
                    originX, originY, originZ, light, opacityLookup);
        }
        if (!anyContribution) {
            return clear();
        }

        normalizeFaceConfidence();
        applyTemporalHistory();

        boolean changed = false;
        boolean nextHasPackedRadiance = false;
        for (int i = 0; i < FACE_VALUE_COUNT; i++) {
            int rgbIndex = i * 3;
            int packed = packRgb9E5(faceRgb[rgbIndex], faceRgb[rgbIndex + 1], faceRgb[rgbIndex + 2]);
            nextHasPackedRadiance |= packed != 0;
            if (packedFaceRgb9E5[i] != packed) {
                packedFaceRgb9E5[i] = packed;
                changed = true;
            }
        }
        changed |= packFaceConfidenceWords();
        if (changed) {
            hasPackedRadiance = nextHasPackedRadiance;
            version++;
        }
        return changed;
    }

    private boolean clear() {
        if (!hasPackedRadiance && !hasTemporalHistory) {
            return false;
        }
        boolean changed = hasPackedRadiance;
        Arrays.fill(packedFaceRgb9E5, 0);
        Arrays.fill(packedFaceConfidence, 0);
        Arrays.fill(faceConfidenceWeight, 0.0f);
        Arrays.fill(historyFaceRgb, 0.0f);
        Arrays.fill(historyFaceConfidence, 0.0f);
        hasPackedRadiance = false;
        hasTemporalHistory = false;
        if (changed) {
            version++;
        }
        return changed;
    }

    private static boolean accumulateLightIntoProbeBounds(
            float[] rgb,
            float[] confidence,
            float[] confidenceWeight,
            int originX,
            int originY,
            int originZ,
            SelectedLight light,
            SectionOpacityLookup opacityLookup) {
        float probeInfluenceDistance = light.maxDistance() + FACE_RECEIVER_OFFSET;
        int minX = probeCoordMin(light.x(), originX, probeInfluenceDistance);
        int maxX = probeCoordMax(light.x(), originX, probeInfluenceDistance);
        int minY = probeCoordMin(light.y(), originY, probeInfluenceDistance);
        int maxY = probeCoordMax(light.y(), originY, probeInfluenceDistance);
        int minZ = probeCoordMin(light.z(), originZ, probeInfluenceDistance);
        int maxZ = probeCoordMax(light.z(), originZ, probeInfluenceDistance);
        if (minX > GRID_SIZE - 1 || maxX < 0
                || minY > GRID_SIZE - 1 || maxY < 0
                || minZ > GRID_SIZE - 1 || maxZ < 0) {
            return false;
        }

        minX = Math.max(0, minX);
        maxX = Math.min(GRID_SIZE - 1, maxX);
        minY = Math.max(0, minY);
        maxY = Math.min(GRID_SIZE - 1, maxY);
        minZ = Math.max(0, minZ);
        maxZ = Math.min(GRID_SIZE - 1, maxZ);

        boolean contributed = false;
        for (int pz = minZ; pz <= maxZ; pz++) {
            float probeZ = probeCenter(originZ, pz);
            for (int py = minY; py <= maxY; py++) {
                float probeY = probeCenter(originY, py);
                for (int px = minX; px <= maxX; px++) {
                    float probeX = probeCenter(originX, px);
                    contributed |= accumulateLight(
                            rgb, confidence, confidenceWeight, probeIndex(px, py, pz), probeX, probeY, probeZ,
                            originX, originY, originZ, light, opacityLookup);
                }
            }
        }
        return contributed;
    }

    private static boolean accumulateLight(
            float[] rgb,
            float[] confidence,
            float[] confidenceWeight,
            int probeIndex,
            float probeX,
            float probeY,
            float probeZ,
            int originX,
            int originY,
            int originZ,
            SelectedLight selectedLight,
            SectionOpacityLookup opacityLookup) {
        int baseRgbIndex = probeIndex * FACE_COUNT * 3;
        boolean hasOpaqueBlocks = opacityLookup.hasOpaqueBlocks();
        float confidenceScale = hasOpaqueBlocks ? 1.0f : 0.7f;
        if (!sourceInsidePage(selectedLight, originX, originY, originZ)) {
            confidenceScale *= 0.82f;
        }

        boolean contributed = false;
        contributed |= accumulateFace(rgb, confidence, confidenceWeight, baseRgbIndex, 0,
                1.0f, 0.0f, 0.0f,
                probeX, probeY, probeZ,
                selectedLight, opacityLookup, hasOpaqueBlocks, confidenceScale);
        contributed |= accumulateFace(rgb, confidence, confidenceWeight, baseRgbIndex, 1,
                -1.0f, 0.0f, 0.0f,
                probeX, probeY, probeZ,
                selectedLight, opacityLookup, hasOpaqueBlocks, confidenceScale);
        contributed |= accumulateFace(rgb, confidence, confidenceWeight, baseRgbIndex, 2,
                0.0f, 1.0f, 0.0f,
                probeX, probeY, probeZ,
                selectedLight, opacityLookup, hasOpaqueBlocks, confidenceScale);
        contributed |= accumulateFace(rgb, confidence, confidenceWeight, baseRgbIndex, 3,
                0.0f, -1.0f, 0.0f,
                probeX, probeY, probeZ,
                selectedLight, opacityLookup, hasOpaqueBlocks, confidenceScale);
        contributed |= accumulateFace(rgb, confidence, confidenceWeight, baseRgbIndex, 4,
                0.0f, 0.0f, 1.0f,
                probeX, probeY, probeZ,
                selectedLight, opacityLookup, hasOpaqueBlocks, confidenceScale);
        contributed |= accumulateFace(rgb, confidence, confidenceWeight, baseRgbIndex, 5,
                0.0f, 0.0f, -1.0f,
                probeX, probeY, probeZ,
                selectedLight, opacityLookup, hasOpaqueBlocks, confidenceScale);
        return contributed;
    }

    private static boolean accumulateFace(
            float[] rgb,
            float[] confidence,
            float[] confidenceWeight,
            int baseRgbIndex,
            int face,
            float faceDirectionX,
            float faceDirectionY,
            float faceDirectionZ,
            float probeX,
            float probeY,
            float probeZ,
            SelectedLight selectedLight,
            SectionOpacityLookup opacityLookup,
            boolean hasOpaqueBlocks,
            float confidenceScale) {
        float receiverX = probeX + faceDirectionX * FACE_RECEIVER_OFFSET;
        float receiverY = probeY + faceDirectionY * FACE_RECEIVER_OFFSET;
        float receiverZ = probeZ + faceDirectionZ * FACE_RECEIVER_OFFSET;
        float dx = selectedLight.x() - receiverX;
        float dy = selectedLight.y() - receiverY;
        float dz = selectedLight.z() - receiverZ;
        float dist2 = dx * dx + dy * dy + dz * dz;
        if (dist2 <= 0.0001f || dist2 > selectedLight.maxDistanceSquared()) {
            return false;
        }

        float distance = (float) Math.sqrt(dist2);
        float invDistance = 1.0f / distance;
        float directionX = dx * invDistance;
        float directionY = dy * invDistance;
        float directionZ = dz * invDistance;

        float weight = directionX * faceDirectionX
                + directionY * faceDirectionY
                + directionZ * faceDirectionZ;
        if (weight <= 0.0001f) {
            return false;
        }

        float attenuation = attenuationAtDistance(distance, selectedLight.maxDistance());
        if (attenuation <= 0.0f) {
            return false;
        }

        float visibility = hasOpaqueBlocks
                ? probeVisibility(selectedLight, receiverX, receiverY, receiverZ, opacityLookup)
                : 1.0f;
        if (visibility <= MIN_VISIBILITY_CONFIDENCE) {
            return false;
        }

        float red = selectedLight.red() * attenuation;
        float green = selectedLight.green() * attenuation;
        float blue = selectedLight.blue() * attenuation;
        float compressionScale = radianceCompressionScale(red, green, blue);
        float contributionConfidence = contributionConfidence(
                selectedLight,
                distance,
                confidenceScale) * visibility;
        return addFace(rgb, confidence, confidenceWeight, baseRgbIndex, face,
                red * compressionScale,
                green * compressionScale,
                blue * compressionScale,
                weight,
                contributionConfidence);
    }

    private static float radianceCompressionScale(float red, float green, float blue) {
        float peakRadiance = Math.max(red, Math.max(green, blue));
        if (peakRadiance <= RADIANCE_COMPRESSION_EPSILON) {
            return 1.0f;
        }
        return (float) (3.0 * Math.log(peakRadiance / 3.0 + 1.0) / peakRadiance);
    }

    private static float contributionConfidence(
            SelectedLight selectedLight,
            float distance,
            float confidenceScale) {
        float normalizedDistance = Math.max(0.0f, Math.min(1.0f, distance / selectedLight.maxDistance()));
        float confidence = (1.0f - normalizedDistance * 0.35f) * confidenceScale;
        return Math.max(0.25f, Math.min(1.0f, confidence));
    }

    private static boolean sourceInsidePage(SelectedLight selectedLight, int originX, int originY, int originZ) {
        return selectedLight.sourceBlockX() >= originX && selectedLight.sourceBlockX() < originX + 16
                && selectedLight.sourceBlockY() >= originY && selectedLight.sourceBlockY() < originY + 16
                && selectedLight.sourceBlockZ() >= originZ && selectedLight.sourceBlockZ() < originZ + 16;
    }

    private static boolean addFace(
            float[] rgb,
            float[] confidence,
            float[] confidenceWeight,
            int baseRgbIndex,
            int face,
            float red,
            float green,
            float blue,
            float weight,
            float contributionConfidence) {
        if (weight <= 0.0001f) {
            return false;
        }
        int faceValueIndex = baseRgbIndex + face * 3;
        float redContribution = red * weight;
        float greenContribution = green * weight;
        float blueContribution = blue * weight;
        rgb[faceValueIndex] += redContribution;
        rgb[faceValueIndex + 1] += greenContribution;
        rgb[faceValueIndex + 2] += blueContribution;

        int confidenceIndex = baseRgbIndex / 3 + face;
        float sampleWeight = Math.max(
                redContribution + greenContribution + blueContribution,
                0.0001f);
        confidence[confidenceIndex] += contributionConfidence * sampleWeight;
        confidenceWeight[confidenceIndex] += sampleWeight;
        return true;
    }

    private static float probeVisibility(
            SelectedLight selectedLight,
            float probeX,
            float probeY,
            float probeZ,
            SectionOpacityLookup opacityLookup) {
        int sourceBlockX = selectedLight.sourceBlockX();
        int sourceBlockY = selectedLight.sourceBlockY();
        int sourceBlockZ = selectedLight.sourceBlockZ();

        int targetBlockX = fastFloor(probeX);
        int targetBlockY = fastFloor(probeY);
        int targetBlockZ = fastFloor(probeZ);
        if (isBlockingCell(targetBlockX, targetBlockY, targetBlockZ,
                sourceBlockX, sourceBlockY, sourceBlockZ, opacityLookup)) {
            return 0.0f;
        }
        if (!opacityLookup.hasOpaqueInBox(
                Math.min(sourceBlockX, targetBlockX),
                Math.min(sourceBlockY, targetBlockY),
                Math.min(sourceBlockZ, targetBlockZ),
                Math.max(sourceBlockX, targetBlockX),
                Math.max(sourceBlockY, targetBlockY),
                Math.max(sourceBlockZ, targetBlockZ))) {
            return 1.0f;
        }

        int blockX = sourceBlockX;
        int blockY = sourceBlockY;
        int blockZ = sourceBlockZ;

        float dx = probeX - selectedLight.x();
        float dy = probeY - selectedLight.y();
        float dz = probeZ - selectedLight.z();

        int stepX = dx > 0.0f ? 1 : dx < 0.0f ? -1 : 0;
        int stepY = dy > 0.0f ? 1 : dy < 0.0f ? -1 : 0;
        int stepZ = dz > 0.0f ? 1 : dz < 0.0f ? -1 : 0;

        float tMaxX = axisTMax(selectedLight.x(), dx, stepX);
        float tMaxY = axisTMax(selectedLight.y(), dy, stepY);
        float tMaxZ = axisTMax(selectedLight.z(), dz, stepZ);
        float tDeltaX = axisTDelta(dx, stepX);
        float tDeltaY = axisTDelta(dy, stepY);
        float tDeltaZ = axisTDelta(dz, stepZ);

        int maxSteps = Math.abs(targetBlockX - sourceBlockX)
                + Math.abs(targetBlockY - sourceBlockY)
                + Math.abs(targetBlockZ - sourceBlockZ)
                + 6;
        float visibility = 1.0f;
        for (int step = 0; step < maxSteps; step++) {
            if (blockX == targetBlockX && blockY == targetBlockY && blockZ == targetBlockZ) {
                return Math.max(0.0f, Math.min(1.0f, visibility));
            }

            int emptyMipLevel = opacityLookup.largestEmptyMipLevel(blockX, blockY, blockZ);
            if (emptyMipLevel > 0) {
                int cellSize = 1 << emptyMipLevel;
                int cellMinX = mipCellMin(blockX, cellSize);
                int cellMinY = mipCellMin(blockY, cellSize);
                int cellMinZ = mipCellMin(blockZ, cellSize);
                int cellMaxX = cellMinX + cellSize - 1;
                int cellMaxY = cellMinY + cellSize - 1;
                int cellMaxZ = cellMinZ + cellSize - 1;
                if (targetBlockX >= cellMinX && targetBlockX <= cellMaxX
                        && targetBlockY >= cellMinY && targetBlockY <= cellMaxY
                        && targetBlockZ >= cellMinZ && targetBlockZ <= cellMaxZ) {
                    return Math.max(0.0f, Math.min(1.0f, visibility));
                }

                int exitStepsX = mipExitSteps(blockX, cellMinX, cellMaxX, stepX);
                int exitStepsY = mipExitSteps(blockY, cellMinY, cellMaxY, stepY);
                int exitStepsZ = mipExitSteps(blockZ, cellMinZ, cellMaxZ, stepZ);
                float exitT = Math.min(
                        mipExitT(tMaxX, tDeltaX, exitStepsX),
                        Math.min(
                                mipExitT(tMaxY, tDeltaY, exitStepsY),
                                mipExitT(tMaxZ, tDeltaZ, exitStepsZ)));
                int advanceCountX = mipAdvanceCount(tMaxX, tDeltaX, exitT);
                int advanceCountY = mipAdvanceCount(tMaxY, tDeltaY, exitT);
                int advanceCountZ = mipAdvanceCount(tMaxZ, tDeltaZ, exitT);
                if (advanceCountX + advanceCountY + advanceCountZ > 1) {
                    if (advanceCountX > 0) {
                        blockX += advanceCountX * stepX;
                        tMaxX += advanceCountX * tDeltaX;
                    }
                    if (advanceCountY > 0) {
                        blockY += advanceCountY * stepY;
                        tMaxY += advanceCountY * tDeltaY;
                    }
                    if (advanceCountZ > 0) {
                        blockZ += advanceCountZ * stepZ;
                        tMaxZ += advanceCountZ * tDeltaZ;
                    }

                    if (isBlockingCell(blockX, blockY, blockZ,
                            sourceBlockX, sourceBlockY, sourceBlockZ, opacityLookup)) {
                        return 0.0f;
                    }
                    continue;
                }
            }

            float nextT = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            boolean advanceX = tMaxX <= nextT + VOXEL_TRAVERSAL_EPSILON;
            boolean advanceY = tMaxY <= nextT + VOXEL_TRAVERSAL_EPSILON;
            boolean advanceZ = tMaxZ <= nextT + VOXEL_TRAVERSAL_EPSILON;

            int previousX = blockX;
            int previousY = blockY;
            int previousZ = blockZ;

            if (advanceX) {
                blockX += stepX;
                tMaxX += tDeltaX;
            }
            if (advanceY) {
                blockY += stepY;
                tMaxY += tDeltaY;
            }
            if (advanceZ) {
                blockZ += stepZ;
                tMaxZ += tDeltaZ;
            }

            if (isBlockingCell(blockX, blockY, blockZ, sourceBlockX, sourceBlockY, sourceBlockZ, opacityLookup)) {
                return 0.0f;
            }

            if (hasGrazingBlockingCrossedCells(previousX, previousY, previousZ,
                    blockX, blockY, blockZ,
                    advanceX, advanceY, advanceZ,
                    sourceBlockX, sourceBlockY, sourceBlockZ,
                    opacityLookup)) {
                visibility *= GRAZING_VISIBILITY_CONFIDENCE;
                if (visibility <= MIN_TRAVERSAL_CONFIDENCE) {
                    return 0.0f;
                }
            }
        }

        return Math.max(0.0f, Math.min(1.0f, visibility));
    }

    private static boolean hasGrazingBlockingCrossedCells(
            int previousX,
            int previousY,
            int previousZ,
            int currentX,
            int currentY,
            int currentZ,
            boolean advanceX,
            boolean advanceY,
            boolean advanceZ,
            int sourceBlockX,
            int sourceBlockY,
            int sourceBlockZ,
            SectionOpacityLookup opacityLookup) {
        int xVariants = advanceX ? 2 : 1;
        int yVariants = advanceY ? 2 : 1;
        int zVariants = advanceZ ? 2 : 1;

        for (int xi = 0; xi < xVariants; xi++) {
            int x = xi == 0 ? previousX : currentX;
            for (int yi = 0; yi < yVariants; yi++) {
                int y = yi == 0 ? previousY : currentY;
                for (int zi = 0; zi < zVariants; zi++) {
                    int z = zi == 0 ? previousZ : currentZ;
                    if (x == previousX && y == previousY && z == previousZ) {
                        continue;
                    }
                    if (isBlockingCell(x, y, z, sourceBlockX, sourceBlockY, sourceBlockZ, opacityLookup)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean isBlockingCell(
            int blockX,
            int blockY,
            int blockZ,
            int sourceBlockX,
            int sourceBlockY,
            int sourceBlockZ,
            SectionOpacityLookup opacityLookup) {
        if (blockX == sourceBlockX && blockY == sourceBlockY && blockZ == sourceBlockZ) {
            return false;
        }
        return opacityLookup.isOpaque(blockX, blockY, blockZ);
    }

    private static float axisTMax(float origin, float delta, int step) {
        if (step == 0) {
            return Float.POSITIVE_INFINITY;
        }
        float boundary = step > 0 ? fastFloor(origin) + 1.0f : fastFloor(origin);
        return Math.max(0.0f, (boundary - origin) / delta);
    }

    private static float axisTDelta(float delta, int step) {
        if (step == 0) {
            return Float.POSITIVE_INFINITY;
        }
        return Math.abs(1.0f / delta);
    }

    private static int mipCellMin(int block, int cellSize) {
        return Math.floorDiv(block, cellSize) * cellSize;
    }

    private static int mipExitSteps(int block, int cellMin, int cellMax, int step) {
        if (step > 0) {
            return cellMax - block + 1;
        }
        if (step < 0) {
            return block - cellMin + 1;
        }
        return Integer.MAX_VALUE;
    }

    private static float mipExitT(float tMax, float tDelta, int exitSteps) {
        if (exitSteps == Integer.MAX_VALUE) {
            return Float.POSITIVE_INFINITY;
        }
        return tMax + (exitSteps - 1) * tDelta;
    }

    private static int mipAdvanceCount(float tMax, float tDelta, float exitT) {
        if (!Float.isFinite(tMax) || !Float.isFinite(tDelta) || tMax > exitT + VOXEL_TRAVERSAL_EPSILON) {
            return 0;
        }
        return Math.max(1, (int) Math.floor((exitT - tMax) / tDelta + VOXEL_TRAVERSAL_EPSILON) + 1);
    }

    private List<SelectedLight> collectSelectedLights(List<SectionLightTable> tables, int maxLights) {
        selectedLightQueue.clear();
        selectedLights.clear();
        if (tables == null || tables.isEmpty() || maxLights <= 0) {
            return selectedLights;
        }

        float pageCenterX = sectionPos.getMinX() + SECTION_SIZE * 0.5f;
        float pageCenterY = sectionPos.getMinY() + SECTION_SIZE * 0.5f;
        float pageCenterZ = sectionPos.getMinZ() + SECTION_SIZE * 0.5f;
        float probeMinX = probeCenter(sectionPos.getMinX(), 0) - FACE_RECEIVER_OFFSET;
        float probeMinY = probeCenter(sectionPos.getMinY(), 0) - FACE_RECEIVER_OFFSET;
        float probeMinZ = probeCenter(sectionPos.getMinZ(), 0) - FACE_RECEIVER_OFFSET;
        float probeMaxX = probeCenter(sectionPos.getMinX(), GRID_SIZE - 1) + FACE_RECEIVER_OFFSET;
        float probeMaxY = probeCenter(sectionPos.getMinY(), GRID_SIZE - 1) + FACE_RECEIVER_OFFSET;
        float probeMaxZ = probeCenter(sectionPos.getMinZ(), GRID_SIZE - 1) + FACE_RECEIVER_OFFSET;

        for (SectionLightTable table : tables) {
            if (table == null || !table.hasLights()) {
                continue;
            }

            int originX = table.sectionPos().getMinX();
            int originY = table.sectionPos().getMinY();
            int originZ = table.sectionPos().getMinZ();
            for (SectionLight light : table.lights()) {
                int localPos = light.packedBlockPos();
                int localX = localPos & 15;
                int localY = (localPos >> 4) & 15;
                int localZ = (localPos >> 8) & 15;
                float lightX = originX + localX + 0.5f;
                float lightY = originY + localY + 0.5f;
                float lightZ = originZ + localZ + 0.5f;
                int packed = light.packedRgbEmission();
                float normalizedEmission = ((packed >>> 24) & 0xFF) * INV_15;
                if (normalizedEmission <= 0.0f) {
                    continue;
                }
                float baseRed = ((packed >> 16) & 0xFF) * INV_255;
                float baseGreen = ((packed >> 8) & 0xFF) * INV_255;
                float baseBlue = (packed & 0xFF) * INV_255;
                float luma = baseRed * 0.2126f + baseGreen * 0.7152f + baseBlue * 0.0722f;
                if (luma <= 0.0f) {
                    continue;
                }
                float lightRadius = lightRadius(light);
                float maxDistance = lightMaxDistance(lightRadius);
                float maxDistanceSquared = maxDistance * maxDistance;
                float nearestProbeDistanceSquared = squaredDistanceOutsideBox(
                        lightX, lightY, lightZ,
                        probeMinX, probeMinY, probeMinZ,
                        probeMaxX, probeMaxY, probeMaxZ);
                if (nearestProbeDistanceSquared > maxDistanceSquared) {
                    continue;
                }
                float centerDx = lightX - pageCenterX;
                float centerDy = lightY - pageCenterY;
                float centerDz = lightZ - pageCenterZ;
                float centerDistanceSquared = centerDx * centerDx + centerDy * centerDy + centerDz * centerDz;
                float score = lightScore(
                        normalizedEmission,
                        luma,
                        lightRadius,
                        maxDistance,
                        nearestProbeDistanceSquared,
                        centerDistanceSquared);
                if (score <= 0.0f) {
                    continue;
                }

                if (selectedLightQueue.size() >= maxLights) {
                    if (score <= selectedLightQueue.peek().score()) {
                        continue;
                    }
                    selectedLightQueue.poll();
                }

                float selectedEmission = Math.min(1.0f, normalizedEmission);
                selectedLightQueue.add(selectedLight(
                        lightX, lightY, lightZ,
                        originX + localX, originY + localY, originZ + localZ,
                        maxDistance,
                        baseRed * selectedEmission,
                        baseGreen * selectedEmission,
                        baseBlue * selectedEmission,
                        score));
            }
        }

        if (selectedLightQueue.isEmpty()) {
            return selectedLights;
        }

        selectedLights.addAll(selectedLightQueue);
        selectedLights.sort((left, right) -> Float.compare(right.score(), left.score()));
        selectedLightQueue.clear();
        return selectedLights;
    }

    private static SelectedLight selectedLight(
            float lightX,
            float lightY,
            float lightZ,
            int sourceBlockX,
            int sourceBlockY,
            int sourceBlockZ,
            float maxDistance,
            float red,
            float green,
            float blue,
            float score) {
        return new SelectedLight(
                lightX,
                lightY,
                lightZ,
                sourceBlockX,
                sourceBlockY,
                sourceBlockZ,
                maxDistance,
                maxDistance * maxDistance,
                red,
                green,
                blue,
                score);
    }

    private static float lightScore(
            float normalizedEmission,
            float luma,
            float radius,
            float maxDistance,
            float nearestProbeDistanceSquared,
            float centerDistanceSquared) {
        float nearestDistance = (float) Math.sqrt(nearestProbeDistanceSquared);
        float bestProbeAttenuation = attenuationAtDistance(nearestDistance, maxDistance);
        float centerStability = 1.0f / (1.0f + centerDistanceSquared * LIGHT_SELECTION_CENTER_BIAS);
        return normalizedEmission * luma * (1.0f + radius * 0.08f)
                * bestProbeAttenuation * centerStability;
    }

    private static float lightRadius(SectionLight light) {
        return Math.max(Short.toUnsignedInt(light.radius()), 1.0f);
    }

    private static float lightMaxDistance(float radius) {
        return Math.min(
                BLOCKLIGHT_CASCADE_MAX_DISTANCE,
                Math.max(radius + 1.0f, radius * BLOCKLIGHT_CASCADE_DISTANCE_SCALE));
    }

    private static float attenuationAtDistance(float distance, float maxDistance) {
        float attenuation = reverseSmoothstep(distance / maxDistance);
        return attenuation * attenuation;
    }

    private static float squaredDistanceOutsideBox(
            float lightX,
            float lightY,
            float lightZ,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ) {
        float dx = distanceOutsideAxis(lightX, minX, maxX);
        float dy = distanceOutsideAxis(lightY, minY, maxY);
        float dz = distanceOutsideAxis(lightZ, minZ, maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static float distanceOutsideAxis(float value, float min, float max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0f;
    }

    private static int fastFloor(float value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static int probeCoordMin(float center, int origin, float radius) {
        return (int) Math.ceil(((center - origin - radius) / PROBE_SPACING) - 0.5f);
    }

    private static int probeCoordMax(float center, int origin, float radius) {
        return (int) Math.floor(((center - origin + radius) / PROBE_SPACING) - 0.5f);
    }

    private static float probeCenter(int origin, int coord) {
        return origin + (coord + 0.5f) * PROBE_SPACING;
    }

    private static int probeIndex(int x, int y, int z) {
        return (z * GRID_SIZE + y) * GRID_SIZE + x;
    }

    private static float reverseSmoothstep(float normalizedDistance) {
        float t = Math.max(0.0f, Math.min(1.0f, 1.0f - normalizedDistance));
        return t * t * (3.0f - 2.0f * t);
    }

    private boolean packFaceConfidenceWords() {
        boolean changed = false;
        for (int wordIndex = 0; wordIndex < PACKED_CONFIDENCE_WORD_COUNT; wordIndex++) {
            int packedWord = 0;
            int baseFaceIndex = wordIndex * CONFIDENCE_VALUES_PER_WORD;
            for (int lane = 0; lane < CONFIDENCE_VALUES_PER_WORD; lane++) {
                int faceIndex = baseFaceIndex + lane;
                if (faceIndex >= FACE_VALUE_COUNT || packedFaceRgb9E5[faceIndex] == 0) {
                    continue;
                }
                packedWord |= packConfidenceByte(faceConfidence[faceIndex]) << (lane * Byte.SIZE);
            }
            if (packedFaceConfidence[wordIndex] != packedWord) {
                packedFaceConfidence[wordIndex] = packedWord;
                changed = true;
            }
        }
        return changed;
    }

    private void normalizeFaceConfidence() {
        for (int i = 0; i < FACE_VALUE_COUNT; i++) {
            if (faceConfidenceWeight[i] > 0.0f) {
                faceConfidence[i] = Math.max(0.0f, Math.min(1.0f,
                        faceConfidence[i] / faceConfidenceWeight[i]));
            } else {
                faceConfidence[i] = 0.0f;
            }
        }
    }

    private void applyTemporalHistory() {
        if (!hasTemporalHistory) {
            System.arraycopy(faceRgb, 0, historyFaceRgb, 0, FACE_RGB_VALUE_COUNT);
            System.arraycopy(faceConfidence, 0, historyFaceConfidence, 0, FACE_VALUE_COUNT);
            hasTemporalHistory = true;
            return;
        }

        for (int faceIndex = 0; faceIndex < FACE_VALUE_COUNT; faceIndex++) {
            int rgbIndex = faceIndex * 3;
            float historyWeight = temporalHistoryWeight(
                    faceRgb[rgbIndex], faceRgb[rgbIndex + 1], faceRgb[rgbIndex + 2],
                    historyFaceRgb[rgbIndex], historyFaceRgb[rgbIndex + 1], historyFaceRgb[rgbIndex + 2],
                    faceConfidence[faceIndex], historyFaceConfidence[faceIndex]);
            float estimateWeight = 1.0f - historyWeight;

            faceRgb[rgbIndex] = historyFaceRgb[rgbIndex] * historyWeight + faceRgb[rgbIndex] * estimateWeight;
            faceRgb[rgbIndex + 1] = historyFaceRgb[rgbIndex + 1] * historyWeight
                    + faceRgb[rgbIndex + 1] * estimateWeight;
            faceRgb[rgbIndex + 2] = historyFaceRgb[rgbIndex + 2] * historyWeight
                    + faceRgb[rgbIndex + 2] * estimateWeight;
            faceConfidence[faceIndex] = historyFaceConfidence[faceIndex] * historyWeight
                    + faceConfidence[faceIndex] * estimateWeight;
        }

        System.arraycopy(faceRgb, 0, historyFaceRgb, 0, FACE_RGB_VALUE_COUNT);
        System.arraycopy(faceConfidence, 0, historyFaceConfidence, 0, FACE_VALUE_COUNT);
    }

    private static float temporalHistoryWeight(
            float red,
            float green,
            float blue,
            float historyRed,
            float historyGreen,
            float historyBlue,
            float confidence,
            float historyConfidence) {
        float estimateEnergy = red + green + blue;
        float historyEnergy = historyRed + historyGreen + historyBlue;
        if (historyEnergy <= TEMPORAL_EPSILON || historyConfidence <= 0.0f) {
            return 0.0f;
        }
        if (estimateEnergy <= TEMPORAL_EPSILON || confidence <= 0.0f) {
            return TEMPORAL_MISSING_ESTIMATE_WEIGHT;
        }

        float relativeDelta = (Math.abs(red - historyRed)
                + Math.abs(green - historyGreen)
                + Math.abs(blue - historyBlue))
                / Math.max(Math.max(estimateEnergy, historyEnergy), TEMPORAL_EPSILON);
        if (relativeDelta >= TEMPORAL_RESET_DELTA) {
            return TEMPORAL_MIN_HISTORY_WEIGHT;
        }

        float confidenceStability = Math.max(0.0f, Math.min(1.0f, Math.min(confidence, historyConfidence)));
        float deltaStability = 1.0f - Math.max(0.0f, Math.min(1.0f, relativeDelta / TEMPORAL_RESET_DELTA));
        float stableWeight = 0.45f + 0.39f * confidenceStability;
        return Math.max(TEMPORAL_MIN_HISTORY_WEIGHT,
                Math.min(TEMPORAL_MAX_HISTORY_WEIGHT, stableWeight * (0.35f + 0.65f * deltaStability)));
    }

    private static int packConfidenceByte(float confidence) {
        return Math.max(1, Math.min(255, Math.round(confidence * 255.0f)));
    }

    private static int packRgb9E5(float red, float green, float blue) {
        red = clampHdr(red);
        green = clampHdr(green);
        blue = clampHdr(blue);
        float maxChannel = Math.max(red, Math.max(green, blue));
        if (maxChannel <= 0.0f) {
            return 0;
        }

        int exponent = Math.max(0, Math.min(RGB9E5_EXPONENT_MAX,
                (int) Math.ceil(log2(maxChannel / RGB9E5_MANTISSA_MAX))
                        + RGB9E5_EXPONENT_BIAS + RGB9E5_MANTISSA_BITS));
        int r;
        int g;
        int b;
        while (true) {
            float scale = Math.scalb(1.0f, exponent - RGB9E5_EXPONENT_BIAS - RGB9E5_MANTISSA_BITS);
            r = Math.round(red / scale);
            g = Math.round(green / scale);
            b = Math.round(blue / scale);
            if ((r <= RGB9E5_MANTISSA_MAX && g <= RGB9E5_MANTISSA_MAX && b <= RGB9E5_MANTISSA_MAX)
                    || exponent >= RGB9E5_EXPONENT_MAX) {
                break;
            }
            exponent++;
        }
        r = clampMantissa(r);
        g = clampMantissa(g);
        b = clampMantissa(b);
        if ((r | g | b) == 0) {
            return 0;
        }
        return r | (g << RGB9E5_MANTISSA_BITS) | (b << (RGB9E5_MANTISSA_BITS * 2))
                | (exponent << RGB9E5_EXPONENT_SHIFT);
    }

    private static float clampHdr(float value) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            return 0.0f;
        }
        return Math.min(value, RGB9E5_MAX_VALUE);
    }

    private static int clampMantissa(int value) {
        return Math.max(0, Math.min(RGB9E5_MANTISSA_MAX, value));
    }

    private static double log2(float value) {
        return Math.log(value) / Math.log(2.0);
    }

    private record SelectedLight(
            float x,
            float y,
            float z,
            int sourceBlockX,
            int sourceBlockY,
            int sourceBlockZ,
            float maxDistance,
            float maxDistanceSquared,
            float red,
            float green,
            float blue,
            float score) {
    }

    private static final class SectionOpacityLookup {
        private static final int LOOKUP_RADIUS = 2;
        private static final int LOOKUP_DIAMETER = LOOKUP_RADIUS * 2 + 1;
        private static final int LOCAL_BLOCK_MASK = 15;
        private final SectionLightTable[] tables = new SectionLightTable[LOOKUP_DIAMETER * LOOKUP_DIAMETER * LOOKUP_DIAMETER];
        private final boolean hasOpaqueBlocks;
        private final int centerSectionX;
        private final int centerSectionY;
        private final int centerSectionZ;

        private SectionOpacityLookup(ChunkSectionPos center, List<SectionLightTable> sourceTables) {
            this.centerSectionX = center.getSectionX();
            this.centerSectionY = center.getSectionY();
            this.centerSectionZ = center.getSectionZ();

            boolean anyOpaqueBlocks = false;
            for (SectionLightTable table : sourceTables) {
                if (table == null || !table.hasOpaqueBlocks()) {
                    continue;
                }

                int dx = table.sectionPos().getSectionX() - centerSectionX;
                int dy = table.sectionPos().getSectionY() - centerSectionY;
                int dz = table.sectionPos().getSectionZ() - centerSectionZ;
                if (dx < -LOOKUP_RADIUS || dx > LOOKUP_RADIUS
                        || dy < -LOOKUP_RADIUS || dy > LOOKUP_RADIUS
                        || dz < -LOOKUP_RADIUS || dz > LOOKUP_RADIUS) {
                    continue;
                }

                tables[lookupIndex(dx, dy, dz)] = table;
                anyOpaqueBlocks = true;
            }
            this.hasOpaqueBlocks = anyOpaqueBlocks;
        }

        private boolean hasOpaqueBlocks() {
            return hasOpaqueBlocks;
        }

        private boolean isOpaque(int blockX, int blockY, int blockZ) {
            int sectionX = blockX >> 4;
            int sectionY = blockY >> 4;
            int sectionZ = blockZ >> 4;
            int dx = sectionX - centerSectionX;
            int dy = sectionY - centerSectionY;
            int dz = sectionZ - centerSectionZ;
            if (dx < -LOOKUP_RADIUS || dx > LOOKUP_RADIUS
                    || dy < -LOOKUP_RADIUS || dy > LOOKUP_RADIUS
                    || dz < -LOOKUP_RADIUS || dz > LOOKUP_RADIUS) {
                return false;
            }

            SectionLightTable table = tables[lookupIndex(dx, dy, dz)];
            if (table == null) {
                return false;
            }
            int localX = blockX & LOCAL_BLOCK_MASK;
            int localY = blockY & LOCAL_BLOCK_MASK;
            int localZ = blockZ & LOCAL_BLOCK_MASK;
            int index = ((localY * 16) + localZ) * 16 + localX;
            long[] opaqueBlocks = table.opaqueBlocks();
            return (opaqueBlocks[index >>> 6] & (1L << (index & 63))) != 0L;
        }

        private boolean hasOpaqueInBox(int minBlockX, int minBlockY, int minBlockZ,
                int maxBlockX, int maxBlockY, int maxBlockZ) {
            if (!hasOpaqueBlocks) {
                return false;
            }

            int minSectionX = minBlockX >> 4;
            int minSectionY = minBlockY >> 4;
            int minSectionZ = minBlockZ >> 4;
            int maxSectionX = maxBlockX >> 4;
            int maxSectionY = maxBlockY >> 4;
            int maxSectionZ = maxBlockZ >> 4;
            for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                int dz = sectionZ - centerSectionZ;
                if (dz < -LOOKUP_RADIUS || dz > LOOKUP_RADIUS) {
                    continue;
                }
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    int dy = sectionY - centerSectionY;
                    if (dy < -LOOKUP_RADIUS || dy > LOOKUP_RADIUS) {
                        continue;
                    }
                    for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                        int dx = sectionX - centerSectionX;
                        if (dx < -LOOKUP_RADIUS || dx > LOOKUP_RADIUS) {
                            continue;
                        }

                        SectionLightTable table = tables[lookupIndex(dx, dy, dz)];
                        if (table == null || !table.hasOpaqueBlocks()) {
                            continue;
                        }

                        int localMinX = sectionX == minSectionX ? minBlockX & LOCAL_BLOCK_MASK : 0;
                        int localMinY = sectionY == minSectionY ? minBlockY & LOCAL_BLOCK_MASK : 0;
                        int localMinZ = sectionZ == minSectionZ ? minBlockZ & LOCAL_BLOCK_MASK : 0;
                        int localMaxX = sectionX == maxSectionX ? maxBlockX & LOCAL_BLOCK_MASK : LOCAL_BLOCK_MASK;
                        int localMaxY = sectionY == maxSectionY ? maxBlockY & LOCAL_BLOCK_MASK : LOCAL_BLOCK_MASK;
                        int localMaxZ = sectionZ == maxSectionZ ? maxBlockZ & LOCAL_BLOCK_MASK : LOCAL_BLOCK_MASK;
                        if (table.hasOpaqueInBox(localMinX, localMinY, localMinZ,
                                localMaxX, localMaxY, localMaxZ)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private int largestEmptyMipLevel(int blockX, int blockY, int blockZ) {
            if (!hasOpaqueBlocks) {
                return 4;
            }

            int sectionX = blockX >> 4;
            int sectionY = blockY >> 4;
            int sectionZ = blockZ >> 4;
            int dx = sectionX - centerSectionX;
            int dy = sectionY - centerSectionY;
            int dz = sectionZ - centerSectionZ;
            if (dx < -LOOKUP_RADIUS || dx > LOOKUP_RADIUS
                    || dy < -LOOKUP_RADIUS || dy > LOOKUP_RADIUS
                    || dz < -LOOKUP_RADIUS || dz > LOOKUP_RADIUS) {
                return 4;
            }

            SectionLightTable table = tables[lookupIndex(dx, dy, dz)];
            if (table == null || !table.hasOpaqueBlocks()) {
                return 4;
            }
            return table.largestEmptyMipLevel(
                    blockX & LOCAL_BLOCK_MASK,
                    blockY & LOCAL_BLOCK_MASK,
                    blockZ & LOCAL_BLOCK_MASK);
        }

        private static int lookupIndex(int dx, int dy, int dz) {
            return ((dz + LOOKUP_RADIUS) * LOOKUP_DIAMETER + (dy + LOOKUP_RADIUS))
                    * LOOKUP_DIAMETER + (dx + LOOKUP_RADIUS);
        }
    }
}

package me.cortex.vulkanite.client.lighting;

import me.cortex.vulkanite.compat.ISectionLightBuildResult;
import me.cortex.vulkanite.compat.SectionLight;
import me.cortex.vulkanite.compat.SectionLightTable;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.minecraft.util.math.ChunkSectionPos;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

public final class SectionLightManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SectionLightManager.class);
    private static final long INFO_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final int HEADER_BYTES = 16;
    private static final int RECORD_BYTES = 32;
    private static final int MAX_GPU_LIGHTS = 32768;
    private static final int MAX_GPU_PROBE_PAGES = 1024;
    private static final int MAX_PROBE_REGENERATIONS_PER_UPDATE = 8;
    private static final int MAX_PROBE_REGENERATIONS_PER_UPLOAD = 4;
    private static final int MAX_LIGHTS_PER_PROBE_PAGE = 48;
    private static final int PROBE_HASH_LOOKUP_LIMIT = 64;
    private static final int PROBE_RECORD_BYTES = 16;
    private static final int PROBE_HEADER_BYTES = PROBE_RECORD_BYTES;
    private static final int PROBE_DIRECTORY_BYTES = MAX_GPU_PROBE_PAGES * PROBE_RECORD_BYTES;
    private static final int PROBE_PAGE_BYTES = SectionDirectionalProbePage.PACKED_RECORD_COUNT * PROBE_RECORD_BYTES;
    private static final long PROBE_DATA_OFFSET_BYTES = (long) PROBE_HEADER_BYTES + PROBE_DIRECTORY_BYTES;
    private static final long PROBE_BUFFER_BYTES = PROBE_DATA_OFFSET_BYTES
            + (long) MAX_GPU_PROBE_PAGES * PROBE_PAGE_BYTES;

    private final Set<ChunkSectionPos> activeSectionPositions = new HashSet<>();
    private final Map<ChunkSectionPos, SectionLightTable> activeTables = new HashMap<>();
    private final Map<ChunkSectionPos, SectionDirectionalProbePage> activeProbePages = new HashMap<>();
    private final SectionDirectionalProbePage[] probeSlots = new SectionDirectionalProbePage[MAX_GPU_PROBE_PAGES];
    private final boolean[] dirtyProbeSlots = new boolean[MAX_GPU_PROBE_PAGES];
    private final ArrayDeque<Integer> dirtyProbeSlotQueue = new ArrayDeque<>();
    private final ArrayDeque<ChunkSectionPos> dirtyProbePageQueue = new ArrayDeque<>();
    private final Set<ChunkSectionPos> queuedDirtyProbePages = new HashSet<>();
    private final ArrayDeque<Integer> freeProbeSlots = new ArrayDeque<>();
    private List<SectionLightTable> sortedTableCache;
    private int activeLightCount;
    private long lastInfoLogNanos;
    private long lastProbeInfoLogNanos;
    private long lastProbeCapacityWarnNanos;
    private VRef<VBuffer> gpuBuffer;
    private int gpuBufferCapacityBytes;
    private int gpuVersion;
    private boolean gpuDirty = true;
    private VRef<VBuffer> probeGpuBuffer;
    private int probeSlotLimit;
    private int dirtyProbePageCount;
    private int neighborInfluenceDirtyMarks;
    private int probeGpuVersion;
    private boolean probeHeaderDirty = true;
    private boolean probeDirectoryDirty = true;
    private long lastTableUploadLogNanos;
    private long lastProbeDirectoryWarnNanos;
    private ByteBuffer tableUploadScratch;
    private ByteBuffer probeHeaderScratch;
    private ByteBuffer probeDirectoryScratch;
    private ByteBuffer probePageScratch;

    public synchronized void updateFromBuildResults(List<ChunkBuildOutput> results) {
        int processedSections = 0;
        int changedTables = 0;
        int batchLights = 0;
        int queuedProbePages = 0;
        int neighborQueuedProbePages = 0;

        for (ChunkBuildOutput result : results) {
            if (!(result instanceof ISectionLightBuildResult lightResult)) {
                continue;
            }

            ChunkSectionPos sectionPos = result.render.getPosition();
            boolean sectionBecameActive = activeSectionPositions.add(sectionPos);
            SectionLightTable table = lightResult.getSectionLights();
            if (table != null && table.isEmpty()) {
                table = null;
            }

            SectionLightTable previous = activeTables.get(sectionPos);
            boolean tableChanged = previous == null ? table != null : !previous.equals(table);

            if (table != null) {
                batchLights += table.size();
            }

            if (tableChanged) {
                if (previous != null) {
                    activeLightCount -= previous.size();
                }
                if (table != null) {
                    activeTables.put(sectionPos, table);
                    activeLightCount += table.size();
                } else {
                    activeTables.remove(sectionPos);
                }

                DirtyProbeMarks marks = markProbePageAndNeighborsDirty(sectionPos);
                queuedProbePages += marks.total();
                neighborQueuedProbePages += marks.neighbor();
                changedTables++;
            } else if (sectionBecameActive && queueProbePageRegeneration(sectionPos, false)) {
                queuedProbePages++;
            }
            processedSections++;
        }

        if (processedSections == 0) {
            return;
        }
        if (changedTables > 0) {
            markGpuDirty();
        }
        int prebakedProbePages = processDirtyProbePages(MAX_PROBE_REGENERATIONS_PER_UPDATE);

        long now = System.nanoTime();
        if (batchLights > 0 || now - lastInfoLogNanos >= INFO_LOG_INTERVAL_NANOS) {
            lastInfoLogNanos = now;
            LOGGER.info("[Vulkanite] Section lights: updated {} sections, {} light-table changes, {} lights in batch, {} active sections, {} active lights, {} active probe pages, {} queued probe pages ({} neighbor), prebaked probe pages={}",
                    processedSections, changedTables, batchLights, activeTables.size(), activeLightCount,
                    activeProbePages.size(), queuedProbePages, neighborQueuedProbePages, prebakedProbePages);
        } else {
            LOGGER.debug("[Vulkanite] Section lights: updated {} sections, {} light-table changes, {} active sections, {} active lights, {} active probe pages, {} queued probe pages ({} neighbor), prebaked probe pages={}",
                    processedSections, changedTables, activeTables.size(), activeLightCount,
                    activeProbePages.size(), queuedProbePages, neighborQueuedProbePages, prebakedProbePages);
        }
    }

    public synchronized void removeSection(RenderSection section) {
        if (section == null) {
            return;
        }
        ChunkSectionPos sectionPos = section.getPosition();
        activeSectionPositions.remove(sectionPos);
        removeQueuedProbePage(sectionPos);

        SectionLightTable removed = activeTables.remove(sectionPos);
        boolean removedProbePage = removeProbePage(sectionPos);
        if (removed != null) {
            activeLightCount -= removed.size();
            markGpuDirty();
            DirtyProbeMarks marks = markProbePageAndNeighborsDirty(sectionPos);
            int prebakedProbePages = processDirtyProbePages(MAX_PROBE_REGENERATIONS_PER_UPDATE);
            LOGGER.debug("[Vulkanite] Removed section light table for {}; queued {} neighbor probe pages, prebaked {} probe pages",
                    sectionPos, marks.neighbor(), prebakedProbePages);
        }
        if (removedProbePage) {
            LOGGER.debug("[Vulkanite] Removed section light probe page for {}", sectionPos);
        }
    }

    public synchronized VRef<VBuffer> ensureGpuBuffer(VContext ctx, VCmdBuff cmd) {
        int lightCount = Math.min(activeLightCount, MAX_GPU_LIGHTS);
        int uploadBytes = HEADER_BYTES + lightCount * RECORD_BYTES;
        ensureCapacity(ctx, uploadBytes);

        if (!gpuDirty) {
            return gpuBuffer.addRef();
        }

        long uploadStartNanos = System.nanoTime();
        ByteBuffer data = tableUploadScratch(uploadBytes);
        data.putInt(lightCount);
        data.putInt(activeTables.size());
        data.putInt(gpuVersion);
        data.putInt(0);

        int written = 0;
        for (SectionLightTable table : sortedTables()) {
            int originX = table.sectionPos().getMinX();
            int originY = table.sectionPos().getMinY();
            int originZ = table.sectionPos().getMinZ();
            for (SectionLight light : table.lights()) {
                if (written >= lightCount) {
                    break;
                }
                int packedRgbEmission = light.packedRgbEmission();
                int localPos = light.packedBlockPos();
                data.putInt(originX + (localPos & 15));
                data.putInt(originY + ((localPos >> 4) & 15));
                data.putInt(originZ + ((localPos >> 8) & 15));
                data.putInt((Short.toUnsignedInt(light.flags()) << 16)
                        | Short.toUnsignedInt(light.radius()));
                data.putInt((packedRgbEmission >> 16) & 0xFF);
                data.putInt((packedRgbEmission >> 8) & 0xFF);
                data.putInt(packedRgbEmission & 0xFF);
                data.putInt((packedRgbEmission >>> 24) & 0xFF);
                written++;
            }
            if (written >= lightCount) {
                break;
            }
        }
        data.flip();

        cmd.encodeDataUpload(ctx.memory, MemoryUtil.memAddress(data), gpuBuffer, 0, uploadBytes);
        cmd.encodeBufferBarrier(gpuBuffer, 0, uploadBytes,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
        gpuDirty = false;
        logSectionLightUpload(lightCount, uploadBytes, System.nanoTime() - uploadStartNanos);
        if (activeLightCount > MAX_GPU_LIGHTS) {
            LOGGER.warn("[Vulkanite] Section light GPU upload truncated: {} active lights, {} uploaded",
                    activeLightCount, MAX_GPU_LIGHTS);
        }

        return gpuBuffer.addRef();
    }

    public synchronized VRef<VBuffer> ensureProbeGpuBuffer(VContext ctx, VCmdBuff cmd) {
        ensureProbeCapacity(ctx);

        long totalStartNanos = System.nanoTime();
        int queuedAtStart = dirtyProbePageQueue.size();
        int neighborMarksAtStart = neighborInfluenceDirtyMarks;
        long regenerationStartNanos = System.nanoTime();
        int regeneratedProbePages = processDirtyProbePages(MAX_PROBE_REGENERATIONS_PER_UPLOAD);
        long regenerationNanos = System.nanoTime() - regenerationStartNanos;
        int queuedAfterRegeneration = dirtyProbePageQueue.size();
        int dirtyAtStart = dirtyProbePageCount;
        long uploadedBytes = 0L;
        long uploadStartNanos = System.nanoTime();
        if (probeHeaderDirty) {
            uploadedBytes += uploadProbeHeader(ctx, cmd);
            probeHeaderDirty = false;
        }
        if (probeDirectoryDirty) {
            uploadedBytes += uploadProbeDirectory(ctx, cmd);
            probeDirectoryDirty = false;
        }

        while (!dirtyProbeSlotQueue.isEmpty()) {
            int slot = dirtyProbeSlotQueue.removeFirst();
            if (!dirtyProbeSlots[slot]) {
                continue;
            }

            SectionDirectionalProbePage page = probeSlots[slot];
            if (page != null) {
                uploadedBytes += uploadProbePage(ctx, cmd, slot, page);
            }
            dirtyProbeSlots[slot] = false;
        }
        dirtyProbePageCount = 0;

        if (uploadedBytes > 0L) {
            cmd.encodeBufferBarrier(probeGpuBuffer, 0, PROBE_BUFFER_BYTES,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
            long uploadNanos = System.nanoTime() - uploadStartNanos;
            logProbeUpload(dirtyAtStart, queuedAtStart, queuedAfterRegeneration,
                    regeneratedProbePages, neighborMarksAtStart, uploadedBytes,
                    regenerationNanos, uploadNanos, System.nanoTime() - totalStartNanos);
            neighborInfluenceDirtyMarks = 0;
        } else if (queuedAfterRegeneration == 0) {
            neighborInfluenceDirtyMarks = 0;
        }

        return probeGpuBuffer.addRef();
    }

    public synchronized int activeSectionCount() {
        return activeTables.size();
    }

    public synchronized int activeLightCount() {
        return activeLightCount;
    }

    public synchronized void clear() {
        activeSectionPositions.clear();
        activeTables.clear();
        activeProbePages.clear();
        Arrays.fill(probeSlots, null);
        Arrays.fill(dirtyProbeSlots, false);
        dirtyProbeSlotQueue.clear();
        dirtyProbePageQueue.clear();
        queuedDirtyProbePages.clear();
        freeProbeSlots.clear();
        sortedTableCache = null;
        activeLightCount = 0;
        gpuDirty = true;
        probeSlotLimit = 0;
        dirtyProbePageCount = 0;
        neighborInfluenceDirtyMarks = 0;
        probeHeaderDirty = true;
        probeDirectoryDirty = true;
        lastTableUploadLogNanos = 0L;
        if (gpuBuffer != null) {
            gpuBuffer.close();
            gpuBuffer = null;
        }
        if (probeGpuBuffer != null) {
            probeGpuBuffer.close();
            probeGpuBuffer = null;
        }
        gpuBufferCapacityBytes = 0;
        freeUploadScratchBuffers();
    }

    private void ensureCapacity(VContext ctx, int requiredBytes) {
        if (gpuBuffer != null && gpuBufferCapacityBytes >= requiredBytes) {
            return;
        }

        if (gpuBuffer != null) {
            gpuBuffer.close();
        }

        gpuBufferCapacityBytes = roundUpPowerOfTwo(Math.max(requiredBytes, 4096));
        gpuBuffer = ctx.memory.createBuffer(
                gpuBufferCapacityBytes,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        gpuBuffer.get().setDebugUtilsObjectName("Section light table");
        gpuDirty = true;
    }

    private void ensureProbeCapacity(VContext ctx) {
        if (probeGpuBuffer != null) {
            return;
        }

        probeGpuBuffer = ctx.memory.createBuffer(
                PROBE_BUFFER_BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        probeGpuBuffer.get().setDebugUtilsObjectName("Section directional light probes");
        probeHeaderDirty = true;
        probeDirectoryDirty = true;
        for (SectionDirectionalProbePage page : activeProbePages.values()) {
            markProbeSlotDirty(page.slot());
        }
    }

    private int processDirtyProbePages(int maxPages) {
        int regenerated = 0;
        int processed = 0;
        while (processed < maxPages && !dirtyProbePageQueue.isEmpty()) {
            ChunkSectionPos sectionPos = dirtyProbePageQueue.removeFirst();
            queuedDirtyProbePages.remove(sectionPos);
            processed++;

            if (regenerateProbePage(sectionPos)) {
                regenerated++;
            }
        }
        return regenerated;
    }

    private boolean regenerateProbePage(ChunkSectionPos sectionPos) {
        if (!activeSectionPositions.contains(sectionPos)) {
            return removeProbePage(sectionPos);
        }

        List<SectionLightTable> neighborTables = gatherNeighborLightTables(sectionPos);
        if (!hasAnyLights(neighborTables)) {
            return removeProbePage(sectionPos);
        }

        SectionDirectionalProbePage page = activeProbePages.get(sectionPos);
        boolean newPage = false;
        if (page == null) {
            int slot = allocateProbeSlot(sectionPos);
            if (slot < 0) {
                return false;
            }
            page = new SectionDirectionalProbePage(sectionPos);
            page.setSlot(slot);
            activeProbePages.put(sectionPos, page);
            probeSlots[slot] = page;
            newPage = true;
            probeHeaderDirty = true;
            probeDirectoryDirty = true;
        }

        boolean pageChanged = page.regenerateFrom(neighborTables, MAX_LIGHTS_PER_PROBE_PAGE);
        if (newPage || pageChanged) {
            markProbeSlotDirty(page.slot());
        }
        return newPage || pageChanged;
    }

    private List<SectionLightTable> gatherNeighborLightTables(ChunkSectionPos sectionPos) {
        ArrayList<SectionLightTable> tables = new ArrayList<>(27);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    SectionLightTable table = activeTables.get(offsetSection(sectionPos, dx, dy, dz));
                    if (table != null && !table.isEmpty()) {
                        tables.add(table);
                    }
                }
            }
        }
        return tables;
    }

    private static boolean hasAnyLights(List<SectionLightTable> tables) {
        for (SectionLightTable table : tables) {
            if (table != null && table.hasLights()) {
                return true;
            }
        }
        return false;
    }

    private boolean removeProbePage(ChunkSectionPos sectionPos) {
        SectionDirectionalProbePage page = activeProbePages.remove(sectionPos);
        if (page == null) {
            return false;
        }

        int slot = page.slot();
        if (slot >= 0 && slot < probeSlots.length) {
            probeSlots[slot] = null;
            freeProbeSlots.addLast(slot);
            markProbeSlotDirty(slot);
        }
        page.setSlot(-1);
        probeHeaderDirty = true;
        probeDirectoryDirty = true;
        return true;
    }

    private int allocateProbeSlot(ChunkSectionPos sectionPos) {
        if (!freeProbeSlots.isEmpty()) {
            return freeProbeSlots.removeFirst();
        }
        if (probeSlotLimit < MAX_GPU_PROBE_PAGES) {
            return probeSlotLimit++;
        }

        long now = System.nanoTime();
        if (now - lastProbeCapacityWarnNanos >= INFO_LOG_INTERVAL_NANOS) {
            lastProbeCapacityWarnNanos = now;
            LOGGER.warn("[Vulkanite] Section light probe page capacity reached ({} pages); keeping table fallback for {}",
                    MAX_GPU_PROBE_PAGES, sectionPos);
        }
        return -1;
    }

    private void markProbeSlotDirty(int slot) {
        if (slot < 0 || slot >= MAX_GPU_PROBE_PAGES) {
            return;
        }
        if (!dirtyProbeSlots[slot]) {
            dirtyProbeSlots[slot] = true;
            dirtyProbeSlotQueue.addLast(slot);
            dirtyProbePageCount++;
        }
        probeGpuVersion++;
        probeHeaderDirty = true;
    }

    private DirtyProbeMarks markProbePageAndNeighborsDirty(ChunkSectionPos sectionPos) {
        DirtyProbeMarks marks = queueDirtyProbeOffset(sectionPos, 0, 0, 0);

        int total = marks.total();
        int neighbor = marks.neighbor();

        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {
                        continue;
                    }

                    marks = queueDirtyProbeOffset(sectionPos, dx, dy, dz);
                    total += marks.total();
                    neighbor += marks.neighbor();
                }
            }
        }

        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int manhattan = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (manhattan <= 1) {
                        continue;
                    }

                    marks = queueDirtyProbeOffset(sectionPos, dx, dy, dz);
                    total += marks.total();
                    neighbor += marks.neighbor();
                }
            }
        }
        return new DirtyProbeMarks(total, neighbor);
    }

    private DirtyProbeMarks queueDirtyProbeOffset(ChunkSectionPos sectionPos, int dx, int dy, int dz) {
        boolean neighborInfluence = dx != 0 || dy != 0 || dz != 0;
        if (!queueProbePageRegeneration(offsetSection(sectionPos, dx, dy, dz), neighborInfluence)) {
            return new DirtyProbeMarks(0, 0);
        }
        return new DirtyProbeMarks(1, neighborInfluence ? 1 : 0);
    }

    private boolean queueProbePageRegeneration(ChunkSectionPos sectionPos, boolean neighborInfluence) {
        if (!activeSectionPositions.contains(sectionPos)) {
            return false;
        }
        if (!queuedDirtyProbePages.add(sectionPos)) {
            return false;
        }

        dirtyProbePageQueue.addLast(sectionPos);
        if (neighborInfluence) {
            neighborInfluenceDirtyMarks++;
        }
        return true;
    }

    private void removeQueuedProbePage(ChunkSectionPos sectionPos) {
        if (queuedDirtyProbePages.remove(sectionPos)) {
            dirtyProbePageQueue.remove(sectionPos);
        }
    }

    private long uploadProbeHeader(VContext ctx, VCmdBuff cmd) {
        ByteBuffer data = probeHeaderScratch(PROBE_HEADER_BYTES);
        data.putInt(activeProbePages.size());
        data.putInt(probeSlotLimit);
        data.putInt(probeGpuVersion);
        data.putInt(dirtyProbePageCount);
        data.flip();
        cmd.encodeDataUpload(ctx.memory, MemoryUtil.memAddress(data), probeGpuBuffer, 0, PROBE_HEADER_BYTES);
        return PROBE_HEADER_BYTES;
    }

    private long uploadProbeDirectory(VContext ctx, VCmdBuff cmd) {
        ByteBuffer data = probeDirectoryScratch(PROBE_DIRECTORY_BYTES);
        MemoryUtil.memSet(MemoryUtil.memAddress(data), 0, PROBE_DIRECTORY_BYTES);
        for (SectionDirectionalProbePage page : activeProbePages.values()) {
            int directoryIndex = findProbeDirectoryIndex(page.sectionPos(), data);
            if (directoryIndex < 0) {
                logProbeDirectorySaturation(page.sectionPos());
                continue;
            }
            writeProbeDirectoryRecord(data, directoryIndex, page);
        }
        data.position(0);
        data.limit(PROBE_DIRECTORY_BYTES);
        cmd.encodeDataUpload(ctx.memory, MemoryUtil.memAddress(data), probeGpuBuffer,
                PROBE_HEADER_BYTES, PROBE_DIRECTORY_BYTES);
        return PROBE_DIRECTORY_BYTES;
    }

    private static int findProbeDirectoryIndex(ChunkSectionPos sectionPos, ByteBuffer directory) {
        int start = probeDirectoryHash(sectionPos.getSectionX(), sectionPos.getSectionY(), sectionPos.getSectionZ());
        for (int probe = 0; probe < PROBE_HASH_LOOKUP_LIMIT; probe++) {
            int index = (start + probe) & (MAX_GPU_PROBE_PAGES - 1);
            int offset = index * PROBE_RECORD_BYTES;
            if (directory.getInt(offset + 12) == 0) {
                return index;
            }
        }
        return -1;
    }

    private static void writeProbeDirectoryRecord(ByteBuffer directory, int directoryIndex, SectionDirectionalProbePage page) {
        int offset = directoryIndex * PROBE_RECORD_BYTES;
        ChunkSectionPos sectionPos = page.sectionPos();
        directory.putInt(offset, sectionPos.getMinX());
        directory.putInt(offset + 4, sectionPos.getMinY());
        directory.putInt(offset + 8, sectionPos.getMinZ());
        directory.putInt(offset + 12, page.slot() + 1);
    }

    private static int probeDirectoryHash(int sectionX, int sectionY, int sectionZ) {
        int hash = sectionX * 0x8da6b343
                ^ sectionY * 0xd8163841
                ^ sectionZ * 0xcb1ab31f;
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        return hash & (MAX_GPU_PROBE_PAGES - 1);
    }

    private void logProbeDirectorySaturation(ChunkSectionPos sectionPos) {
        long now = System.nanoTime();
        if (now - lastProbeDirectoryWarnNanos >= INFO_LOG_INTERVAL_NANOS) {
            lastProbeDirectoryWarnNanos = now;
            LOGGER.warn("[Vulkanite] Section light probe directory hash cluster saturated near {}; page will be temporarily unmapped",
                    sectionPos);
        }
    }

    private long uploadProbePage(VContext ctx, VCmdBuff cmd, int slot, SectionDirectionalProbePage page) {
        ByteBuffer data = probePageScratch(PROBE_PAGE_BYTES);
        page.writePackedFaceData(data);
        data.flip();
        long offset = PROBE_DATA_OFFSET_BYTES + (long) slot * PROBE_PAGE_BYTES;
        cmd.encodeDataUpload(ctx.memory, MemoryUtil.memAddress(data), probeGpuBuffer, offset, PROBE_PAGE_BYTES);
        return PROBE_PAGE_BYTES;
    }

    private ByteBuffer tableUploadScratch(int requiredBytes) {
        tableUploadScratch = uploadScratch(tableUploadScratch, requiredBytes);
        return tableUploadScratch;
    }

    private ByteBuffer probeHeaderScratch(int requiredBytes) {
        probeHeaderScratch = uploadScratch(probeHeaderScratch, requiredBytes);
        return probeHeaderScratch;
    }

    private ByteBuffer probeDirectoryScratch(int requiredBytes) {
        probeDirectoryScratch = uploadScratch(probeDirectoryScratch, requiredBytes);
        return probeDirectoryScratch;
    }

    private ByteBuffer probePageScratch(int requiredBytes) {
        probePageScratch = uploadScratch(probePageScratch, requiredBytes);
        return probePageScratch;
    }

    private static ByteBuffer uploadScratch(ByteBuffer scratch, int requiredBytes) {
        if (scratch == null || scratch.capacity() < requiredBytes) {
            if (scratch != null) {
                MemoryUtil.memFree(scratch);
            }
            scratch = MemoryUtil.memAlloc(requiredBytes);
        }
        scratch.clear();
        scratch.limit(requiredBytes);
        return scratch;
    }

    private void freeUploadScratchBuffers() {
        tableUploadScratch = freeUploadScratch(tableUploadScratch);
        probeHeaderScratch = freeUploadScratch(probeHeaderScratch);
        probeDirectoryScratch = freeUploadScratch(probeDirectoryScratch);
        probePageScratch = freeUploadScratch(probePageScratch);
    }

    private static ByteBuffer freeUploadScratch(ByteBuffer scratch) {
        if (scratch != null) {
            MemoryUtil.memFree(scratch);
        }
        return null;
    }

    private void logProbeUpload(
            int dirtyAtStart,
            int queuedAtStart,
            int queuedAfterRegeneration,
            int regeneratedProbePages,
            int neighborMarksAtStart,
            long uploadedBytes,
            long regenerationNanos,
            long uploadNanos,
            long totalNanos) {
        double regenerationMs = nanosToMillis(regenerationNanos);
        double uploadMs = nanosToMillis(uploadNanos);
        double totalMs = nanosToMillis(totalNanos);
        long now = System.nanoTime();
        if (now - lastProbeInfoLogNanos >= INFO_LOG_INTERVAL_NANOS) {
            lastProbeInfoLogNanos = now;
            LOGGER.info("[Vulkanite] Section light probes: active pages={}, slot limit={}, dirty upload pages={}, dirty queue {}->{}, regenerated={}, neighbor dirty marks={}, uploaded={} bytes, regen={} ms, upload={} ms, total={} ms, buffer={} bytes",
                    activeProbePages.size(), probeSlotLimit, dirtyAtStart, queuedAtStart, queuedAfterRegeneration,
                    regeneratedProbePages, neighborMarksAtStart, uploadedBytes,
                    formatMillis(regenerationMs),
                    formatMillis(uploadMs),
                    formatMillis(totalMs),
                    PROBE_BUFFER_BYTES);
        } else {
            LOGGER.debug("[Vulkanite] Section light probes: active pages={}, slot limit={}, dirty upload pages={}, dirty queue {}->{}, regenerated={}, neighbor dirty marks={}, uploaded={} bytes, regen={} ms, upload={} ms, total={} ms",
                    activeProbePages.size(), probeSlotLimit, dirtyAtStart, queuedAtStart, queuedAfterRegeneration,
                    regeneratedProbePages, neighborMarksAtStart, uploadedBytes,
                    formatMillis(regenerationMs),
                    formatMillis(uploadMs),
                    formatMillis(totalMs));
        }
    }

    private void logSectionLightUpload(int lightCount, int uploadBytes, long uploadNanos) {
        long now = System.nanoTime();
        if (now - lastTableUploadLogNanos >= INFO_LOG_INTERVAL_NANOS || uploadNanos >= 1_000_000L) {
            lastTableUploadLogNanos = now;
            LOGGER.info("[Vulkanite] Section light table upload: lights={}, active sections={}, uploaded={} bytes, upload={} ms, version={}",
                    lightCount, activeTables.size(), uploadBytes, formatMillis(nanosToMillis(uploadNanos)), gpuVersion);
        } else {
            LOGGER.debug("[Vulkanite] Section light table upload: lights={}, active sections={}, uploaded={} bytes, upload={} ms, version={}",
                    lightCount, activeTables.size(), uploadBytes, formatMillis(nanosToMillis(uploadNanos)), gpuVersion);
        }
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static String formatMillis(double millis) {
        return String.format(Locale.ROOT, "%.3f", millis);
    }

    private List<SectionLightTable> sortedTables() {
        if (sortedTableCache != null) {
            return sortedTableCache;
        }
        ArrayList<SectionLightTable> tables = new ArrayList<>(activeTables.values());
        tables.sort((a, b) -> Long.compare(a.sectionPos().asLong(), b.sectionPos().asLong()));
        sortedTableCache = tables;
        return sortedTableCache;
    }

    private void markGpuDirty() {
        gpuVersion++;
        gpuDirty = true;
        sortedTableCache = null;
    }

    private static int roundUpPowerOfTwo(int value) {
        value--;
        value |= value >> 1;
        value |= value >> 2;
        value |= value >> 4;
        value |= value >> 8;
        value |= value >> 16;
        return value + 1;
    }

    private static ChunkSectionPos offsetSection(ChunkSectionPos sectionPos, int dx, int dy, int dz) {
        return ChunkSectionPos.from(
                sectionPos.getSectionX() + dx,
                sectionPos.getSectionY() + dy,
                sectionPos.getSectionZ() + dz);
    }

    private record DirtyProbeMarks(int total, int neighbor) {
    }
}

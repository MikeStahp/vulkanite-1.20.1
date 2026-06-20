package me.cortex.vulkanite.client.lighting;

import me.cortex.vulkanite.compat.ISectionLightBuildResult;
import me.cortex.vulkanite.compat.SectionLight;
import me.cortex.vulkanite.compat.SectionLightTable;
import me.cortex.vulkanite.client.rendering.cache.CacheRequest;
import me.cortex.vulkanite.client.rendering.cache.CacheRequestBatch;
import me.cortex.vulkanite.client.rendering.cache.CacheRequestFamily;
import me.cortex.vulkanite.client.rendering.cache.CacheInvalidationTracker;
import me.cortex.vulkanite.client.rendering.cache.CacheRequestKey;
import me.cortex.vulkanite.client.rendering.cache.CacheRequestQueue;
import me.cortex.vulkanite.client.rendering.cache.CacheRequestSource;
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
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

public final class SectionLightManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SectionLightManager.class);
    private static final long INFO_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final int MAX_PROBE_WORKERS = 8;
    private static final int PROBE_WORKER_COUNT = probeWorkerCount();
    private static final int HEADER_BYTES = 16;
    private static final int RECORD_BYTES = 32;
    private static final int MAX_GPU_LIGHTS = 32768;
    private static final int MIN_GPU_LIGHT_GRID_RECORDS = 64;
    private static final int MAX_GPU_LIGHT_GRID_RECORDS = 8192;
    private static final int LIGHT_GRID_HASH_LOOKUP_LIMIT = 64;
    private static final int MAX_GPU_PROBE_PAGES = 1024;
    private static final int MAX_PROBE_REGENERATIONS_PER_UPDATE = 8;
    private static final int MAX_PROBE_REGENERATIONS_PER_UPLOAD = 4;
    private static final int MAX_LIGHTS_PER_PROBE_PAGE = 48;
    private static final int PROBE_LIGHT_CASCADE_SECTION_RADIUS = 2;
    private static final int PROBE_LIGHT_CASCADE_SECTION_DIAMETER =
            PROBE_LIGHT_CASCADE_SECTION_RADIUS * 2 + 1;
    private static final int PROBE_LIGHT_CASCADE_SECTION_COUNT =
            PROBE_LIGHT_CASCADE_SECTION_DIAMETER
                    * PROBE_LIGHT_CASCADE_SECTION_DIAMETER
                    * PROBE_LIGHT_CASCADE_SECTION_DIAMETER;
    private static final int PROBE_HASH_LOOKUP_LIMIT = 64;
    private static final int PROBE_DIRECTORY_RECORDS = MAX_GPU_PROBE_PAGES * 2;
    private static final int PROBE_RECORD_BYTES = SectionDirectionalProbePage.PACKED_RECORD_BYTES;
    private static final int PROBE_HEADER_BYTES = PROBE_RECORD_BYTES;
    private static final int PROBE_DIRECTORY_BYTES = PROBE_DIRECTORY_RECORDS * PROBE_RECORD_BYTES;
    private static final int PROBE_PAGE_BYTES = SectionDirectionalProbePage.PACKED_PAGE_BYTES;
    private static final int PROBE_FEEDBACK_HEADER_BYTES = PROBE_RECORD_BYTES;
    // Matches SectionLightProbeRtCacheCell: two uvec4 lanes for six radiance faces, state, and padding.
    private static final int PROBE_FEEDBACK_RECORD_BYTES = Integer.BYTES * 8;
    private static final int PROBE_FEEDBACK_RECORD_COUNT =
            MAX_GPU_PROBE_PAGES * SectionDirectionalProbePage.PROBE_COUNT;
    private static final int PROBE_FEEDBACK_SLOT_BYTES =
            SectionDirectionalProbePage.PROBE_COUNT * PROBE_FEEDBACK_RECORD_BYTES;
    private static final long PROBE_DATA_OFFSET_BYTES = (long) PROBE_HEADER_BYTES + PROBE_DIRECTORY_BYTES;
    private static final long PROBE_BUFFER_BYTES = PROBE_DATA_OFFSET_BYTES
            + (long) MAX_GPU_PROBE_PAGES * PROBE_PAGE_BYTES;
    private static final long PROBE_FEEDBACK_BUFFER_BYTES = PROBE_FEEDBACK_HEADER_BYTES
            + (long) PROBE_FEEDBACK_RECORD_COUNT * PROBE_FEEDBACK_RECORD_BYTES;
    private static final int MAX_PROBE_FILL_REQUESTS = 256;
    private static final int PROBE_FILL_REQUEST_HEADER_BYTES = 16;
    private static final int PROBE_FILL_REQUEST_RECORD_BYTES = 16;
    private static final int PROBE_FILL_REQUEST_BUFFER_BYTES = PROBE_FILL_REQUEST_HEADER_BYTES
            + MAX_PROBE_FILL_REQUESTS * PROBE_FILL_REQUEST_RECORD_BYTES;
    private static final long PROBE_SHUTDOWN_JOIN_MS =
            Math.max(0L, Long.getLong("vulkanite.probeShutdownJoinMs", 2_000L));

    private final Set<ChunkSectionPos> activeSectionPositions = new HashSet<>();
    private final Map<ChunkSectionPos, SectionLightTable> activeTables = new HashMap<>();
    private final Map<ChunkSectionPos, SectionLightTable> retainedTables = new HashMap<>();
    private final Map<ChunkSectionPos, SectionDirectionalProbePage> activeProbePages = new HashMap<>();
    private final SectionDirectionalProbePage[] probeSlots = new SectionDirectionalProbePage[MAX_GPU_PROBE_PAGES];
    private final boolean[] dirtyProbeSlots = new boolean[MAX_GPU_PROBE_PAGES];
    private final boolean[] dirtyProbeFeedbackSlots = new boolean[MAX_GPU_PROBE_PAGES];
    private final ArrayDeque<Integer> dirtyProbeSlotQueue = new ArrayDeque<>();
    private final ArrayDeque<Integer> dirtyProbeFeedbackSlotQueue = new ArrayDeque<>();
    private final ArrayDeque<ChunkSectionPos> dirtyProbePageQueue = new ArrayDeque<>();
    private final Set<ChunkSectionPos> queuedDirtyProbePages = new HashSet<>();
    private final ArrayDeque<ChunkSectionPos> dirtyProbeRtRequestPageQueue = new ArrayDeque<>();
    private final Map<ChunkSectionPos, Integer> dirtyProbeRtRequestCursors = new HashMap<>();
    private final ArrayDeque<Integer> freeProbeSlots = new ArrayDeque<>();
    private final ArrayList<SectionLightTable> neighborTableScratch =
            new ArrayList<>(PROBE_LIGHT_CASCADE_SECTION_COUNT);
    private final CacheInvalidationTracker cacheInvalidationTracker;
    private final ExecutorService probeBuildExecutor =
            Executors.newFixedThreadPool(PROBE_WORKER_COUNT, probeThreadFactory());
    private final ConcurrentLinkedQueue<ProbeBuildResult> completedProbeBuilds = new ConcurrentLinkedQueue<>();
    private final Map<ChunkSectionPos, Integer> probeBuildVersions = new HashMap<>();
    private final Set<ChunkSectionPos> inFlightProbeBuilds = new HashSet<>();
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
    private VRef<VBuffer> probeFeedbackGpuBuffer;
    private VRef<VBuffer> probeFillRequestGpuBuffer;
    private int probeSlotLimit;
    private int dirtyProbePageCount;
    private int neighborInfluenceDirtyMarks;
    // Zero is the cleared SSBO state, so cache-ready tokens start from one.
    private int probeGpuVersion = 1;
    private boolean probeHeaderDirty = true;
    private boolean probeDirectoryDirty = true;
    private boolean probeFeedbackHeaderDirty = true;
    private boolean probeFeedbackFullClearDirty = true;
    private long lastTableUploadLogNanos;
    private long lastLightGridDirectoryWarnNanos;
    private long lastProbeDirectoryWarnNanos;
    private ByteBuffer tableUploadScratch;
    private ByteBuffer probeHeaderScratch;
    private ByteBuffer probeDirectoryScratch;
    private ByteBuffer probePageScratch;
    private ByteBuffer probeFeedbackHeaderScratch;
    private ByteBuffer probeFeedbackClearScratch;
    private ByteBuffer probeFillRequestScratch;
    private volatile boolean destroyed;

    public SectionLightManager() {
        this(CacheInvalidationTracker.global());
    }

    public SectionLightManager(CacheInvalidationTracker cacheInvalidationTracker) {
        this.cacheInvalidationTracker = Objects.requireNonNull(cacheInvalidationTracker);
        LOGGER.info("[Vulkanite] Section light probe workers: {} of {} available processors",
                PROBE_WORKER_COUNT, Runtime.getRuntime().availableProcessors());
    }

    public synchronized void updateFromBuildResults(List<ChunkBuildOutput> results) {
        if (destroyed) {
            return;
        }

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
            SectionLightTable retained = retainedTables.get(sectionPos);
            SectionLightTable versionPrevious = previous != null ? previous : retained;
            boolean residentProbePage = activeProbePages.containsKey(sectionPos);
            boolean retainedProbeReusable = sectionBecameActive
                    && previous == null
                    && residentProbePage
                    && Objects.equals(retained, table);
            boolean tableChanged = previous == null ? table != null : !previous.equals(table);
            boolean lightVersionChanged = sectionBecameActive || !sameLights(versionPrevious, table);
            boolean probeLightingChanged = tableChanged && !retainedProbeReusable;

            cacheInvalidationTracker.recordSectionBuild(sectionPos, true, lightVersionChanged);

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
                retainedTables.remove(sectionPos);

                if (probeLightingChanged) {
                    DirtyProbeMarks marks = markProbePageAndNeighborsDirty(sectionPos);
                    queuedProbePages += marks.total();
                    neighborQueuedProbePages += marks.neighbor();
                }
                changedTables++;
            } else if (sectionBecameActive) {
                retainedTables.remove(sectionPos);
                if (!residentProbePage && queueProbePageRegeneration(sectionPos, false)) {
                    queuedProbePages++;
                }
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
        if (now - lastInfoLogNanos >= INFO_LOG_INTERVAL_NANOS) {
            lastInfoLogNanos = now;
            LOGGER.info("[Vulkanite] Section lights: updated {} sections, {} light-table changes, {} lights in batch, {} active sections, {} active lights, {} active probe pages, {} queued probe pages ({} neighbor), prebaked probe pages={}",
                    processedSections, changedTables, batchLights, activeTables.size(), activeLightCount,
                    activeProbePages.size(), queuedProbePages, neighborQueuedProbePages, prebakedProbePages);
        } else if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[Vulkanite] Section lights: updated {} sections, {} light-table changes, {} lights in batch, {} active sections, {} active lights, {} active probe pages, {} queued probe pages ({} neighbor), prebaked probe pages={}",
                    processedSections, changedTables, batchLights, activeTables.size(), activeLightCount,
                    activeProbePages.size(), queuedProbePages, neighborQueuedProbePages, prebakedProbePages);
        }
    }

    public synchronized void removeSection(RenderSection section) {
        if (destroyed || section == null) {
            return;
        }
        ChunkSectionPos sectionPos = section.getPosition();
        cacheInvalidationTracker.recordSectionRemoval(sectionPos);
        activeSectionPositions.remove(sectionPos);
        removeQueuedProbePage(sectionPos);
        removeQueuedProbeRtRequests(sectionPos);
        probeBuildVersions.remove(sectionPos);

        SectionLightTable removed = activeTables.remove(sectionPos);
        SectionDirectionalProbePage residentPage = activeProbePages.get(sectionPos);
        boolean retainProbePage = isRetainableBlocklightPage(residentPage, removed);
        if (removed != null && retainProbePage) {
            retainedTables.put(sectionPos, removed);
        } else {
            retainedTables.remove(sectionPos);
        }
        if (removed != null) {
            activeLightCount -= removed.size();
            markGpuDirty();
            DirtyProbeMarks marks = markProbePageAndNeighborsDirty(sectionPos, false);
            int prebakedProbePages = processDirtyProbePages(MAX_PROBE_REGENERATIONS_PER_UPDATE);
            LOGGER.debug("[Vulkanite] Removed section light table for {}; queued {} neighbor probe pages, prebaked {} probe pages",
                    sectionPos, marks.neighbor(), prebakedProbePages);
        }
        if (retainProbePage) {
            LOGGER.debug("[Vulkanite] Retained blocklight section probe RT cache for {}", sectionPos);
        } else if (residentPage != null && removeProbePage(sectionPos)) {
            LOGGER.debug("[Vulkanite] Removed non-blocklight-retained section light probe page for {}", sectionPos);
        }
    }

    public synchronized VRef<VBuffer> ensureGpuBuffer(VContext ctx, VCmdBuff cmd) {
        int lightCount = Math.min(activeLightCount, MAX_GPU_LIGHTS);
        List<SectionLightTable> sortedTables = sortedTables();
        int lightGridRecordCount = lightGridRecordCount(sortedTables.size(), lightCount);
        int uploadBytes = HEADER_BYTES + (lightCount + lightGridRecordCount) * RECORD_BYTES;
        ensureCapacity(ctx, uploadBytes);

        if (!gpuDirty) {
            return gpuBuffer.addRef();
        }

        long uploadStartNanos = System.nanoTime();
        ByteBuffer data = tableUploadScratch(uploadBytes);
        data.putInt(lightCount);
        data.putInt(activeTables.size());
        data.putInt(gpuVersion);
        data.putInt(lightGridRecordCount);

        int written = 0;
        ArrayList<UploadedLightSection> uploadedSections = new ArrayList<>(sortedTables.size());
        for (SectionLightTable table : sortedTables) {
            int originX = table.sectionPos().getMinX();
            int originY = table.sectionPos().getMinY();
            int originZ = table.sectionPos().getMinZ();
            int firstLight = written;
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
            int sectionLightCount = written - firstLight;
            if (sectionLightCount > 0) {
                uploadedSections.add(new UploadedLightSection(table.sectionPos(), firstLight, sectionLightCount));
            }
            if (written >= lightCount) {
                break;
            }
        }
        writeLightGridDirectory(data, lightCount, lightGridRecordCount, uploadedSections);
        data.flip();

        cmd.encodeDataUpload(ctx.memory, MemoryUtil.memAddress(data), gpuBuffer, 0, uploadBytes);
        cmd.encodeBufferBarrier(gpuBuffer, 0, uploadBytes,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
        gpuDirty = false;
        logSectionLightUpload(lightCount, uploadedSections.size(), lightGridRecordCount,
                uploadBytes, System.nanoTime() - uploadStartNanos);
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

    public synchronized VRef<VBuffer> ensureProbeFeedbackGpuBuffer(VContext ctx, VCmdBuff cmd) {
        ensureProbeFeedbackCapacity(ctx);

        boolean uploadPending = probeFeedbackFullClearDirty
                || probeFeedbackHeaderDirty
                || !dirtyProbeFeedbackSlotQueue.isEmpty();
        if (uploadPending) {
            cmd.encodeBufferBarrier(probeFeedbackGpuBuffer, 0, PROBE_FEEDBACK_BUFFER_BYTES,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT);
        }

        long uploadedBytes = 0L;
        if (probeFeedbackFullClearDirty) {
            ByteBuffer data = probeFeedbackClearScratch((int) PROBE_FEEDBACK_BUFFER_BYTES);
            MemoryUtil.memSet(MemoryUtil.memAddress(data), 0, PROBE_FEEDBACK_BUFFER_BYTES);
            cmd.encodeDataUpload(ctx.memory, MemoryUtil.memAddress(data),
                    probeFeedbackGpuBuffer, 0, PROBE_FEEDBACK_BUFFER_BYTES);
            Arrays.fill(dirtyProbeFeedbackSlots, false);
            dirtyProbeFeedbackSlotQueue.clear();
            probeFeedbackFullClearDirty = false;
            probeFeedbackHeaderDirty = true;
            uploadedBytes += PROBE_FEEDBACK_BUFFER_BYTES;
        }

        if (probeFeedbackHeaderDirty) {
            ByteBuffer data = probeFeedbackHeaderScratch(PROBE_FEEDBACK_HEADER_BYTES);
            data.putInt(activeProbePages.size());
            data.putInt(probeSlotLimit);
            data.putInt(probeGpuVersion);
            data.putInt(PROBE_FEEDBACK_RECORD_COUNT);
            data.flip();
            cmd.encodeDataUpload(ctx.memory, MemoryUtil.memAddress(data),
                    probeFeedbackGpuBuffer, 0, PROBE_FEEDBACK_HEADER_BYTES);
            probeFeedbackHeaderDirty = false;
            uploadedBytes += PROBE_FEEDBACK_HEADER_BYTES;
        }

        while (!dirtyProbeFeedbackSlotQueue.isEmpty()) {
            int slot = dirtyProbeFeedbackSlotQueue.removeFirst();
            if (!dirtyProbeFeedbackSlots[slot]) {
                continue;
            }

            ByteBuffer data = probeFeedbackClearScratch(PROBE_FEEDBACK_SLOT_BYTES);
            MemoryUtil.memSet(MemoryUtil.memAddress(data), 0, PROBE_FEEDBACK_SLOT_BYTES);
            long offset = PROBE_FEEDBACK_HEADER_BYTES + (long) slot * PROBE_FEEDBACK_SLOT_BYTES;
            cmd.encodeDataUpload(ctx.memory, MemoryUtil.memAddress(data),
                    probeFeedbackGpuBuffer, offset, PROBE_FEEDBACK_SLOT_BYTES);
            dirtyProbeFeedbackSlots[slot] = false;
            uploadedBytes += PROBE_FEEDBACK_SLOT_BYTES;
        }

        if (uploadedBytes > 0L) {
            cmd.encodeBufferBarrier(probeFeedbackGpuBuffer, 0, PROBE_FEEDBACK_BUFFER_BYTES,
                    VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        } else {
            cmd.encodeBufferBarrier(probeFeedbackGpuBuffer, 0, PROBE_FEEDBACK_BUFFER_BYTES,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        }

        return probeFeedbackGpuBuffer.addRef();
    }

    public synchronized VRef<VBuffer> ensureProbeFillRequestGpuBuffer(
            VContext ctx,
            VCmdBuff cmd,
            CacheRequestBatch batch) {
        ensureProbeFillRequestCapacity(ctx);

        ByteBuffer data = probeFillRequestScratch(PROBE_FILL_REQUEST_BUFFER_BYTES);
        data.putInt(0);
        data.putInt(probeGpuVersion);
        data.putInt(MAX_PROBE_FILL_REQUESTS);
        data.putInt(0);

        int requestCount = 0;
        if (batch != null) {
            for (CacheRequest request : batch.requests()) {
                if (requestCount >= MAX_PROBE_FILL_REQUESTS
                        || request.key().family() != CacheRequestFamily.SECTION_PROBE_CELL
                        || !cacheInvalidationTracker.isCurrent(request.key(), request.versionStamp())) {
                    continue;
                }

                ChunkSectionPos sectionPos = ChunkSectionPos.from(request.key().spatialKey());
                SectionDirectionalProbePage page = activeProbePages.get(sectionPos);
                int probeIndex = request.key().variantKey();
                if (page == null || !page.hasPackedRadiance()
                        || queuedDirtyProbePages.contains(sectionPos)
                        || inFlightProbeBuilds.contains(sectionPos)
                        || page.slot() < 0
                        || probeIndex < 0
                        || probeIndex >= SectionDirectionalProbePage.PROBE_COUNT) {
                    continue;
                }

                data.putInt(sectionPos.getMinX());
                data.putInt(sectionPos.getMinY());
                data.putInt(sectionPos.getMinZ());
                data.putInt(probeIndex);
                requestCount++;
            }
        }

        data.putInt(0, requestCount);
        data.position(PROBE_FILL_REQUEST_HEADER_BYTES
                + requestCount * PROBE_FILL_REQUEST_RECORD_BYTES);
        data.flip();
        int uploadBytes = data.remaining();
        cmd.encodeDataUpload(ctx.memory, MemoryUtil.memAddress(data),
                probeFillRequestGpuBuffer, 0, uploadBytes);
        cmd.encodeBufferBarrier(probeFillRequestGpuBuffer, 0, uploadBytes,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT);
        return probeFillRequestGpuBuffer.addRef();
    }

    public synchronized int activeSectionCount() {
        return activeTables.size();
    }

    public synchronized int activeLightCount() {
        return activeLightCount;
    }

    public synchronized int drainPendingCacheRequests(
            CacheRequestQueue requests,
            ChunkSectionPos cameraSection,
            int frameIndex,
            int maxRequests) {
        if (destroyed || requests == null || maxRequests <= 0) {
            return 0;
        }

        int emitted = 0;
        int attempts = dirtyProbeRtRequestPageQueue.size();
        while (emitted < maxRequests && attempts-- > 0 && !dirtyProbeRtRequestPageQueue.isEmpty()) {
            ChunkSectionPos sectionPos = dirtyProbeRtRequestPageQueue.removeFirst();
            Integer cursor = dirtyProbeRtRequestCursors.get(sectionPos);
            if (cursor == null) {
                continue;
            }

            SectionDirectionalProbePage page = activeProbePages.get(sectionPos);
            if (page == null || !page.hasPackedRadiance()) {
                dirtyProbeRtRequestCursors.remove(sectionPos);
                continue;
            }

            int probeIndex = Math.max(0, cursor);
            float distanceSquared = requestDistanceSquared(sectionPos, cameraSection);
            while (probeIndex < SectionDirectionalProbePage.PROBE_COUNT && emitted < maxRequests) {
                CacheRequestKey key = CacheRequestKey.sectionProbeCell(sectionPos, probeIndex);
                CacheRequestQueue.EnqueueResult enqueueResult = requests.enqueue(CacheRequest.sectionProbeCell(
                        key,
                        CacheRequestSource.SECTION_DIRTY_QUEUE,
                        frameIndex,
                        page.probeLuma(probeIndex),
                        distanceSquared));
                if (!enqueueResult.accepted()) {
                    break;
                }
                probeIndex++;
                emitted++;
            }

            if (probeIndex < SectionDirectionalProbePage.PROBE_COUNT) {
                dirtyProbeRtRequestCursors.put(sectionPos, probeIndex);
                dirtyProbeRtRequestPageQueue.addLast(sectionPos);
            } else {
                dirtyProbeRtRequestCursors.remove(sectionPos);
            }
        }
        return emitted;
    }

    public synchronized void clearPendingCacheRequests() {
        dirtyProbeRtRequestPageQueue.clear();
        dirtyProbeRtRequestCursors.clear();
    }

    public synchronized void clear() {
        activeSectionPositions.clear();
        activeTables.clear();
        retainedTables.clear();
        activeProbePages.clear();
        Arrays.fill(probeSlots, null);
        Arrays.fill(dirtyProbeSlots, false);
        Arrays.fill(dirtyProbeFeedbackSlots, false);
        dirtyProbeSlotQueue.clear();
        dirtyProbeFeedbackSlotQueue.clear();
        dirtyProbePageQueue.clear();
        queuedDirtyProbePages.clear();
        dirtyProbeRtRequestPageQueue.clear();
        dirtyProbeRtRequestCursors.clear();
        freeProbeSlots.clear();
        completedProbeBuilds.clear();
        inFlightProbeBuilds.clear();
        probeBuildVersions.clear();
        neighborTableScratch.clear();
        cacheInvalidationTracker.clearSectionVersions();
        sortedTableCache = null;
        activeLightCount = 0;
        gpuDirty = true;
        probeSlotLimit = 0;
        dirtyProbePageCount = 0;
        neighborInfluenceDirtyMarks = 0;
        probeGpuVersion = 1;
        probeHeaderDirty = true;
        probeDirectoryDirty = true;
        probeFeedbackHeaderDirty = true;
        probeFeedbackFullClearDirty = true;
        lastTableUploadLogNanos = 0L;
        lastLightGridDirectoryWarnNanos = 0L;
        if (gpuBuffer != null) {
            gpuBuffer.close();
            gpuBuffer = null;
        }
        if (probeGpuBuffer != null) {
            probeGpuBuffer.close();
            probeGpuBuffer = null;
        }
        if (probeFeedbackGpuBuffer != null) {
            probeFeedbackGpuBuffer.close();
            probeFeedbackGpuBuffer = null;
        }
        if (probeFillRequestGpuBuffer != null) {
            probeFillRequestGpuBuffer.close();
            probeFillRequestGpuBuffer = null;
        }
        gpuBufferCapacityBytes = 0;
        freeUploadScratchBuffers();
    }

    public void destroy() {
        int dirtyQueueCount;
        int inFlightCount;
        synchronized (this) {
            if (destroyed) {
                return;
            }
            destroyed = true;
            dirtyQueueCount = dirtyProbePageQueue.size();
            inFlightCount = inFlightProbeBuilds.size();
            dirtyProbePageQueue.clear();
            queuedDirtyProbePages.clear();
        }

        int cancelledQueuedTasks = probeBuildExecutor.shutdownNow().size();
        boolean stopped = awaitProbeExecutorStop();

        synchronized (this) {
            int discardedResults = completedProbeBuilds.size();
            int abandonedInFlight = inFlightProbeBuilds.size();
            clear();
            if (stopped) {
                LOGGER.info("[Vulkanite] Section probe workers stopped during shutdown; dirtyQueue={}, inFlight={}, cancelledTasks={}, discardedResults={}",
                        dirtyQueueCount, inFlightCount, cancelledQueuedTasks, discardedResults);
            } else {
                LOGGER.warn("[Vulkanite] Section probe workers did not stop within {} ms; abandonedInFlight={}, dirtyQueue={}, cancelledTasks={}, discardedResults={}",
                        PROBE_SHUTDOWN_JOIN_MS, abandonedInFlight, dirtyQueueCount, cancelledQueuedTasks,
                        discardedResults);
            }
        }
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

    private void ensureProbeFeedbackCapacity(VContext ctx) {
        if (probeFeedbackGpuBuffer != null) {
            return;
        }

        probeFeedbackGpuBuffer = ctx.memory.createBuffer(
                PROBE_FEEDBACK_BUFFER_BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        probeFeedbackGpuBuffer.get().setDebugUtilsObjectName("Section directional light probe RT radiance cache");
        probeFeedbackHeaderDirty = true;
        probeFeedbackFullClearDirty = true;
    }

    private void ensureProbeFillRequestCapacity(VContext ctx) {
        if (probeFillRequestGpuBuffer != null) {
            return;
        }

        probeFillRequestGpuBuffer = ctx.memory.createBuffer(
                PROBE_FILL_REQUEST_BUFFER_BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        probeFillRequestGpuBuffer.get().setDebugUtilsObjectName("Section probe RTX fill requests");
    }

    private int processDirtyProbePages(int maxPages) {
        if (destroyed) {
            completedProbeBuilds.clear();
            return 0;
        }

        int completed = applyCompletedProbeBuilds();
        int scheduledOrApplied = completed;
        int processed = 0;
        int attempts = dirtyProbePageQueue.size();
        while (processed < maxPages && attempts-- > 0 && !dirtyProbePageQueue.isEmpty()) {
            ChunkSectionPos sectionPos = dirtyProbePageQueue.removeFirst();
            queuedDirtyProbePages.remove(sectionPos);
            if (inFlightProbeBuilds.contains(sectionPos)) {
                queuedDirtyProbePages.add(sectionPos);
                dirtyProbePageQueue.addLast(sectionPos);
                continue;
            }
            processed++;

            if (scheduleProbePageBuild(sectionPos)) {
                scheduledOrApplied++;
            }
        }
        return scheduledOrApplied;
    }

    private boolean scheduleProbePageBuild(ChunkSectionPos sectionPos) {
        if (destroyed || probeBuildExecutor.isShutdown()) {
            return false;
        }
        if (!activeSectionPositions.contains(sectionPos)) {
            return removeProbePage(sectionPos);
        }

        List<SectionLightTable> neighborTables = List.copyOf(gatherNeighborLightTables(sectionPos));
        if (!hasAnyLights(neighborTables)) {
            return removeProbePage(sectionPos);
        }

        int buildVersion = probeBuildVersions.getOrDefault(sectionPos, 0);
        SectionDirectionalProbePage page = new SectionDirectionalProbePage(sectionPos);
        page.copyTemporalHistoryFrom(activeProbePages.get(sectionPos));
        inFlightProbeBuilds.add(sectionPos);
        try {
            CompletableFuture.runAsync(() -> {
                if (destroyed || Thread.currentThread().isInterrupted()) {
                    return;
                }
                Throwable failure = null;
                try {
                    page.regenerateFrom(neighborTables, MAX_LIGHTS_PER_PROBE_PAGE);
                } catch (Throwable throwable) {
                    failure = throwable;
                }
                if (!destroyed) {
                    completedProbeBuilds.add(new ProbeBuildResult(sectionPos, buildVersion, page, failure));
                }
            }, probeBuildExecutor);
        } catch (RejectedExecutionException e) {
            inFlightProbeBuilds.remove(sectionPos);
            LOGGER.warn("[Vulkanite] Section probe worker rejected rebuild for {}; executorShutdown={}",
                    sectionPos, probeBuildExecutor.isShutdown());
            return false;
        }
        return true;
    }

    private int applyCompletedProbeBuilds() {
        if (destroyed) {
            completedProbeBuilds.clear();
            return 0;
        }

        int applied = 0;
        ProbeBuildResult result;
        while ((result = completedProbeBuilds.poll()) != null) {
            ChunkSectionPos sectionPos = result.sectionPos();
            inFlightProbeBuilds.remove(sectionPos);
            if (result.failure() != null) {
                LOGGER.warn("[Vulkanite] Section probe rebuild failed for {}", sectionPos, result.failure());
                queueProbePageRegeneration(sectionPos, false);
                continue;
            }

            Integer currentVersion = probeBuildVersions.get(sectionPos);
            if (currentVersion == null || currentVersion != result.buildVersion()
                    || !activeSectionPositions.contains(sectionPos)) {
                if (currentVersion != null && activeSectionPositions.contains(sectionPos)) {
                    queueProbePageRegeneration(sectionPos, false);
                }
                continue;
            }

            SectionDirectionalProbePage page = result.page();
            if (!isInfluencedProbePage(page)) {
                removeProbePage(sectionPos);
                retainedTables.remove(sectionPos);
                continue;
            }

            SectionDirectionalProbePage previous = activeProbePages.get(sectionPos);
            boolean newPage = previous == null;
            int slot = newPage ? allocateProbeSlot(sectionPos) : previous.slot();
            if (slot < 0) {
                continue;
            }

            page.setSlot(slot);
            activeProbePages.put(sectionPos, page);
            probeSlots[slot] = page;
            if (newPage) {
                probeHeaderDirty = true;
                probeDirectoryDirty = true;
            }
            markProbeSlotDirty(slot);
            applied++;
        }
        return applied;
    }

    private List<SectionLightTable> gatherNeighborLightTables(ChunkSectionPos sectionPos) {
        ArrayList<SectionLightTable> tables = neighborTableScratch;
        tables.clear();
        for (int dz = -PROBE_LIGHT_CASCADE_SECTION_RADIUS;
                dz <= PROBE_LIGHT_CASCADE_SECTION_RADIUS;
                dz++) {
            for (int dy = -PROBE_LIGHT_CASCADE_SECTION_RADIUS;
                    dy <= PROBE_LIGHT_CASCADE_SECTION_RADIUS;
                    dy++) {
                for (int dx = -PROBE_LIGHT_CASCADE_SECTION_RADIUS;
                        dx <= PROBE_LIGHT_CASCADE_SECTION_RADIUS;
                        dx++) {
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

    private static boolean isInfluencedProbePage(SectionDirectionalProbePage page) {
        return page != null && page.hasPackedRadiance();
    }

    private static boolean isRetainableBlocklightPage(
            SectionDirectionalProbePage page,
            SectionLightTable sourceTable) {
        return isInfluencedProbePage(page) && sourceTable != null && sourceTable.hasLights();
    }

    private boolean removeProbePage(ChunkSectionPos sectionPos) {
        SectionDirectionalProbePage page = activeProbePages.remove(sectionPos);
        if (page == null) {
            return false;
        }
        removeQueuedProbeRtRequests(sectionPos);
        if (!activeSectionPositions.contains(sectionPos)) {
            retainedTables.remove(sectionPos);
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

        int evictedSlot = evictProbeSlotFor(sectionPos);
        if (evictedSlot >= 0) {
            return evictedSlot;
        }

        long now = System.nanoTime();
        if (now - lastProbeCapacityWarnNanos >= INFO_LOG_INTERVAL_NANOS) {
            lastProbeCapacityWarnNanos = now;
            LOGGER.warn("[Vulkanite] Section light probe page capacity reached ({} pages) with no evictable slots; keeping table fallback for {}",
                    MAX_GPU_PROBE_PAGES, sectionPos);
        }
        return -1;
    }

    private int evictProbeSlotFor(ChunkSectionPos requestedSectionPos) {
        SectionDirectionalProbePage victim = selectProbeEvictionVictim(requestedSectionPos, false, true);
        if (victim == null) {
            victim = selectProbeEvictionVictim(requestedSectionPos, true, true);
        }
        if (victim == null) {
            victim = selectProbeEvictionVictim(requestedSectionPos, false, false);
        }
        if (victim == null) {
            victim = selectProbeEvictionVictim(requestedSectionPos, true, false);
        }
        if (victim == null) {
            return -1;
        }

        ChunkSectionPos victimSectionPos = victim.sectionPos();
        int slot = victim.slot();
        if (slot < 0 || slot >= probeSlots.length) {
            return -1;
        }
        activeProbePages.remove(victimSectionPos);
        retainedTables.remove(victimSectionPos);
        removeQueuedProbePage(victimSectionPos);
        removeQueuedProbeRtRequests(victimSectionPos);
        probeSlots[slot] = null;
        victim.setSlot(-1);
        probeHeaderDirty = true;
        probeDirectoryDirty = true;
        LOGGER.debug("[Vulkanite] Evicted section light probe page for {} from slot {} to map {}",
                victimSectionPos, slot, requestedSectionPos);
        return slot;
    }

    private SectionDirectionalProbePage selectProbeEvictionVictim(
            ChunkSectionPos requestedSectionPos,
            boolean allowQueuedPages,
            boolean inactiveOnly) {
        SectionDirectionalProbePage victim = null;
        long farthestDistance = Long.MIN_VALUE;
        for (SectionDirectionalProbePage page : activeProbePages.values()) {
            ChunkSectionPos candidateSectionPos = page.sectionPos();
            if (inactiveOnly && activeSectionPositions.contains(candidateSectionPos)) {
                continue;
            }
            if (inFlightProbeBuilds.contains(candidateSectionPos)) {
                continue;
            }
            if (!allowQueuedPages && queuedDirtyProbePages.contains(candidateSectionPos)) {
                continue;
            }

            long distance = sectionDistanceSquared(requestedSectionPos, candidateSectionPos);
            if (victim == null || distance > farthestDistance) {
                victim = page;
                farthestDistance = distance;
            }
        }
        return victim;
    }

    private void markProbeSlotDirty(int slot) {
        if (slot < 0 || slot >= MAX_GPU_PROBE_PAGES) {
            return;
        }
        SectionDirectionalProbePage page = probeSlots[slot];
        if (page != null) {
            queueProbeRtRequests(page.sectionPos());
        }
        probeGpuVersion++;
        if (probeGpuVersion <= 0) {
            probeGpuVersion = 1;
            probeFeedbackFullClearDirty = true;
        }
        if (!dirtyProbeSlots[slot]) {
            dirtyProbeSlots[slot] = true;
            dirtyProbeSlotQueue.addLast(slot);
            dirtyProbePageCount++;
        }
        markProbeFeedbackSlotDirty(slot);
        probeHeaderDirty = true;
    }

    private void markProbeFeedbackSlotDirty(int slot) {
        if (slot < 0 || slot >= MAX_GPU_PROBE_PAGES || probeFeedbackFullClearDirty) {
            return;
        }
        if (!dirtyProbeFeedbackSlots[slot]) {
            dirtyProbeFeedbackSlots[slot] = true;
            dirtyProbeFeedbackSlotQueue.addLast(slot);
        }
        probeFeedbackHeaderDirty = true;
    }

    private DirtyProbeMarks markProbePageAndNeighborsDirty(ChunkSectionPos sectionPos) {
        return markProbePageAndNeighborsDirty(sectionPos, true);
    }

    private DirtyProbeMarks markProbePageAndNeighborsDirty(ChunkSectionPos sectionPos, boolean invalidateInactive) {
        int total = 0;
        int neighbor = 0;
        for (int manhattan = 0; manhattan <= PROBE_LIGHT_CASCADE_SECTION_RADIUS * 3; manhattan++) {
            for (int dz = -PROBE_LIGHT_CASCADE_SECTION_RADIUS;
                    dz <= PROBE_LIGHT_CASCADE_SECTION_RADIUS;
                    dz++) {
                for (int dy = -PROBE_LIGHT_CASCADE_SECTION_RADIUS;
                        dy <= PROBE_LIGHT_CASCADE_SECTION_RADIUS;
                        dy++) {
                    for (int dx = -PROBE_LIGHT_CASCADE_SECTION_RADIUS;
                            dx <= PROBE_LIGHT_CASCADE_SECTION_RADIUS;
                            dx++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != manhattan) {
                            continue;
                        }

                        DirtyProbeMarks marks = queueDirtyProbeOffset(
                                sectionPos, dx, dy, dz, invalidateInactive);
                        total += marks.total();
                        neighbor += marks.neighbor();
                    }
                }
            }
        }
        return new DirtyProbeMarks(total, neighbor);
    }

    private DirtyProbeMarks queueDirtyProbeOffset(ChunkSectionPos sectionPos, int dx, int dy, int dz) {
        return queueDirtyProbeOffset(sectionPos, dx, dy, dz, true);
    }

    private DirtyProbeMarks queueDirtyProbeOffset(
            ChunkSectionPos sectionPos,
            int dx,
            int dy,
            int dz,
            boolean invalidateInactive) {
        boolean neighborInfluence = dx != 0 || dy != 0 || dz != 0;
        if (!queueProbePageRegeneration(
                offsetSection(sectionPos, dx, dy, dz),
                neighborInfluence,
                invalidateInactive)) {
            return new DirtyProbeMarks(0, 0);
        }
        return new DirtyProbeMarks(1, neighborInfluence ? 1 : 0);
    }

    private boolean queueProbePageRegeneration(ChunkSectionPos sectionPos, boolean neighborInfluence) {
        return queueProbePageRegeneration(sectionPos, neighborInfluence, true);
    }

    private boolean queueProbePageRegeneration(
            ChunkSectionPos sectionPos,
            boolean neighborInfluence,
            boolean invalidateInactive) {
        if (destroyed) {
            return false;
        }

        if (!activeSectionPositions.contains(sectionPos)) {
            if (invalidateInactive) {
                removeProbePage(sectionPos);
            }
            return false;
        }
        probeBuildVersions.merge(sectionPos, 1, Integer::sum);
        if (!queuedDirtyProbePages.add(sectionPos)) {
            return false;
        }

        SectionDirectionalProbePage residentPage = activeProbePages.get(sectionPos);
        if (residentPage != null) {
            removeQueuedProbeRtRequests(sectionPos);
            markProbeFeedbackSlotDirty(residentPage.slot());
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

    private void queueProbeRtRequests(ChunkSectionPos sectionPos) {
        if (destroyed || sectionPos == null) {
            return;
        }
        Integer previousCursor = dirtyProbeRtRequestCursors.put(sectionPos, 0);
        if (previousCursor == null) {
            dirtyProbeRtRequestPageQueue.addLast(sectionPos);
        }
    }

    private void removeQueuedProbeRtRequests(ChunkSectionPos sectionPos) {
        if (dirtyProbeRtRequestCursors.remove(sectionPos) != null) {
            dirtyProbeRtRequestPageQueue.remove(sectionPos);
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
            int index = (start + probe) & (PROBE_DIRECTORY_RECORDS - 1);
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
        return hash & (PROBE_DIRECTORY_RECORDS - 1);
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

    private ByteBuffer probeFeedbackHeaderScratch(int requiredBytes) {
        probeFeedbackHeaderScratch = uploadScratch(probeFeedbackHeaderScratch, requiredBytes);
        return probeFeedbackHeaderScratch;
    }

    private ByteBuffer probeFeedbackClearScratch(int requiredBytes) {
        probeFeedbackClearScratch = uploadScratch(probeFeedbackClearScratch, requiredBytes);
        return probeFeedbackClearScratch;
    }

    private ByteBuffer probeFillRequestScratch(int requiredBytes) {
        probeFillRequestScratch = uploadScratch(probeFillRequestScratch, requiredBytes);
        return probeFillRequestScratch;
    }

    private static ByteBuffer uploadScratch(ByteBuffer scratch, int requiredBytes) {
        if (scratch == null || scratch.capacity() < requiredBytes) {
            if (scratch != null) {
                MemoryUtil.memFree(scratch);
            }
            scratch = MemoryUtil.memAlloc(requiredBytes);
        }
        scratch.order(ByteOrder.nativeOrder());
        scratch.clear();
        scratch.limit(requiredBytes);
        return scratch;
    }

    private void freeUploadScratchBuffers() {
        tableUploadScratch = freeUploadScratch(tableUploadScratch);
        probeHeaderScratch = freeUploadScratch(probeHeaderScratch);
        probeDirectoryScratch = freeUploadScratch(probeDirectoryScratch);
        probePageScratch = freeUploadScratch(probePageScratch);
        probeFeedbackHeaderScratch = freeUploadScratch(probeFeedbackHeaderScratch);
        probeFeedbackClearScratch = freeUploadScratch(probeFeedbackClearScratch);
        probeFillRequestScratch = freeUploadScratch(probeFillRequestScratch);
    }

    private static ByteBuffer freeUploadScratch(ByteBuffer scratch) {
        if (scratch != null) {
            MemoryUtil.memFree(scratch);
        }
        return null;
    }

    private boolean awaitProbeExecutorStop() {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROBE_SHUTDOWN_JOIN_MS);
        boolean interrupted = false;
        while (!probeBuildExecutor.isTerminated() && System.nanoTime() < deadlineNanos) {
            long remainingNanos = Math.max(1L, deadlineNanos - System.nanoTime());
            long waitMillis = Math.max(1L, Math.min(50L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            try {
                if (probeBuildExecutor.awaitTermination(waitMillis, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return probeBuildExecutor.isTerminated();
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
        } else if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[Vulkanite] Section light probes: active pages={}, slot limit={}, dirty upload pages={}, dirty queue {}->{}, regenerated={}, neighbor dirty marks={}, uploaded={} bytes, regen={} ms, upload={} ms, total={} ms",
                    activeProbePages.size(), probeSlotLimit, dirtyAtStart, queuedAtStart, queuedAfterRegeneration,
                    regeneratedProbePages, neighborMarksAtStart, uploadedBytes,
                    formatMillis(regenerationMs),
                    formatMillis(uploadMs),
                    formatMillis(totalMs));
        }
    }

    private void writeLightGridDirectory(
            ByteBuffer data,
            int lightCount,
            int lightGridRecordCount,
            List<UploadedLightSection> uploadedSections) {
        if (lightGridRecordCount <= 0) {
            return;
        }

        int directoryStart = HEADER_BYTES + lightCount * RECORD_BYTES;
        data.position(directoryStart);
        for (int i = 0; i < lightGridRecordCount * (RECORD_BYTES / Integer.BYTES); i++) {
            data.putInt(0);
        }

        for (UploadedLightSection section : uploadedSections) {
            int directoryIndex = findLightGridDirectoryIndex(
                    section.sectionPos(), data, lightCount, lightGridRecordCount);
            if (directoryIndex < 0) {
                logLightGridDirectorySaturation(section.sectionPos());
                continue;
            }
            writeLightGridDirectoryRecord(data, lightCount, directoryIndex, section);
        }
    }

    private static int findLightGridDirectoryIndex(
            ChunkSectionPos sectionPos,
            ByteBuffer data,
            int lightCount,
            int lightGridRecordCount) {
        int start = lightGridHash(sectionPos.getSectionX(), sectionPos.getSectionY(), sectionPos.getSectionZ());
        int lookupLimit = Math.min(LIGHT_GRID_HASH_LOOKUP_LIMIT, lightGridRecordCount);
        for (int probe = 0; probe < lookupLimit; probe++) {
            int index = (start + probe) & (lightGridRecordCount - 1);
            int offset = HEADER_BYTES + (lightCount + index) * RECORD_BYTES;
            if (data.getInt(offset + 28) == 0) {
                return index;
            }
        }
        return -1;
    }

    private static void writeLightGridDirectoryRecord(
            ByteBuffer data,
            int lightCount,
            int directoryIndex,
            UploadedLightSection section) {
        int offset = HEADER_BYTES + (lightCount + directoryIndex) * RECORD_BYTES;
        ChunkSectionPos sectionPos = section.sectionPos();
        data.putInt(offset, sectionPos.getMinX());
        data.putInt(offset + 4, sectionPos.getMinY());
        data.putInt(offset + 8, sectionPos.getMinZ());
        data.putInt(offset + 12, section.firstLight());
        data.putInt(offset + 16, section.lightCount());
        data.putInt(offset + 20, 0);
        data.putInt(offset + 24, 0);
        data.putInt(offset + 28, 1);
    }

    private void logLightGridDirectorySaturation(ChunkSectionPos sectionPos) {
        long now = System.nanoTime();
        if (now - lastLightGridDirectoryWarnNanos >= INFO_LOG_INTERVAL_NANOS) {
            lastLightGridDirectoryWarnNanos = now;
            LOGGER.warn("[Vulkanite] Section light grid hash cluster saturated near {}; table fallback may sample global lights",
                    sectionPos);
        }
    }

    private void logSectionLightUpload(
            int lightCount,
            int lightSectionCount,
            int lightGridRecordCount,
            int uploadBytes,
            long uploadNanos) {
        long now = System.nanoTime();
        if (now - lastTableUploadLogNanos >= INFO_LOG_INTERVAL_NANOS) {
            lastTableUploadLogNanos = now;
            LOGGER.info("[Vulkanite] Section light table upload: lights={}, light sections={}, active sections={}, grid records={}, uploaded={} bytes, upload={} ms, version={}",
                    lightCount, lightSectionCount, activeTables.size(), lightGridRecordCount,
                    uploadBytes, formatMillis(nanosToMillis(uploadNanos)), gpuVersion);
        } else if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[Vulkanite] Section light table upload: lights={}, light sections={}, active sections={}, grid records={}, uploaded={} bytes, upload={} ms, version={}",
                    lightCount, lightSectionCount, activeTables.size(), lightGridRecordCount,
                    uploadBytes, formatMillis(nanosToMillis(uploadNanos)), gpuVersion);
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

    private static int lightGridRecordCount(int activeSectionCount, int lightCount) {
        if (activeSectionCount <= 0 || lightCount <= 0) {
            return 0;
        }
        int desired = roundUpPowerOfTwo(Math.max(MIN_GPU_LIGHT_GRID_RECORDS, activeSectionCount * 2));
        return Math.min(desired, MAX_GPU_LIGHT_GRID_RECORDS);
    }

    private static int lightGridHash(int sectionX, int sectionY, int sectionZ) {
        int hash = sectionX * 0x8da6b343
                ^ sectionY * 0xd8163841
                ^ sectionZ * 0xcb1ab31f;
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        return hash;
    }

    private static int probeWorkerCount() {
        String configured = System.getProperty("vulkanite.probeWorkers");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(1, Math.min(MAX_PROBE_WORKERS, Integer.parseInt(configured)));
            } catch (NumberFormatException e) {
                LOGGER.warn("[Vulkanite] Invalid vulkanite.probeWorkers value '{}'; using automatic worker count",
                        configured);
            }
        }

        int processors = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(MAX_PROBE_WORKERS, processors - 2));
    }

    private static ThreadFactory probeThreadFactory() {
        AtomicInteger id = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable,
                    "Vulkanite section probe worker " + id.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        };
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

    private static long sectionDistanceSquared(ChunkSectionPos a, ChunkSectionPos b) {
        long dx = (long) a.getSectionX() - b.getSectionX();
        long dy = (long) a.getSectionY() - b.getSectionY();
        long dz = (long) a.getSectionZ() - b.getSectionZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static float requestDistanceSquared(ChunkSectionPos sectionPos, ChunkSectionPos cameraSection) {
        if (sectionPos == null || cameraSection == null) {
            return 0.0f;
        }
        return (float) Math.min(sectionDistanceSquared(sectionPos, cameraSection), (long) Float.MAX_VALUE);
    }

    private static boolean sameLights(SectionLightTable left, SectionLightTable right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.lights().equals(right.lights());
    }

    private record DirtyProbeMarks(int total, int neighbor) {
    }

    private record UploadedLightSection(ChunkSectionPos sectionPos, int firstLight, int lightCount) {
    }

    private record ProbeBuildResult(
            ChunkSectionPos sectionPos,
            int buildVersion,
            SectionDirectionalProbePage page,
            Throwable failure) {
    }
}

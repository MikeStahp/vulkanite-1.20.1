package me.cortex.vulkanite.client.rendering.cache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Dedupe and backlog for cache-fill requests.
 */
public final class CacheRequestQueue {
    public enum EnqueueResult {
        ADDED,
        MERGED,
        REJECTED;

        public boolean accepted() {
            return this != REJECTED;
        }
    }

    private final int maxBacklog;
    private final int maxBacklogPerFamily;
    private final int[] familyBacklogs = new int[CacheRequestFamily.values().length];
    private final LinkedHashMap<CacheRequestKey, CacheRequest> backlog = new LinkedHashMap<>();
    private long enqueued;
    private long merged;
    private long drained;
    private long dropped;
    private int lastBatchSize;

    public CacheRequestQueue(int maxBacklog) {
        this.maxBacklog = Math.max(1, maxBacklog);
        this.maxBacklogPerFamily = Math.max(1,
                this.maxBacklog / CacheRequestFamily.values().length);
    }

    public synchronized EnqueueResult enqueue(CacheRequest request) {
        CacheRequest previous = backlog.get(request.key());
        if (previous != null) {
            backlog.put(request.key(), previous.merge(request));
            merged++;
            return EnqueueResult.MERGED;
        }

        boolean familyFull = familyBacklog(request.key().family()) >= maxBacklogPerFamily;
        if (familyFull && request.key().family() == CacheRequestFamily.SECTION_PROBE_CELL) {
            dropped++;
            return EnqueueResult.REJECTED;
        }
        if ((familyFull || backlog.size() >= maxBacklog) && !makeRoomFor(request, familyFull)) {
            dropped++;
            return EnqueueResult.REJECTED;
        }

        backlog.put(request.key(), request);
        familyBacklogs[request.key().family().ordinal()]++;
        enqueued++;
        return EnqueueResult.ADDED;
    }

    public synchronized CacheRequestBatch drainBatch(int maxRequests, int frameIndex) {
        return drainBatch(maxRequests, frameIndex, request -> true);
    }

    public synchronized CacheRequestBatch drainBatch(
            int maxRequests,
            int frameIndex,
            Predicate<CacheRequest> acceptedByConsumer) {
        if (maxRequests <= 0 || backlog.isEmpty()) {
            lastBatchSize = 0;
            return new CacheRequestBatch(List.of(), backlog.size(), snapshot());
        }

        List<CacheRequest> sorted = backlog.values().stream()
                .filter(acceptedByConsumer)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        sorted.sort(Comparator
                .comparingDouble((CacheRequest request) -> request.priorityScore(frameIndex))
                .reversed()
                .thenComparing(CacheRequest::key));

        int batchSize = Math.min(maxRequests, sorted.size());
        ArrayList<CacheRequest> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            CacheRequest request = sorted.get(i);
            backlog.remove(request.key());
            familyBacklogs[request.key().family().ordinal()]--;
            batch.add(request);
        }

        drained += batchSize;
        lastBatchSize = batchSize;
        return new CacheRequestBatch(batch, backlog.size(), snapshot());
    }

    public synchronized int discardIf(Predicate<CacheRequest> predicate) {
        int removed = 0;
        var iterator = backlog.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CacheRequestKey, CacheRequest> entry = iterator.next();
            if (!predicate.test(entry.getValue())) {
                continue;
            }
            familyBacklogs[entry.getKey().family().ordinal()]--;
            iterator.remove();
            removed++;
        }
        dropped += removed;
        return removed;
    }

    public synchronized CacheRequestStats snapshot() {
        return new CacheRequestStats(backlog.size(), lastBatchSize, enqueued, merged, drained, dropped);
    }

    public synchronized void clear() {
        backlog.clear();
        java.util.Arrays.fill(familyBacklogs, 0);
        lastBatchSize = 0;
    }

    private boolean makeRoomFor(CacheRequest request, boolean restrictToFamily) {
        Map.Entry<CacheRequestKey, CacheRequest> weakest = null;
        float weakestScore = Float.POSITIVE_INFINITY;
        for (Map.Entry<CacheRequestKey, CacheRequest> entry : backlog.entrySet()) {
            if (restrictToFamily && entry.getKey().family() != request.key().family()) {
                continue;
            }
            if (!restrictToFamily
                    && entry.getKey().family() == CacheRequestFamily.SECTION_PROBE_CELL) {
                continue;
            }
            float score = entry.getValue().staticPriorityScore();
            if (score < weakestScore) {
                weakestScore = score;
                weakest = entry;
            }
        }

        if (weakest == null || request.staticPriorityScore() <= weakestScore) {
            return false;
        }

        backlog.remove(weakest.getKey());
        familyBacklogs[weakest.getKey().family().ordinal()]--;
        dropped++;
        return true;
    }

    private int familyBacklog(CacheRequestFamily family) {
        return familyBacklogs[family.ordinal()];
    }
}

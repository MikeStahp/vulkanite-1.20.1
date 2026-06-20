package me.cortex.vulkanite.client.rendering.cache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dedupe and backlog for cache-fill requests.
 */
public final class CacheRequestQueue {
    private final int maxBacklog;
    private final LinkedHashMap<CacheRequestKey, CacheRequest> backlog = new LinkedHashMap<>();
    private long enqueued;
    private long merged;
    private long drained;
    private long dropped;
    private int lastBatchSize;

    public CacheRequestQueue(int maxBacklog) {
        this.maxBacklog = Math.max(1, maxBacklog);
    }

    public synchronized boolean enqueue(CacheRequest request) {
        CacheRequest previous = backlog.get(request.key());
        if (previous != null) {
            backlog.put(request.key(), previous.merge(request));
            merged++;
            return false;
        }

        if (backlog.size() >= maxBacklog && !makeRoomFor(request)) {
            dropped++;
            return false;
        }

        backlog.put(request.key(), request);
        enqueued++;
        return true;
    }

    public synchronized CacheRequestBatch drainBatch(int maxRequests, int frameIndex) {
        if (maxRequests <= 0 || backlog.isEmpty()) {
            lastBatchSize = 0;
            return new CacheRequestBatch(List.of(), backlog.size(), snapshot());
        }

        List<CacheRequest> sorted = new ArrayList<>(backlog.values());
        sorted.sort(Comparator
                .comparingDouble((CacheRequest request) -> request.priorityScore(frameIndex))
                .reversed()
                .thenComparing(CacheRequest::key));

        int batchSize = Math.min(maxRequests, sorted.size());
        ArrayList<CacheRequest> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            CacheRequest request = sorted.get(i);
            backlog.remove(request.key());
            batch.add(request);
        }

        drained += batchSize;
        lastBatchSize = batchSize;
        return new CacheRequestBatch(batch, backlog.size(), snapshot());
    }

    public synchronized CacheRequestStats snapshot() {
        return new CacheRequestStats(backlog.size(), lastBatchSize, enqueued, merged, drained, dropped);
    }

    public synchronized void clear() {
        backlog.clear();
        lastBatchSize = 0;
    }

    private boolean makeRoomFor(CacheRequest request) {
        Map.Entry<CacheRequestKey, CacheRequest> weakest = null;
        float weakestScore = Float.POSITIVE_INFINITY;
        for (Map.Entry<CacheRequestKey, CacheRequest> entry : backlog.entrySet()) {
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
        dropped++;
        return true;
    }
}

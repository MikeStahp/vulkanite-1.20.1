package me.cortex.vulkanite.client.rendering.cache;

import java.util.List;

public record CacheRequestBatch(
        List<CacheRequest> requests,
        int backlogRemaining,
        CacheRequestStats stats) {
    public CacheRequestBatch {
        requests = List.copyOf(requests);
    }

    public boolean hasWork() {
        return !requests.isEmpty();
    }

    public int size() {
        return requests.size();
    }
}

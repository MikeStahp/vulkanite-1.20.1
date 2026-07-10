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

    /**
     * The bounded raygen consumes section, diffuse, specular, and surface-light request
     * buffers in parallel at the same launch index. Reflection and refraction
     * share the specular buffer, so the required launch width is the largest
     * populated buffer rather than the sum of every request family.
     */
    public int parallelDispatchWidth() {
        int section = 0;
        int diffuse = 0;
        int specular = 0;
        int surfaceDirect = 0;
        for (CacheRequest request : requests) {
            switch (request.key().family()) {
                case SECTION_PROBE_CELL -> section++;
                case DIFFUSE_RADIANCE -> diffuse++;
                case REFLECTION, REFRACTION -> specular++;
                case SURFACE_DIRECT_LIGHT -> surfaceDirect++;
            }
        }
        return Math.max(Math.max(section, diffuse), Math.max(specular, surfaceDirect));
    }
}

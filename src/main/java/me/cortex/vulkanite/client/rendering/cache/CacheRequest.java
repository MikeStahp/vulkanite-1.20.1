package me.cortex.vulkanite.client.rendering.cache;

/**
 * One cache-fill or validation request before it is assigned to an RTX budget.
 */
public record CacheRequest(
        CacheRequestKey key,
        CacheRequestSource source,
        int frameIndex,
        int priorityHint,
        float visibility,
        float error,
        float luma,
        float distanceSquared) {
    public CacheRequest {
        if (key == null) {
            throw new IllegalArgumentException("Cache request key must not be null");
        }
        source = source == null ? CacheRequestSource.VALIDATION : source;
        visibility = saturateFinite(visibility);
        error = saturateFinite(error);
        luma = Math.max(0.0f, finiteOrZero(luma));
        distanceSquared = Math.max(0.0f, finiteOrZero(distanceSquared));
    }

    public static CacheRequest sectionProbeCell(
            CacheRequestKey key,
            CacheRequestSource source,
            int frameIndex,
            float luma,
            float distanceSquared) {
        return new CacheRequest(key, source, frameIndex, 0, 0.65f, 0.5f, luma, distanceSquared);
    }

    public CacheRequest merge(CacheRequest other) {
        if (!key.equals(other.key())) {
            throw new IllegalArgumentException("Cannot merge different cache request keys");
        }

        float currentScore = staticPriorityScore();
        float otherScore = other.staticPriorityScore();
        CacheRequestSource mergedSource = otherScore >= currentScore ? other.source() : source;
        int mergedFrameIndex = Math.min(frameIndex, other.frameIndex());
        return new CacheRequest(
                key,
                mergedSource,
                mergedFrameIndex,
                Math.max(priorityHint, other.priorityHint()),
                Math.max(visibility, other.visibility()),
                Math.max(error, other.error()),
                Math.max(luma, other.luma()),
                Math.min(distanceSquared, other.distanceSquared()));
    }

    public float priorityScore(int currentFrameIndex) {
        int ageFrames = Math.max(0, currentFrameIndex - frameIndex);
        return staticPriorityScore() + Math.min(ageFrames, 240) * 0.1f;
    }

    public float staticPriorityScore() {
        float nearWeight = 1.0f / (1.0f + (float) Math.sqrt(distanceSquared) * 0.08f);
        float lumaWeight = (float) Math.log1p(luma);
        return priorityHint * 1000.0f
                + visibility * 120.0f
                + error * 80.0f
                + lumaWeight * 24.0f
                + nearWeight * 48.0f;
    }

    private static float saturateFinite(float value) {
        return Math.max(0.0f, Math.min(1.0f, finiteOrZero(value)));
    }

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }
}

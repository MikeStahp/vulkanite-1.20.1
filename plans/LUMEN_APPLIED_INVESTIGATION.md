# Lumen-Inspired Lighting Investigation

## Sources

- Epic Lumen technical details: https://dev.epicgames.com/documentation/en-us/unreal-engine/lumen-technical-details-in-unreal-engine
- Epic Lumen GI/reflections overview: https://dev.epicgames.com/documentation/en-us/unreal-engine/lumen-global-illumination-and-reflections-in-unreal-engine

## What Lumen Does That Maps To Vulkanite

Lumen is not one technique. The important pattern is a hierarchy:

- Shade at full resolution, but compute indirect lighting at a lower resolution.
- Use a cache for most lighting lookups, because evaluating lighting at every ray hit is too expensive.
- Use screen traces first where they can cheaply hide mismatches between the rendered scene and the cached scene.
- Use more expensive hit lighting only when quality requires it, especially for reflections or cache misses.
- Keep cache updates amortized over frames, and expose visualization/debug modes for missing coverage.

In Vulkanite, the closest current match is the section directional probe volume:

- Java builds per-section `8x8x8` probe pages from explicit Minecraft section lights.
- The GPU samples binding `23` as a low-resolution directional cache.
- Binding `22` keeps the explicit section-light table available as a more expensive fallback/reference path.
- The raygen already has temporal shadow reuse, DLSS/RR sidecars, sparse RT validation hooks, and probe debug views.

## Applied Change

The Lumen-inspired change in this pass is probe coverage normalization.

Before this change, trilinear probe sampling treated missing neighboring pages or zero-confidence probe corners as black lighting. That is useful for exposing missing cache data, but it also creates section-boundary dimming and visible page-shaped transitions.

The updated gather normalizes radiance over mapped/confident probe corners, then scales the returned confidence by the amount of covered interpolation weight. The result is closer to Lumen's cache behavior:

- lighting remains stable across partial cache coverage;
- confidence still drops when the cache is incomplete;
- sparse RT validation can still damp suspected leaks;
- debug confidence remains meaningful.

The setting is exposed as `SECTION_LIGHT_PROBE_COVERAGE_NORMALIZATION`, enabled by default.

## Next Useful Lumen Pieces

1. Add a screen-space local-light reuse pass.
   This would sample current-frame depth/color/normal before the probe cache, similar to Lumen screen traces. It can hide cache mismatch at visible surfaces without increasing probe resolution.

2. Add a small radiance history image for probe final gather.
   The current Java-side temporal history stabilizes packed pages, but a screen-space lighting history could reuse final gathered blocklight with disocclusion rejection, closer to Lumen's final gather cache behavior.

3. Make sparse hit lighting adaptive.
   `SECTION_LIGHT_SPARSE_RT_CORRECTION` should become confidence and motion aware. High-confidence probe samples should avoid ray queries; low-confidence, high-luma samples should trace one or two explicit emitters.

4. Track cache coverage explicitly.
   Lumen has Surface Cache coverage visualization. Vulkanite now has confidence/debug views, but a per-page coverage metric in the probe header would make stale/missing pages visible and tunable.

5. Extend the cache beyond explicit lights.
   Lumen propagates emissive material lighting through the final gather. Vulkanite can approximate that by adding selected emissive surface hits into section probes or a separate low-frequency radiance cache.

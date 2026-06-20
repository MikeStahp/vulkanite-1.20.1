# MegaLights-Inspired Lighting Investigation

## Sources

- Epic MegaLights documentation: https://dev.epicgames.com/documentation/unreal-engine/megalights-in-unreal-engine
- Epic MegaLights shadow method API notes: https://dev.epicgames.com/documentation/unreal-engine/API/Runtime/Engine/EMegaLightsShadowMethod__Type

## What MegaLights Does That Maps To Vulkanite

MegaLights is Unreal's stochastic direct-lighting path for many dynamic local lights. The transferable pattern is:

- use a fixed number of light samples per pixel;
- choose samples from important local lights instead of looping every light;
- keep shadow cost mostly fixed with ray tracing;
- use screen traces and ray tracing scene fallback where appropriate;
- expose light complexity/debug views because bad light bounds and hidden lights steal samples.

Vulkanite already has the building blocks for a smaller version of this:

- binding `22` uploads explicit section lights;
- binding `23` provides a low-resolution section directional probe cache;
- sparse RT validation can trace a tiny number of rays toward suspicious explicit lights;
- debug source markers and probe/table comparison can reveal cache/source mismatch.

## Applied Change

Before this change, the shader table fallback and sparse probe validation only scanned the first `SECTION_LIGHT_SAMPLE_LIMIT` uploaded lights. Since the Java upload is sorted by section position, that made the fallback biased toward whichever sections happen to be first, not lights important to the current pixel.

The updated path adds `SECTION_LIGHT_TABLE_STOCHASTIC_SAMPLING`, enabled by default. When the uploaded light count exceeds the per-pixel budget, the shader can take world-stable stratified candidates across the full uploaded table instead of the first N records.

The follow-up grid path appends a section-local light directory after the binding `22` light records. Header `w` stores the directory record count, and the shader hashes the shaded section plus its 26 neighbors to find compact local light ranges before falling back to the global table. This is not full MegaLights ray guiding, but it gives the direct-light fallback the important MegaLights property Vulkanite can support today: fixed cost biased toward local lights instead of upload order.

This affects:

- `sampleSectionLightTable`, used by the experimental table lighting fallback and probe/table debug comparison;
- `sampleSectionLightSparseVisibilityRatio`, used by sparse RT probe-leak correction when enabled.

## What Full MegaLights Would Need Here

1. Replace the section directory with a screen/clustered light list.
   The implemented section grid is deliberately coarse. A full MegaLights-style path would build per-screen-tile or per-cluster candidate lists and score lights by projected influence, not just nearby section membership.

2. Add per-pixel light importance/reservoir state.
   MegaLights uses ray guiding to send more samples to lights likely to matter and fewer samples to hidden/weak lights. Vulkanite could approximate this with a small screen-space reservoir keyed by world position, normal, and material.

   Voxel Base points at a cheap Minecraft-specific version: retain recently
   visible emissive voxel positions, dedupe them per workgroup, occasionally
   discover new emitters, and spend validation rays on the retained candidates.
   That should feed the same local-light reservoir/history path rather than
   becoming a second independent light picker.

3. Add direct-light denoising/history.
   Fixed-budget stochastic lighting needs reconstruction. DLSS Ray Reconstruction helps final radiance, but a local direct-light history with normal/depth rejection would reduce shimmer before upscaling.

4. Improve light bounds at extraction time.
   MegaLights quality depends heavily on tight attenuation bounds. `SectionLightExtractor` should keep radii conservative and avoid huge bounds for weak or hidden Minecraft emitters.

5. Add light complexity visualization.
   A debug view that lists/scores the most important section lights for a picked pixel would match MegaLights' most useful production diagnostic.

6. Replace the exact section directory with an influence-sorted regional list.
   Rethinking Voxels does this for voxel lights: scatter each emitter into
   affected coarse cells, then cap each cell to the best candidates. Vulkanite's
   binding 22 grid should evolve in that direction so the fixed sample budget is
   spent on lights that can actually affect the shaded point.

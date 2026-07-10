# Hybrid Vulkan Ray-Tracing Acceleration Plan

## Project target

Implement a hybrid ray-tracing backend for the existing Minecraft 1.20.1 Vulkan sidecar renderer.

The target architecture is:

* Existing raster G-buffer for primary visibility.
* RT-core BVH traversal between chunk sections and occupied voxel bricks.
* Short software DDA only inside the final candidate brick.
* Triangle BLAS geometry for entities, cutouts, fluids, particles, and irregular block models.
* Procedural AABB geometry for regular opaque full-cube terrain.
* GPU-driven radiance-cache request selection after the procedural traversal path is stable.

Do not assume access to Mojang’s later Vulkan renderer or render graph.

---

# Instructions for the coding AI

## Execution rules

1. Work on exactly one phase at a time.
2. Start with the earliest phase containing unchecked tasks.
3. Inspect the existing repository before changing code.
4. Reuse existing abstractions where practical.
5. Do not replace working systems unnecessarily.
6. Keep the triangle path operational as a fallback until the relevant validation gate passes.
7. Do not mark a task complete merely because the code compiles.
8. Mark a task with `[x]` only after its stated verification requirement succeeds.
9. Leave incomplete tasks as `[ ]`.
10. If a task is blocked, leave it unchecked and add a short `BLOCKED:` note underneath it.
11. Do not begin the next phase until every required gate in the current phase passes.
12. Optional tasks may remain unchecked without blocking later required phases.
13. Update this document after every implementation pass.
14. Preserve prior completed checkmarks unless a regression invalidates them.
15. If a completed requirement becomes broken, change `[x]` back to `[ ]` and explain why.
16. Never hide validation errors, device-feature failures, or benchmark regressions.
17. Keep compatibility fallbacks for unsupported Vulkan features.
18. Prefer explicit capability checks over assumptions based on vendor or GPU model.
19. Do not enable the hybrid backend by default until the final rollout phase.
20. At the end of each response, report:

    * Current phase
    * Tasks completed
    * Files modified
    * Tests executed
    * Validation results
    * Known blockers
    * Next unchecked task

## Required status format

Use this format after every coding pass:

```text
Current phase:
Completed this pass:
Files modified:
Tests executed:
Vulkan validation:
Benchmark result:
Blockers:
Next task:
```

## Checklist syntax

```text
[ ] Not completed
[x] Completed and verified
```

A checkmark means the implementation exists and its verification gate passed.

---

# Current repository foundation

The implementation should verify these claims against the actual repository before relying on them.

* Sodium chunk meshes feed per-section triangle BLAS objects.
* Entities use a separate triangle hit group and SBT offset.
* `SectionLightExtractor` scans logical block state.
* `SectionLightExtractor` produces a 4,096-bit opaque-block field.
* Hierarchical occupancy mips are available.
* `VoxelBrickGeometry` converts occupancy into tightly packed `VkAabbPositionsKHR` records.
* `VoxelBrickGeometry` creates fixed-stride local-DDA payloads.
* Brick sizes of 4, 8, and 16 cells are supported.
* Vulkan queue-family selection validates required queue capabilities.
* `DeviceCapabilities` records supported features, but enabled backend paths are selected elsewhere and are not represented explicitly yet.
* Startup reports the supported acceleration tier, not a separately selected backend tier.

Phase 0 audit evidence and the current ownership map are recorded in
[`hybrid-acceleration-baseline/README.md`](hybrid-acceleration-baseline/README.md).

If any claim is incorrect, update this section before proceeding.

---

# Phase 0 — Repository audit and baseline capture

## Objective

Establish a known-good baseline and document the current renderer before introducing procedural geometry.

## Tasks

* [x] Verify the repository builds from a clean checkout.
* [x] Record the required JDK, Gradle, LWJGL, Vulkan SDK, and shader compiler versions.
* [x] Identify the classes responsible for chunk BLAS creation.
* [x] Identify the classes responsible for entity BLAS creation.
* [x] Identify TLAS construction and update code.
* [x] Identify SBT construction and hit-group indexing.
* [x] Identify the bindless geometry descriptor set.
* [x] Identify shadow-ray dispatch code.
* [x] Identify reflection-ray dispatch code.
* [x] Identify radiance-cache request generation.
* [x] Identify device capability detection.
* [x] Identify queue-family selection and queue submission code.
* [ ] Verify that Vulkan validation layers can be enabled.
  * BLOCKED: The opt-in runtime toggle now requests
    `VK_LAYER_KHRONOS_validation` and `VK_EXT_debug_utils`, checks both before
    instance creation, and installs a persistent debug callback. A 2026-07-10
    client smoke run confirmed the requested layer loaded, but the Java 21
    process then crashed inside `vkCreateInstance` through the JDK-local
    `msvcp140.dll`. Disabling all implicit layers did not change the failure.
    A control run with the code toggle off and
    `VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation` reproduced the same native
    crash, while `vulkaninfo --summary` completed with that layer. A compatible
    JDK/Vulkan-validation-layer runtime pairing is required before the
    renderer-level verification can be checked off.
* [ ] Capture a triangle-only reference screenshot set.
  * BLOCKED: Existing screenshots are not labelled with the required scenes or triangle-only settings. An interactive benchmark-world capture is required.
* [ ] Capture triangle-only frame timing in the traversal benchmark world.
* [ ] Capture triangle-only VRAM usage.
* [ ] Capture triangle-only BLAS build and update timing.
* [ ] Capture triangle-only ray-tracing timing.
  * BLOCKED: The required benchmark world, fixed camera route, and triangle-only runtime capture have not been supplied or recorded.
* [x] Add a short architecture map to the repository documentation.

## Required reference scenes

Capture the following scenes:

* [ ] Dense stone terrain.
* [ ] Sparse caves.
* [ ] Forest with leaves and cutouts.
* [ ] Water and fluids.
* [ ] Modded or irregular block models.
* [ ] Many entities.
* [ ] Chunk-border view.
* [ ] Negative world coordinates.
* [ ] Rapid chunk loading while moving.
* [ ] Repeated block placement and destruction.

## Phase gate

Do not continue until:

* [x] Clean baseline build succeeds.
* [ ] Existing renderer runs with Vulkan validation enabled.
* [ ] Reference screenshots and performance numbers are recorded.
* [x] Current SBT and TLAS ownership are documented.

---

# Phase 1 — Voxel-brick data correctness

## Objective

Validate voxel partitioning and packed payload layout independently of Vulkan acceleration-structure traversal.

## Representation contract

For every non-empty section:

* Occupancy covers exactly 16 × 16 × 16 local cells.
* Bit order is deterministic and documented.
* Brick AABBs use section-local coordinates.
* Packed payload entries have a fixed stride.
* Each AABB primitive maps to exactly one payload entry.
* `gl_PrimitiveID` will be usable as the payload index.
* Empty bricks produce no AABB record.
* Occupied voxels cannot be lost during partitioning.

## Tasks

* [ ] Document the 4,096-bit occupancy bit order.
* [ ] Document section-local coordinate orientation.
* [ ] Document brick ordering.
* [ ] Document payload field layout.
* [ ] Document payload byte alignment.
* [ ] Document AABB coordinate conventions.
* [ ] Add tests for completely empty sections.
* [ ] Add tests for completely full sections.
* [ ] Add tests for one occupied corner voxel.
* [ ] Add tests for all eight section corners.
* [ ] Add tests for section-boundary voxels.
* [ ] Add tests for checkerboard occupancy.
* [ ] Add tests for thin walls.
* [ ] Add tests for isolated voxels.
* [ ] Add tests for 4-cell bricks.
* [ ] Add tests for 8-cell bricks.
* [ ] Add tests for 16-cell bricks.
* [ ] Verify AABB minimum coordinates are less than or equal to maximum coordinates.
* [ ] Verify AABBs remain within section-local bounds.
* [ ] Verify packed payload offsets satisfy shader alignment requirements.
* [ ] Verify each generated AABB has a matching payload.
* [ ] Verify no payload exists without a corresponding AABB.
* [ ] Verify deterministic output for identical occupancy input.
* [ ] Add randomized property tests for occupancy preservation.
* [ ] Add tests for negative world section coordinates after instance translation.
* [ ] Add tests for sections above and below world origin.

## Recommended invariants

Add assertions for:

```text
aabbCount == payloadCount
payloadOffset % requiredAlignment == 0
brickSize ∈ {4, 8, 16}
sectionCellCount == 4096
every occupied cell belongs to exactly one emitted brick
every emitted brick contains at least one occupied cell
```

## Phase gate

Do not continue until:

* [ ] All voxel-brick unit tests pass.
* [ ] Randomized occupancy tests pass.
* [ ] Packed-buffer layout is documented.
* [ ] No ambiguity remains about bit order or coordinate space.

---

# Phase 2 — Procedural AABB BLAS creation

## Objective

Build procedural AABB BLAS objects without using them for production rendering.

## Initial design

Use one AABB BLAS per non-empty section.

For the MVP:

* One `VkAccelerationStructureGeometryKHR` per section BLAS.
* Geometry type is `VK_GEOMETRY_TYPE_AABBS_KHR`.
* Multiple AABB primitives may exist inside that geometry.
* Primitive index corresponds to packed brick payload index.
* The section transform remains in the TLAS instance.

## Required buffer usage

The AABB input buffer must include the appropriate usage flags:

```text
VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
```

The payload buffer should include:

```text
VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
```

Add transfer usage flags if staging or copies require them.

## Tasks

* [ ] Extend the async BLAS job model with optional procedural voxel-brick input.
* [ ] Extend the async BLAS result model with an optional procedural BLAS.
* [ ] Upload tightly packed `VkAabbPositionsKHR` records.
* [ ] Upload fixed-stride brick payloads.
* [ ] Query procedural BLAS build sizes.
* [ ] Allocate procedural BLAS storage.
* [ ] Allocate or reuse scratch storage.
* [ ] Build one procedural AABB BLAS per non-empty section.
* [ ] Skip procedural BLAS creation for empty sections.
* [ ] Assign readable debug names to buffers and acceleration structures.
* [ ] Track procedural BLAS lifetime with section lifetime.
* [ ] Destroy procedural BLAS resources on chunk unload.
* [ ] Destroy procedural BLAS resources on world change.
* [ ] Destroy procedural BLAS resources during shutdown.
* [ ] Rebuild the procedural BLAS after relevant opaque occupancy changes.
* [ ] Avoid rebuilding procedural BLAS for changes that do not affect procedural occupancy.
* [ ] Add capability checks for required ray-tracing features.
* [ ] Keep the backend disabled when required procedural RT support is unavailable.
* [ ] Report procedural BLAS creation statistics in debug mode.
* [ ] Report AABB count per section.
* [ ] Report procedural BLAS memory usage.
* [ ] Verify Vulkan object lifetime ordering.

## Validation

* [ ] Vulkan validation reports no acceleration-structure build errors.
* [ ] Empty sections create no procedural BLAS.
* [ ] Full sections create the expected number of AABB primitives.
* [ ] Chunk unload releases procedural resources.
* [ ] Repeated rebuilds do not leak memory.
* [ ] World changes do not retain stale procedural BLAS references.
* [ ] Shutdown reports no live Vulkan objects from this path.

## Phase gate

Do not continue until:

* [ ] Procedural BLAS objects build successfully.
* [ ] Vulkan validation reports no procedural-build errors.
* [ ] Resource lifetime stress tests pass.
* [ ] Memory usage remains stable over repeated rebuild cycles.

---

# Phase 3 — Debug-only procedural TLAS and hit group

## Objective

Trace rays against procedural bricks without changing authoritative lighting results.

## TLAS design

Build a separate debug-only procedural TLAS containing opaque voxel-section instances.

For the MVP:

```text
Procedural debug TLAS
└── Section instance
    └── AABB BLAS
        └── Occupied brick primitives
```

The triangle TLAS remains authoritative.

## SBT contract

Add a procedural hit group, initially at the intended SBT record index.

Document all indexing assumptions.

For the simple layout:

```text
geometryCountPerProceduralBlas = 1
instanceShaderBindingTableRecordOffset = proceduralHitGroupIndex
raySbtRecordOffset = 0
raySbtRecordStride = existing ray-type stride
```

The exact index must follow the repository’s existing SBT organization.

## Tasks

* [ ] Add a procedural intersection shader such as `ray0_2.rint`.
* [ ] Add a minimal procedural closest-hit shader for debugging.
* [ ] Add the procedural hit group to the ray-tracing pipeline.
* [ ] Add the procedural hit group to SBT construction.
* [ ] Add explicit assertions for SBT record offsets.
* [ ] Add explicit assertions for geometry type and hit-group compatibility.
* [ ] Build a second procedural section TLAS.
* [ ] Use the correct instance SBT record offset.
* [ ] Bind the packed payload through the existing bindless geometry system.
* [ ] Make the payload address or descriptor discoverable from the section instance.
* [ ] Ensure `gl_PrimitiveID` indexes the correct brick payload.
* [ ] Ensure section-local ray coordinates are reconstructed correctly.
* [ ] Implement exact brick-local DDA.
* [ ] Call `reportIntersectionEXT` only for an occupied voxel hit.
* [ ] Report the exact voxel hit distance.
* [ ] Return a debug block-local hit position.
* [ ] Return a debug face normal.
* [ ] Return a debug local-cell index.
* [ ] Handle rays starting inside a candidate AABB.
* [ ] Handle rays parallel to one or more brick axes.
* [ ] Handle zero and near-zero direction components safely.
* [ ] Handle entry exactly on a voxel boundary.
* [ ] Handle section and brick boundaries deterministically.
* [ ] Add a debug view that visualizes procedural hit distance.
* [ ] Add a debug view that visualizes procedural face normals.
* [ ] Add a debug view that visualizes brick IDs.
* [ ] Add a debug view that visualizes local voxel IDs.

## Important correctness rule

Hardware AABB traversal only identifies a candidate.

The intersection shader must perform exact local DDA and must not report an intersection unless it finds an occupied voxel.

A candidate AABB alone is not an occluder.

## Validation scenes

* [ ] Single block in an empty section.
* [ ] Thin one-block wall.
* [ ] Hollow cube.
* [ ] Dense solid section.
* [ ] Stair-step occupancy.
* [ ] Rays beginning inside occupied cells.
* [ ] Rays beginning inside empty parts of occupied bricks.
* [ ] Rays grazing cell boundaries.
* [ ] Rays crossing section boundaries.
* [ ] Rays entering from negative world directions.

## Phase gate

Do not continue until:

* [ ] Procedural TLAS builds successfully.
* [ ] SBT indexing assertions pass.
* [ ] Vulkan validation reports no geometry-type or SBT mismatch.
* [ ] Debug hit positions and normals match expected voxel geometry.
* [ ] Rays inside AABBs are handled correctly.
* [ ] No production lighting result depends on this path yet.

---

# Phase 4 — Triangle versus procedural shadow validation

## Objective

Compare procedural visibility with the existing triangle path before changing production shadows.

## Comparison categories

Track at least these four outcomes:

```text
Triangle hit, procedural hit
Triangle hit, procedural miss
Triangle miss, procedural hit
Triangle miss, procedural miss
```

Also track:

```text
Both hit but distance differs
Both hit and distance agrees
```

Interpretation:

* Triangle hit, procedural miss: dangerous false negative and possible light leak.
* Triangle miss, procedural hit: conservative false positive and possible excess darkness.
* Both hit with distance mismatch: likely coordinate, geometry, or classification problem.

## Tasks

* [ ] Add a debug dual-trace mode.
* [ ] Trace the same ray against both TLAS paths.
* [ ] Add atomic counters for all mismatch categories.
* [ ] Add a hit-distance tolerance.
* [ ] Record maximum observed hit-distance difference.
* [ ] Record average hit-distance difference.
* [ ] Record mismatch counts by brick size.
* [ ] Record mismatch counts by ray direction octant.
* [ ] Record mismatch counts for rays beginning inside AABBs.
* [ ] Record mismatch counts by material classification.
* [ ] Record mismatch counts by section coordinate.
* [ ] Add a screen overlay with mismatch totals.
* [ ] Add a visualization for dangerous procedural misses.
* [ ] Add a visualization for conservative procedural extra hits.
* [ ] Add optional structured debug logging for sampled mismatches.
* [ ] Add a configurable comparison sampling rate.
* [ ] Support 100% comparison in debug captures.
* [ ] Support a low sampling rate for extended gameplay tests.
* [ ] Compare sun visibility rays.
* [ ] Compare local-light visibility rays.
* [ ] Verify correct finite ray minimum and maximum distances.
* [ ] Verify equivalent ray-origin bias between both paths.
* [ ] Verify equivalent opaque classification between both paths.

## Required stress scenarios

* [ ] Rebuild a chunk repeatedly while comparison mode is active.
* [ ] Unload chunks while rays are in flight.
* [ ] Reload shaders repeatedly.
* [ ] Change worlds.
* [ ] Exit to title.
* [ ] Shut down the game.
* [ ] Place and remove full opaque blocks rapidly.
* [ ] Move rapidly across chunk boundaries.
* [ ] Test negative coordinates.
* [ ] Test high and low world sections.

## Phase gate

Do not continue until:

* [ ] Dangerous procedural false negatives are understood and resolved.
* [ ] Remaining conservative false positives are documented and accepted.
* [ ] Hit-distance disagreement is within the defined tolerance.
* [ ] Comparison mode survives all lifecycle stress tests.
* [ ] Vulkan validation remains clean.

---

# Phase 5 — Production procedural opaque shadows

## Objective

Use procedural AABB traversal for opaque full-cube shadow occlusion while retaining triangle geometry for special cases.

## Shadow ray optimization

For opaque binary visibility, prefer:

```text
gl_RayFlagsOpaqueEXT
gl_RayFlagsTerminateOnFirstHitEXT
gl_RayFlagsSkipClosestHitShaderEXT
```

The exact shader-language spelling should match the existing shader toolchain.

Suggested behavior:

* Initialize the visibility payload as occluded.
* Let the miss shader mark the ray visible.
* Let the intersection shader report only real voxel hits.
* Skip closest-hit execution in the production path.

Keep the debug closest-hit shader available behind a debug option.

## Tasks

* [ ] Add a production intersection-only procedural hit group.
* [ ] Route opaque sun visibility through the procedural path.
* [ ] Route opaque local-light visibility through the procedural path.
* [ ] Use terminate-on-first-hit for binary visibility.
* [ ] Skip closest-hit shading for production opaque visibility.
* [ ] Preserve the triangle shadow path as a runtime fallback.
* [ ] Add a configuration option selecting triangle or procedural opaque shadows.
* [ ] Add startup reporting for the selected shadow backend.
* [ ] Keep sampled mismatch validation available.
* [ ] Add fallback behavior after procedural pipeline creation failure.
* [ ] Add fallback behavior after procedural BLAS allocation failure.
* [ ] Add fallback behavior on unsupported devices.
* [ ] Verify shadow bias and self-intersection behavior.
* [ ] Verify no visible light leaks from full opaque blocks.
* [ ] Verify no stale shadows after block removal.
* [ ] Verify no missing shadows after block placement.

## Initial production structure

At this phase, two traces may temporarily be used:

```text
Procedural TLAS:
  regular opaque full cubes

Triangle TLAS:
  cutouts
  panes
  fluids when applicable
  irregular block models
  entities
  particles when applicable
```

Final visibility is the logical union of both results.

This temporary arrangement is acceptable for correctness but should later be replaced by a unified hybrid shadow TLAS.

## Phase gate

Do not continue until:

* [ ] Procedural opaque shadows work in production mode.
* [ ] Triangle fallback remains operational.
* [ ] No dangerous mismatch regression appears in sampled validation.
* [ ] Lifecycle stress tests pass.
* [ ] Vulkan validation remains clean.

---

# Phase 6 — Ray-role-aware material classification

## Objective

Prevent blocks from being accidentally omitted, duplicated incorrectly, or assigned to an unsuitable representation.

## Do not use one global backend flag

Classification must be defined per ray role.

Suggested representation:

```text
BlockRayClassification {
    shadowRepresentation;
    reflectionRepresentation;
    giRepresentation;
    emissionRepresentation;
}
```

Possible flags:

```text
VOXEL_SHADOW
TRIANGLE_SHADOW
VOXEL_REFLECTION
TRIANGLE_REFLECTION
VOXEL_GI
TRIANGLE_GI
EMISSIVE
NON_OCCLUDING
COMPOSITE_GEOMETRY
```

## Classification rules

* Regular opaque full cube:

  * Procedural shadow representation.
  * Triangle reflection representation until procedural reflection shading exists.
* Cutout blocks:

  * Triangle shadow representation.
  * Triangle reflection representation.
* Leaves:

  * Triangle shadow representation unless an explicit conservative voxel mode is selected.
* Panes and fences:

  * Triangle representation.
* Fluids:

  * Specialized triangle or procedural fluid representation.
* Entities:

  * Triangle representation.
* Particles:

  * Existing transient representation or ignored by ray role.
* Air:

  * No geometry representation.
* Emissive blocks:

  * Geometry classification plus emission metadata.
* Composite or multipart blocks:

  * Explicit composite classification.

## Tasks

* [ ] Add ray-role-aware classification data.
* [ ] Populate classification during `SectionLightExtractor.scan` or the appropriate extraction stage.
* [ ] Distinguish opaque full cubes from merely opaque-looking models.
* [ ] Account for cutout render layers.
* [ ] Account for translucent render layers.
* [ ] Account for fluids.
* [ ] Account for block entities.
* [ ] Account for modded irregular models.
* [ ] Account for waterlogged blocks.
* [ ] Account for multipart models.
* [ ] Add validation that every opaque shadow caster has exactly one authoritative shadow representation.
* [ ] Allow both representations only for explicit comparison or composite cases.
* [ ] Add validation that air and non-occluding blocks have no shadow representation.
* [ ] Add validation that reflection-visible blocks retain a reflection representation.
* [ ] Add debug statistics by classification.
* [ ] Add a debug visualization of representation ownership.
* [ ] Add warnings for unclassified visible blocks.
* [ ] Add warnings for accidental duplicate authoritative ownership.
* [ ] Add unit tests for vanilla block categories.
* [ ] Add extension points or fallback behavior for unknown modded blocks.

## Phase gate

Do not continue until:

* [ ] Classification tests pass.
* [ ] Unknown or modded blocks have safe fallback behavior.
* [ ] No block can silently disappear from all relevant ray representations.
* [ ] Reflection geometry has not yet been removed without a procedural replacement.

---

# Phase 7 — Unified hybrid shadow TLAS

## Objective

Replace the temporary double-trace approach with one mixed shadow TLAS.

## Target structure

```text
Hybrid shadow TLAS
├── Procedural AABB BLAS instances for regular opaque terrain
├── Triangle BLAS instances for cutouts
├── Triangle BLAS instances for irregular block models
├── Triangle BLAS instances for entities
└── Other explicitly shadow-visible geometry
```

A single TLAS may contain instances referring to BLAS objects with different geometry types.

SBT instance offsets must select compatible hit groups.

## Tasks

* [ ] Design the mixed shadow TLAS instance list.
* [ ] Add procedural section instances.
* [ ] Add triangle special-geometry instances.
* [ ] Add entity triangle instances.
* [ ] Assign correct SBT offsets for procedural and triangle instances.
* [ ] Add assertions preventing AABB geometry from selecting a triangle-only hit group.
* [ ] Add assertions preventing triangle geometry from selecting a procedural-only hit group.
* [ ] Keep stable instance masks by ray role.
* [ ] Preserve entity SBT behavior.
* [ ] Preserve existing culling flags where appropriate.
* [ ] Route production shadow rays through the hybrid TLAS.
* [ ] Remove the permanent requirement for two shadow traces.
* [ ] Retain the old double-trace path as a debug comparison mode.
* [ ] Verify instance updates after chunk rebuild.
* [ ] Verify instance removal after chunk unload.
* [ ] Verify entity updates.
* [ ] Verify world-change cleanup.
* [ ] Benchmark hybrid TLAS build and update cost.
* [ ] Benchmark hybrid shadow ray time.
* [ ] Compare hybrid results with the old double-trace reference.

## Phase gate

Do not continue until:

* [ ] One hybrid shadow TLAS produces correct combined visibility.
* [ ] SBT geometry-type validation passes.
* [ ] The old double-trace mode remains usable for debugging.
* [ ] Hybrid ray time is no worse than the temporary double-trace path.
* [ ] Lifecycle tests pass.

---

# Phase 8 — Remove duplicated opaque shadow triangles

## Objective

Obtain the expected VRAM and traversal benefit by excluding regular opaque full cubes from shadow triangle geometry.

## Important restriction

Do not remove opaque terrain from the reflection triangle TLAS yet.

At this phase:

```text
Shadow representation:
  regular opaque full cubes → procedural AABB
  special geometry → triangles

Reflection representation:
  regular opaque full cubes → triangles
  special geometry → triangles
```

## Tasks

* [ ] Separate shadow geometry ownership from reflection geometry ownership.
* [ ] Exclude regular opaque full-cube terrain from shadow triangle BLAS input.
* [ ] Retain those triangles where reflection rays still require them.
* [ ] Verify no full-cube shadow caster disappears.
* [ ] Verify no block is duplicated in the authoritative shadow path.
* [ ] Measure triangle count reduction.
* [ ] Measure triangle BLAS memory reduction.
* [ ] Measure procedural BLAS memory cost.
* [ ] Measure total VRAM difference.
* [ ] Measure BLAS build-time difference.
* [ ] Measure TLAS update-time difference.
* [ ] Measure shadow ray-time difference.
* [ ] Measure frame-time difference.
* [ ] Record results for dense and sparse terrain.
* [ ] Record results while rapidly loading chunks.
* [ ] Record results during repeated block updates.
* [ ] Keep a runtime switch restoring triangle-only shadow geometry.

## Acceptance condition

Keep this optimization only if at least one of the following improves without unacceptable regressions:

* Total ray-tracing VRAM.
* Shadow-ray traversal time.
* BLAS build time.
* Chunk rebuild cost.
* Frame time.
* Frame-time consistency.

## Phase gate

Do not continue until:

* [ ] Opaque shadow triangles are removed safely.
* [ ] Reflection geometry remains intact.
* [ ] Benchmark results are recorded.
* [ ] The hybrid representation provides a measurable benefit or is explicitly justified for later scaling.

---

# Phase 9 — Adaptive brick-size policy

## Objective

Choose between 4-, 8-, and 16-cell bricks based on section occupancy and measured device behavior.

## Tradeoff

Smaller bricks:

* More AABB primitives.
* Better empty-space rejection.
* Shorter local DDA.

Larger bricks:

* Fewer AABB primitives.
* Smaller acceleration structure.
* Longer local DDA.
* More false AABB candidates.

## Tasks

* [ ] Record primitive counts for each brick size.
* [ ] Record procedural BLAS sizes for each brick size.
* [ ] Record procedural BLAS build times for each brick size.
* [ ] Record local-DDA step counts for each brick size.
* [ ] Record shadow ray time for each brick size.
* [ ] Benchmark dense sections.
* [ ] Benchmark sparse sections.
* [ ] Benchmark fragmented checkerboard-like sections.
* [ ] Benchmark cave-heavy sections.
* [ ] Add a fixed-size runtime override.
* [ ] Design an adaptive per-section heuristic.
* [ ] Include occupied-brick count in the heuristic.
* [ ] Include estimated local-DDA work in the heuristic.
* [ ] Add hysteresis to prevent repeated size switching.
* [ ] Rebuild only when the selected brick size materially changes.
* [ ] Record selected brick-size distribution in debug statistics.
* [ ] Verify deterministic selection for unchanged occupancy.
* [ ] Verify adaptive mode improves or matches the best practical fixed mode.

## Suggested initial heuristic

Evaluate each supported brick size using an estimated cost:

```text
estimatedCost =
    occupiedBrickCount × traversalWeight
    + estimatedDdaSteps × ddaWeight
    + estimatedBlasBytes × memoryWeight
```

Weights should be configurable and later informed by benchmarks.

## Phase gate

Do not continue until:

* [ ] Fixed brick-size modes are benchmarked.
* [ ] Adaptive mode is stable.
* [ ] Adaptive mode does not cause rebuild thrashing.
* [ ] The selected default is based on measured results.

---

# Phase 10 — Procedural reflection shading

## Objective

Allow regular opaque full cubes to be removed from reflection triangle geometry.

This phase is optional for the first production shadow release.

## Required procedural hit information

The procedural path must reconstruct:

* Exact hit distance.
* World-space hit position.
* Section-local hit position.
* Local voxel coordinate.
* Hit face.
* Geometric normal.
* Block or material ID.
* Texture or atlas coordinates.
* Face texture selection.
* Emission.
* Tint and biome-dependent color where required.
* Any reflection material properties currently derived from triangle data.

## Tasks

* [ ] Extend procedural payloads with block or material identifiers.
* [ ] Reconstruct exact hit face.
* [ ] Reconstruct face-local UV coordinates.
* [ ] Resolve the appropriate face texture.
* [ ] Resolve block tint.
* [ ] Resolve biome tint where required.
* [ ] Resolve emission.
* [ ] Resolve roughness or reflection parameters.
* [ ] Add a procedural reflection closest-hit shader.
* [ ] Add reflection SBT records for procedural terrain.
* [ ] Add a procedural-versus-triangle reflection comparison mode.
* [ ] Compare hit distance.
* [ ] Compare normals.
* [ ] Compare material IDs.
* [ ] Compare UV coordinates.
* [ ] Compare sampled color.
* [ ] Compare emission.
* [ ] Test animated textures.
* [ ] Test biome-tinted blocks.
* [ ] Test blocks with different textures per face.
* [ ] Test modded texture atlases.
* [ ] Test resource reload.
* [ ] Test shader reload.
* [ ] Test world change.
* [ ] Remove regular opaque full cubes from reflection triangle geometry only after validation passes.
* [ ] Preserve triangle fallback.

## Phase gate

Do not remove opaque reflection triangles until:

* [ ] Procedural hit geometry matches triangle geometry.
* [ ] Material reconstruction is visually correct.
* [ ] Resource reload works.
* [ ] Modded fallback behavior is safe.
* [ ] Comparison screenshots show no unacceptable regression.

---

# Phase 11 — GPU-driven radiance-cache request compaction

## Objective

Move cache-miss compaction and prioritization from CPU-driven queues to GPU compute.

## Required capability

Only enable indirect ray tracing when:

```text
DeviceCapabilities.traceRaysIndirect() == true
```

Keep the CPU queue as fallback and diagnostics path.

## Request-buffer usage

The compacted request and command buffers may require:

```text
VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
```

Use only flags actually required by each buffer’s role.

## Tasks

* [ ] Document the current CPU cache-request pipeline.
* [ ] Add a GPU cache-request input buffer.
* [ ] Add a GPU compacted-request output buffer.
* [ ] Add a GPU request-count buffer.
* [ ] Add a `VkTraceRaysIndirectCommandKHR` buffer.
* [ ] Add subgroup capability detection.
* [ ] Add subgroup ballot-based active-request detection.
* [ ] Add subgroup prefix-count compaction.
* [ ] Add a non-subgroup compute fallback if needed.
* [ ] Move cache-miss collection to the GPU.
* [ ] Generate compacted request indices.
* [ ] Generate indirect trace dimensions from the compacted count.
* [ ] Dispatch rays with `vkCmdTraceRaysIndirectKHR`.
* [ ] Keep CPU request generation selectable.
* [ ] Add a GPU-versus-CPU request comparison mode.
* [ ] Compare compacted request counts.
* [ ] Compare request identities.
* [ ] Compare resulting cache contents.
* [ ] Handle zero-request frames safely.
* [ ] Clamp request counts to buffer capacity.
* [ ] Add overflow counters.
* [ ] Add a safe overflow fallback.
* [ ] Avoid CPU readback in the normal GPU-driven path.
* [ ] Add debug readback only when explicitly enabled.

## Required synchronization

After compute writes the request list and indirect command:

```text
Source stage:
  COMPUTE_SHADER

Source access:
  SHADER_STORAGE_WRITE

Destination stages:
  DRAW_INDIRECT
  RAY_TRACING_SHADER

Destination access:
  INDIRECT_COMMAND_READ
  SHADER_STORAGE_READ
```

Use the repository’s synchronization abstraction and correct Vulkan 1.2/KHR equivalents.

## Synchronization tasks

* [ ] Add compute-write to indirect-command-read synchronization.
* [ ] Add compute-write to ray-shader-read synchronization.
* [ ] Handle cross-queue semaphore synchronization if different queues are used.
* [ ] Handle queue-family ownership transfer or concurrent sharing where required.
* [ ] Verify no host readback is accidentally introduced.
* [ ] Verify command-buffer lifetime covers indirect command consumption.
* [ ] Verify buffers are not overwritten before tracing completes.

## Phase gate

Do not continue until:

* [ ] GPU and CPU request paths produce equivalent cache requests.
* [ ] Indirect tracing works on supported devices.
* [ ] CPU fallback works on unsupported devices.
* [ ] Zero-count and overflow cases are safe.
* [ ] Vulkan synchronization validation remains clean.

---

# Phase 12 — GPU cache prioritization

## Objective

Move variance, age, and update-priority selection into a compute pass.

## Suggested priority inputs

```text
cache miss
cache age
radiance variance
distance from camera
recently exposed geometry
nearby block modification
nearby dynamic light
invalid generation ID
low confidence
```

## Tasks

* [ ] Define cache-entry metadata required for prioritization.
* [ ] Add age tracking.
* [ ] Add variance tracking.
* [ ] Add confidence tracking.
* [ ] Add geometry-generation tracking.
* [ ] Add dirty-region influence.
* [ ] Add camera-distance influence.
* [ ] Add light-distance influence.
* [ ] Add configurable priority weights.
* [ ] Add a compute selection pass.
* [ ] Compact selected entries into the trace-request buffer.
* [ ] Enforce a per-frame ray budget.
* [ ] Enforce a per-frame cache-update budget.
* [ ] Prevent starvation of old entries.
* [ ] Prioritize newly visible invalid entries.
* [ ] Add debug visualization of selected cache requests.
* [ ] Add debug visualization of cache age.
* [ ] Add debug visualization of variance.
* [ ] Compare GPU selection against the existing CPU strategy.
* [ ] Benchmark stable scenes.
* [ ] Benchmark rapid movement.
* [ ] Benchmark many block updates.
* [ ] Benchmark changing local lights.

## Phase gate

Do not continue until:

* [ ] Cache convergence remains visually stable.
* [ ] No cache region starves indefinitely.
* [ ] GPU selection reduces CPU overhead or improves scalability.
* [ ] Per-frame update cost remains bounded.

---

# Phase 13 — Queue overlap evaluation

## Objective

Evaluate whether compute and ray work benefit from multi-queue submission.

Do not assume that two available queues provide physical concurrency.

Use terminology such as:

```text
multiQueueSubmissionAvailable
asyncComputeCandidate
```

Do not call the feature “validated overlap” until timing demonstrates actual benefit.

## Tasks

* [ ] Detect whether suitable multiple queues are available.
* [ ] Record whether queues belong to the same family.
* [ ] Record whether ownership transfers would be required.
* [ ] Implement a single-queue reference path.
* [ ] Implement an optional multi-queue path.
* [ ] Add timeline semaphore synchronization.
* [ ] Add correct queue-family ownership handling.
* [ ] Verify no resource is consumed before production completes.
* [ ] Measure overlap using GPU timestamps.
* [ ] Measure total frame time.
* [ ] Measure graphics queue idle time.
* [ ] Measure compute queue idle time.
* [ ] Measure memory-bandwidth pressure.
* [ ] Test on at least one NVIDIA GPU when available.
* [ ] Test on at least one AMD GPU when available.
* [ ] Test on at least one Intel GPU when available.
* [ ] Disable multi-queue mode automatically when it regresses performance.
* [ ] Keep a manual override for diagnostics.

## Phase gate

* [ ] Single-queue mode remains fully supported.
* [ ] Multi-queue mode is enabled only where measured beneficial.
* [ ] No queue-family or semaphore validation errors occur.
* [ ] Startup logs distinguish availability from enabled use.

---

# Phase 14 — Robustness and lifecycle validation

## Objective

Ensure the hybrid renderer survives normal Minecraft and mod lifecycle events.

## Required tests

* [ ] Repeated chunk rebuild.
* [ ] Repeated block placement.
* [ ] Repeated block destruction.
* [ ] Large explosion or mass block update.
* [ ] Chunk unload.
* [ ] Chunk reload.
* [ ] Rapid movement through new terrain.
* [ ] Teleportation.
* [ ] Dimension change.
* [ ] World change.
* [ ] Return to title.
* [ ] Re-enter a world.
* [ ] Shader reload.
* [ ] Resource-pack reload.
* [ ] Window resize.
* [ ] Fullscreen toggle.
* [ ] Swapchain recreation.
* [ ] Device-lost handling where supported.
* [ ] Clean game shutdown.
* [ ] Unsupported GPU fallback.
* [ ] Missing optional feature fallback.
* [ ] Allocation-failure fallback where practical.
* [ ] Debug-mode enable and disable during runtime where supported.
* [ ] Triangle fallback after procedural initialization failure.

## Leak checks

* [ ] No procedural AABB input-buffer leaks.
* [ ] No payload-buffer leaks.
* [ ] No procedural BLAS leaks.
* [ ] No hybrid TLAS leaks.
* [ ] No stale descriptor references.
* [ ] No stale device addresses.
* [ ] No in-flight resource destruction.
* [ ] No world references retained after world exit.
* [ ] No pending async jobs mutate destroyed worlds.

## Phase gate

Do not continue until:

* [ ] All lifecycle stress tests pass.
* [ ] Vulkan validation remains clean.
* [ ] Long-running memory usage remains stable.
* [ ] The triangle fallback remains reliable.

---

# Phase 15 — Benchmark and acceptance decision

## Objective

Determine whether the hybrid path should remain, be adjusted, or be disabled on particular hardware.

## Modes to compare

```text
Triangle only
Procedural opaque plus second triangle trace
Unified hybrid shadow TLAS
Hybrid with fixed 4-cell bricks
Hybrid with fixed 8-cell bricks
Hybrid with fixed 16-cell bricks
Hybrid with adaptive bricks
CPU-driven cache requests
GPU-driven cache requests
Single queue
Multi-queue candidate
```

## Metrics

* [ ] CPU render-thread time.
* [ ] CPU worker-thread time.
* [ ] GPU total frame time.
* [ ] Shadow ray-tracing time.
* [ ] Reflection ray-tracing time.
* [ ] Cache update time.
* [ ] BLAS build time.
* [ ] BLAS update time.
* [ ] TLAS build or update time.
* [ ] Number of procedural AABB primitives.
* [ ] Number of terrain triangles removed.
* [ ] Triangle BLAS memory.
* [ ] Procedural BLAS memory.
* [ ] TLAS memory.
* [ ] Payload-buffer memory.
* [ ] Total renderer VRAM.
* [ ] Cache-request count.
* [ ] Rays traced per frame.
* [ ] Average local-DDA steps.
* [ ] 1% low frame time.
* [ ] Worst observed frame-time spike.
* [ ] Chunk rebuild latency.
* [ ] Chunk upload bandwidth.
* [ ] Mismatch counters.

## Test conditions

* [ ] Static dense terrain.
* [ ] Static sparse caves.
* [ ] Forest and cutouts.
* [ ] Water and irregular geometry.
* [ ] Many entities.
* [ ] Rapid chunk streaming.
* [ ] Repeated block updates.
* [ ] Stable converged cache.
* [ ] Cold invalid cache.
* [ ] High render distance.
* [ ] Low render distance.
* [ ] Several supported GPUs where available.

## Acceptance rule

Keep the hybrid path only when it provides a meaningful improvement in at least one major area without unacceptable regression in another:

* VRAM.
* Traversal time.
* BLAS build cost.
* Chunk update latency.
* Frame time.
* Frame-time consistency.
* Cache scalability.

If performance varies by device, use capability-tier or benchmark-informed defaults.

## Phase gate

* [ ] Benchmark report is committed.
* [ ] Default brick-size policy is selected.
* [ ] Default queue policy is selected.
* [ ] Device-specific regressions are documented.
* [ ] Automatic fallback rules are defined.

---

# Phase 16 — Controlled rollout

## Objective

Enable the backend safely after validation and benchmarking.

## Tasks

* [ ] Add a user-facing backend option.
* [ ] Add `triangle-only`.
* [ ] Add `hybrid`.
* [ ] Add `automatic`.
* [ ] Keep hybrid disabled by default during experimental rollout.
* [ ] Add startup capability summary.
* [ ] Add startup selected-backend summary.
* [ ] Add a warning when falling back.
* [ ] Add concise diagnostics for pipeline creation failure.
* [ ] Add concise diagnostics for BLAS build failure.
* [ ] Add a debug information screen or log section.
* [ ] Document supported hardware requirements.
* [ ] Document unsupported paths.
* [ ] Document expected visual differences.
* [ ] Document configuration options.
* [ ] Document how to collect a validation report.
* [ ] Document how to collect performance captures.
* [ ] Enable automatic mode only after enough benchmark coverage.
* [ ] Enable hybrid by default only if all final gates pass.

## Final release gate

The hybrid backend may become default only when:

* [ ] Vulkan validation is clean.
* [ ] Unit and integration tests pass.
* [ ] Chunk and world lifecycle tests pass.
* [ ] No known dangerous procedural false negatives remain.
* [ ] Triangle fallback works.
* [ ] Reflection geometry remains correct.
* [ ] Resource reload works.
* [ ] VRAM behavior is stable.
* [ ] Benchmark results justify the backend.
* [ ] Unsupported devices fall back cleanly.
* [ ] Documentation is complete.

---

# Optional future backends

These are not required for the procedural AABB MVP.

Do not begin them before the required phases are stable.

## Sparse residency

* [ ] Investigate sparse voxel or cache resource residency.
* [ ] Measure page-management overhead.
* [ ] Keep a non-sparse fallback.

## Mesh shaders

* [ ] Investigate meshlet-based chunk rendering.
* [ ] Investigate GPU culling.
* [ ] Keep existing chunk rendering fallback.

## Invocation reorder

* [ ] Detect ray-tracing invocation-reorder support.
* [ ] Group rays or hits by material or hit type.
* [ ] Benchmark actual benefit.

## Variable-rate shading

* [ ] Evaluate reduced-rate GI composition.
* [ ] Evaluate reduced-rate reflection shading.
* [ ] Preserve full-rate mode.

## Cooperative matrices

* [ ] Investigate optional neural denoising.
* [ ] Investigate cache prediction.
* [ ] Keep conventional temporal and spatial filtering.

## Device-generated commands

* [ ] Investigate only after indirect tracing is stable.
* [ ] Keep conventional command-buffer recording.

---

# Definition of done

The overall project is complete when:

* [ ] Regular opaque terrain uses procedural AABB traversal for shadow rays.
* [ ] Exact voxel hits are resolved by short brick-local DDA.
* [ ] Special terrain and entities remain represented by triangles.
* [ ] A unified hybrid shadow TLAS is operational.
* [ ] Reflection geometry remains correct.
* [ ] GPU-driven cache requests work on supported devices.
* [ ] CPU fallback works on unsupported devices.
* [ ] Vulkan validation remains clean.
* [ ] Lifecycle and shutdown tests pass.
* [ ] Benchmarks demonstrate a justified benefit.
* [ ] The backend can be enabled, disabled, and diagnosed safely.

---

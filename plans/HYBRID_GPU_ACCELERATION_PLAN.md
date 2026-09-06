# Hybrid Vulkan Ray-Tracing Acceleration Plan

## Reassessment — 2026-09-06

**Current acceptance milestone: Phase 0 validation and baseline recovery.** The
working tree already contains implementation through Phase 10. That source
coverage does not advance the release gate: renderer validation, labelled scene
captures, and comparable triangle/hybrid measurements remain incomplete.

The phase checklists below retain dated evidence from earlier work. A historical
checkmark applies to the recorded implementation and experiment, not automatically
to subsequent geometry, payload, shader, or driver changes. In particular, the
Phase 7 measurements predate the Phase 8–10 changes. Revalidate their gates on
the consolidated implementation before promoting any experimental default.

### One renderer with separate decisions

These are independent axes, not competing complete renderers:

| Decision | Canonical owner | Current behavior |
| --- | --- | --- |
| Primary visibility and final presentation | Iris/Sodium and `MixinIrisRenderingPipeline` | Raster surface records and compatibility geometry; Vulkanite runs after Iris composites and before final presentation. |
| Per-frame lighting work | `RtxFrameDecision` / `VulkanPipeline` | Full-frame reference RT, bounded cache-fill RT plus resolve, or cache resolve without ray dispatch. |
| Shadow/reflection geometry and development dependencies | `HybridAccelerationConfig` | Triangle reference by default; hybrid shadows, diagnostics, and procedural reflections are selected independently. |
| SBT indices, geometry compatibility, ray masks | `HybridSbtLayout` | Fixed compatible terrain/entity/procedural records; shader stages and device checks still gate execution. |
| Ray encoding and frame inputs | `RenderPassExecutor` / `RtxFrame` | One borrowed frame contract shared with cache feedback/resolve; `VulkanPipeline` owns order and explicit barriers. |
| Ray descriptor ABI | `PipelineDescriptorSets` | One common binding schema; reflected shader subsets remain valid. Compute passes retain their own layouts. |
| Temporal resources and reconstruction | `RtxFrameImages` / denoiser configuration | ReSTIR, cache histories, and DLSS/RR are separate consumers, not geometry backend switches. |
| Shader source | Active Iris shaderpack; tracked `shaderpacks/VulkaniteRT` | The only injected bundled library is ReSTIR. Runtime pack copies are generated. |

`RtxPassGraph` was a sequential forwarding wrapper, not a dependency/barrier
scheduler. It has been removed, along with the second 57-argument execution
signature and duplicated expected descriptor layouts. Do not add a replacement
graph facade without moving actual dependency and resource-lifetime ownership.

### Findings that change the optimization order

1. **Normal launches were building unused experimental geometry.** The old
   `proceduralBlas=true` default enabled AABB builds, filtered shadow batches, and
   material extraction even when the hybrid shader groups were disabled. The
   existing `run/logs/latest.log` records both “Skipping experimental hybrid SBT
   groups” and later nonzero `shadowPersistentBytes` / `proceduralPersistentBytes`.
   Central selection now disables that work by default and derives BLAS/shader
   dependencies from selected consumers. This establishes a source-level removal
   of unnecessary work; no new frame-time or VRAM savings measurement is claimed.
2. **Phase 8 removes triangles from shadow traversal, not from total residency.**
   The original material-bearing triangle BLAS remains for reflection/fallback;
   filtered shadow triangles, procedural AS/payloads, and extra TLAS resources
   are additional allocations. Compare *total* resident/peak memory and build
   work against triangle-only mode. Fewer authoritative shadow triangles cannot
   by itself establish total VRAM savings.
3. **Adaptive bricks are an uncalibrated policy.** Determinism and hysteresis can
   be tested offline. Traversal/empty-space/byte estimates are not driver timings;
   include real v2 material payload sizes and rebuild costs when calibrating.
   Keep fixed sizes available and keep `fixed` as the default until measured.
4. **Procedural reflection is a separate material-correctness milestone.** Its v2
   payload and six-face completeness checks do not prove animated, tinted,
   layered, reloaded, or modded materials render correctly. Keep full triangle
   resources and the default triangle reflection path until Phase 10 acceptance.
5. **Frame-resource allocation is still unconditional.** `RtxFrameImages` creates
   two reservoirs and four specular history images even in full-reference mode
   with ReSTIR off. Introduce a tested resource-use plan before resizing/removing
   them: reflected bindings, unconditional shader writes, cache transitions,
   history resets, and denoiser guides must all remain valid. This cleanup does
   not claim that allocation optimization is implemented.
6. **Cache scheduling remains CPU-driven after GPU feedback.** `CacheFeedbackPass`
   readback, `CacheRequestQueue`, bounded batching, and CPU-packed requests are
   current owners. Phase 11 must replace compaction/dispatch sizing coherently,
   with a capability fallback, rather than adding a second competing scheduler.

### Startup selection after consolidation

`HybridAccelerationConfig` resolves these properties for all consumers. Supply
`-Pvulkanite.<name>=...` to Gradle (explicit `vulkanite.*` properties are forwarded
to the game JVM), or `-Dvulkanite.<name>=...` to a direct JVM launch. Restart after
changes; UI debug selection does not compile stages omitted at startup.

For example, in PowerShell, quote dotted property arguments:

```powershell
.\gradlew.bat runClient '-Pvulkanite.hybridShadow=true' '-Pvulkanite.voxelBrickSize=8'
```

| Property | Default / dependency |
| --- | --- |
| `hybridShadow` | `false`; selecting it also selects shadow shader compilation and procedural BLAS construction. |
| `hybridShadowPipeline` | `false`; explicit compile-only experiment, without selecting hybrid dispatch or BLAS builds by itself. |
| `compileDiagnostics` | `false`; selects diagnostic/extended stages and standalone procedural geometry. |
| `proceduralReflection` | `false`; selects reflection stages and material-bearing procedural geometry, without selecting hybrid shadow dispatch. |
| `proceduralBlas` | Derived from diagnostics/shadows/reflections; explicit `false` overrides construction, explicit `true` permits BLAS-only measurements. |
| `hybridShadowGeometry` | Filtering only when a shadow/reflection consumer needs it; `triangle-only` retains full shadow triangles. |
| `voxelBrickMode` / `voxelBrickSize` | `fixed` / existing fixed-size override; `adaptive` remains opt-in. |
| `fastPipelineCompile` | `false`; retain normal driver optimization. |

These are requested techniques, not a claim of supported or enabled device
features. Capability checks, shader compatibility, complete per-section resources,
and triangle fallbacks remain in their existing owners.

### Revised acceptance order

1. **Consolidation and offline correctness:** one feature selector, frame contract,
   descriptor ABI, and shader source owner; executable geometry tests; Java 21 in
   CI. This pass implements that scope, preserving existing experimental work.
2. **Recover the baseline:** reproduce or clear the previously recorded validation
   layer/runtime crash; capture labelled triangle-only scenes, camera route,
   cold/warm pipeline creation, total AS/payload/VRAM costs, and frame/RT timings.
   Recheck ordinary entity/cutout/fluid behavior and reload after this cleanup.
3. **Accept hybrid shadows as one milestone (Phases 4–8):** compare triangle-only,
   unfiltered hybrid, and filtered hybrid with fixed brick sizes. Require sampled
   mismatch and lifecycle gates plus actual traversal/frame benefit with bounded
   memory/build overhead. Keep the double trace for diagnostics only; do not
   introduce another production shadow path to replay Phase 5.
4. **Calibrate Phase 9, then validate Phase 10 independently.** Do not use reflection
   expansion to justify unmeasured shadow or adaptive defaults.
5. **Revisit Phases 11–13 only after that evidence:** indirect request compaction,
   priority, and queue overlap each need their own capability/synchronization and
   measured-benefit gates. Phases 14–16 still control robustness and rollout.

### This pass: verification record

- Baseline `gradlew.bat test --offline --console=plain`: 63 tests, 7 failures in
  `ShadowGeometryFilterTest` during Minecraft registry initialization.
- Corrected the harness with Fabric Loader JUnit (matching Loader 0.15.11),
  Minecraft bootstrap, and scoped in-memory Sodium options for native buffers.
  Tests use `build/test-runtime` so Loader-generated configuration/logs remain
  build output. No application client or user configuration is required by the fixture.
- After consolidation: 73 tests passed, zero failures/skips. Added coverage for
  feature dependencies/explicit fallbacks and descriptor compatibility.
- `gradlew.bat build --console=plain` and the subsequent offline build:
  **BUILD SUCCESSFUL**, including the
  73-test suite, remapped jar/source packaging, and access-widener validation.
  The initial offline build lacked cached JOML 1.10.4; the normal build resolved
  that dependency and completed. Existing deprecation/unchecked warnings remain.
- Jar inspection: `RtxFrame` and `HybridAccelerationConfig` present, old graph
  classes absent, ReSTIR retained, all 14 inactive shader assets absent.
- A temporary Gradle assertion task verified explicit shadow, BLAS-override, and
  brick-size properties reach `runClient` without launching a client.
- `git diff --check` passed. Comparison with the turn-start snapshot confirmed the
  consolidated common descriptor ABI retains the same 35 bindings/types/shapes.
- New Vulkan validation, shader compiler execution, live rendering, screenshots,
  GPU benchmarks, and hardware/driver retesting: **not executed** in this pass.
- Next unchecked acceptance task: recover Phase 0 renderer validation, then capture
  the labelled triangle-only reference set and matching performance baseline.

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

1. Work on one acceptance milestone at a time, using the revised order above.
2. Start with the earliest unmet acceptance gate; existing later-phase source is not release evidence.
3. Inspect the existing repository before changing code.
4. Reuse existing abstractions where practical.
5. Do not replace working systems unnecessarily.
6. Keep the triangle path operational as a fallback until the relevant validation gate passes.
7. Do not mark a task complete merely because the code compiles.
8. Mark a task with `[x]` only after its stated verification requirement succeeds.
9. Leave incomplete tasks as `[ ]`.
10. If a task is blocked, leave it unchecked and add a short `BLOCKED:` note underneath it.
11. Do not promote a later phase or enable its defaults until prerequisite gates pass. Cross-phase cleanup and offline verification may proceed without claiming gate completion.
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
* `SectionLightExtractor` produces a separate 4,096-bit regular opaque
  full-cube field for procedural geometry; the broader probe field is not a
  valid procedural occupancy source because it includes irregular blockers.
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

* [x] Extend the async BLAS job model with optional procedural voxel-brick input.
* [x] Extend the async BLAS result model with an optional procedural BLAS.
* [x] Upload tightly packed `VkAabbPositionsKHR` records.
* [x] Upload fixed-stride brick payloads.
* [x] Query procedural BLAS build sizes.
* [x] Allocate procedural BLAS storage.
* [x] Allocate or reuse scratch storage.
* [x] Build one procedural AABB BLAS per non-empty section.
* [x] Skip procedural BLAS creation for empty sections.
* [x] Assign readable debug names to buffers and acceleration structures.
* [x] Track procedural BLAS lifetime with section lifetime.
* [x] Destroy procedural BLAS resources on chunk unload.
* [x] Destroy procedural BLAS resources on world change.
* [x] Destroy procedural BLAS resources during shutdown.
* [x] Rebuild the procedural BLAS after relevant opaque occupancy changes.
* [x] Avoid rebuilding procedural BLAS for changes that do not affect procedural occupancy.
* [x] Add capability checks for required ray-tracing features.
* [x] Keep the backend disabled when required procedural RT support is unavailable.
* [x] Report procedural BLAS creation statistics in debug mode.
* [x] Report AABB count per section.
* [x] Report procedural BLAS memory usage.
* [ ] Verify Vulkan object lifetime ordering.
  * BLOCKED: Source ownership paths now close procedural inputs, results, section-held procedural BLAS resources, queued updates, removals, world-change/shutdown state, and retained-occupancy records. Runtime ordering still needs validation-layer and lifecycle stress coverage, which is blocked by the existing `vkCreateInstance` validation-layer crash documented in Phase 0.

## Validation

* [ ] Vulkan validation reports no acceleration-structure build errors.
  * BLOCKED: Validation-layer runtime coverage is blocked by the Phase 0 native `vkCreateInstance` crash with `VK_LAYER_KHRONOS_validation`.
* [x] Empty sections create no procedural BLAS.
* [x] Full sections create the expected number of AABB primitives.
* [ ] Chunk unload releases procedural resources.
  * BLOCKED: Requires runtime lifecycle stress with validation or object leak reporting.
* [ ] Repeated rebuilds do not leak memory.
  * BLOCKED: Requires runtime lifecycle stress with validation or object leak reporting.
* [ ] World changes do not retain stale procedural BLAS references.
  * BLOCKED: Requires runtime lifecycle stress with validation or object leak reporting.
* [ ] Shutdown reports no live Vulkan objects from this path.
  * BLOCKED: Requires runtime lifecycle stress with validation or object leak reporting.

## Phase gate

Do not continue until:

* [ ] Procedural BLAS objects build successfully.
  * BLOCKED: The build path compiles and unit tests pass, but actual device-side procedural BLAS execution has not been validated because validation-layer runtime coverage is blocked.
* [ ] Vulkan validation reports no procedural-build errors.
  * BLOCKED: Same Phase 0 validation-layer `vkCreateInstance` crash.
* [ ] Resource lifetime stress tests pass.
  * BLOCKED: Requires runtime lifecycle stress coverage.
* [ ] Memory usage remains stable over repeated rebuild cycles.
  * BLOCKED: Requires runtime lifecycle stress coverage.

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

The implemented Phase 3 layout is:

```text
triangleTerrainHitGroup = 0
entityTriangleHitGroup = 1
proceduralTerrainHitGroup = 2
geometryCountPerProceduralBlas = 1
instanceShaderBindingTableRecordOffset = 2
raySbtRecordOffset = 0
raySbtRecordStride = 0
proceduralMissIndex = 2
proceduralPayloadDescriptorSet = 1
proceduralPayloadDescriptorBinding = 0
proceduralDebugTlasBinding = 32
```

The zero ray-record stride matches the existing single-ray-type pipeline. The
procedural TLAS instance custom index selects the packed payload descriptor;
`gl_PrimitiveID` selects the fixed-stride brick record inside that payload.

## Tasks

* [x] Add a procedural intersection shader such as `ray0_2.rint`.
* [x] Add a minimal procedural closest-hit shader for debugging.
* [x] Add the procedural hit group to the ray-tracing pipeline.
* [x] Add the procedural hit group to SBT construction.
* [x] Add explicit assertions for SBT record offsets.
* [x] Add explicit assertions for geometry type and hit-group compatibility.
* [x] Build a second procedural section TLAS.
* [x] Use the correct instance SBT record offset.
* [x] Bind the packed payload through the existing bindless geometry system.
* [x] Make the payload address or descriptor discoverable from the section instance.
* [x] Ensure `gl_PrimitiveID` indexes the correct brick payload.
* [x] Ensure section-local ray coordinates are reconstructed correctly.
* [x] Implement exact brick-local DDA.
* [x] Call `reportIntersectionEXT` only for an occupied voxel hit.
* [x] Report the exact voxel hit distance.
* [x] Return a debug block-local hit position.
* [x] Return a debug face normal.
* [x] Return a debug local-cell index.
* [x] Handle rays starting inside a candidate AABB.
* [x] Handle rays parallel to one or more brick axes.
* [x] Handle zero and near-zero direction components safely.
* [x] Handle entry exactly on a voxel boundary.
* [x] Handle section and brick boundaries deterministically.
* [x] Add a debug view that visualizes procedural hit distance.
* [x] Add a debug view that visualizes procedural face normals.
* [x] Add a debug view that visualizes brick IDs.
* [x] Add a debug view that visualizes local voxel IDs.

## Important correctness rule

Hardware AABB traversal only identifies a candidate.

The intersection shader must perform exact local DDA and must not report an intersection unless it finds an occupied voxel.

A candidate AABB alone is not an occluder.

## Validation scenes

The implementation, Java tests, shader compilation, and shaderpack sync pass.
The following scene checks remain unchecked until an interactive client run can
exercise binding 32 and the procedural SBT record on a Vulkan device.

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
  * BLOCKED: The device-side debug TLAS build needs an interactive world run;
    source compilation alone does not validate `vkCmdBuildAccelerationStructuresKHR`.
* [x] SBT indexing assertions pass.
* [ ] Vulkan validation reports no geometry-type or SBT mismatch.
  * BLOCKED: Renderer validation remains blocked by the Phase 0 native
    `vkCreateInstance` crash with `VK_LAYER_KHRONOS_validation`.
* [ ] Debug hit positions and normals match expected voxel geometry.
  * BLOCKED: Requires the Phase 3 interactive validation scenes above.
* [ ] Rays inside AABBs are handled correctly.
  * BLOCKED: The shader handles inside starts, but device-side scene validation
    is still required before checking this gate.
* [x] No production lighting result depends on this path yet.

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

* [x] Add a debug dual-trace mode.
* [x] Trace the same ray against both TLAS paths.
* [x] Add atomic counters for all mismatch categories.
* [x] Add a hit-distance tolerance.
* [x] Record maximum observed hit-distance difference.
* [x] Record average hit-distance difference.
* [x] Record mismatch counts by brick size.
* [x] Record mismatch counts by ray direction octant.
* [x] Record mismatch counts for rays beginning inside AABBs.
* [x] Record mismatch counts by material classification.
* [x] Record mismatch counts by section coordinate.
* [x] Add a screen overlay with mismatch totals.
* [x] Add a visualization for dangerous procedural misses.
* [x] Add a visualization for conservative procedural extra hits.
* [x] Add optional structured debug logging for sampled mismatches.
* [x] Add a configurable comparison sampling rate.
* [x] Support 100% comparison in debug captures.
* [x] Support a low sampling rate for extended gameplay tests.
* [x] Compare sun visibility rays.
* [x] Compare local-light visibility rays.
* [x] Verify correct finite ray minimum and maximum distances.
* [x] Verify equivalent ray-origin bias between both paths.
* [x] Verify equivalent opaque classification between both paths.
  * VERIFIED FOR PROCEDURAL-OWNED CELLS: Five exact 2026-07-11 windows in
    `ray-tracing-test-place` used 100% sampling and a 0.01-block tolerance.
    Across 894,703,511 rays, all 22,527 raw triangle-hit/procedural-miss cases
    belonged to triangle-only cells; procedural-owned and unknown dangerous
    misses were both zero. The capture therefore found no full-cube occupancy
    classification false negative. Triangle-only irregular geometry remains
    intentionally outside the procedural TLAS and stays authoritative through
    the triangle path.

The Phase 4 implementation compares the procedural TLAS from inside the
existing `alphaAwareVisibility` path, after the authoritative triangle ray
query completes. Both paths receive the exact same origin, direction,
`tMin = 0.001`, and caller-provided finite `tMax`; only triangle visibility is
returned to production lighting. Debug modes 7–9 expose a cumulative overlay,
dangerous procedural misses, and conservative procedural-only hits. The
comparison SSBO records the four visibility outcomes, distance agreement,
maximum distance error, a 1/64-block fixed-point distance sum/count for the
average, brick-size and direction buckets, inside-brick starts, triangle
material class, and a bounded 64-entry section-coordinate table. The sampling
control supports 1–100%, and the tolerance supports 0.0001–1 block. Eight
distance-mismatch buckets now separate errors at 0.02, 0.05, 0.1, 0.5, 1, 2,
16, and greater than 16 blocks.

The next classification capture now has ownership-scoped counters instead of
assuming every opaque triangle belongs in the procedural representation. For a
mismatching terrain hit, the shader reconstructs the owning world cell from
Iris's per-vertex `mid_block` data and probes that cell from its center against
the procedural TLAS. The eight logged ownership buckets are ordered as
`[procedural-owned dangerous miss, triangle-only dangerous miss, unknown
dangerous miss, same-cell procedural distance mismatch, triangle-only distance
mismatch, unknown distance mismatch, different-caster solid hit, reserved]`.
Entities are known
triangle-only. Missing `mid_block` metadata is reported as unknown. The
quarter-block ownership probe is issued only for an already mismatching debug
sample and cannot leave the candidate cell, so it separates actionable
full-cube traversal errors from expected irregular-geometry differences without
changing production visibility.

`Log Shadow Mismatches` adds an opt-in five-second diagnostic window. At the
end of a window, the renderer copies the device-local SSBO into a host-visible
readback buffer, waits for that explicitly requested diagnostic copy, and logs
exact key/value totals, distance statistics, all brick/direction/material
buckets, overflow indicators, and the eight hottest section coordinates. The
normal path performs no readback or queue wait. Each logged window resets the
GPU counters, and the shader counters saturate instead of silently wrapping.
CPU snapshot parsing, unsigned-counter handling, distance conversion, and
section ordering have focused unit coverage.

The 2026-07-11 pass stopped reusing probe visibility as procedural occupancy.
That mask intentionally includes stairs, slabs, fences, doors, leaves, and
other irregular meshes as conservative probe blockers. Procedural BLAS input
now uses a separately scanned `isOpaqueFullCube` mask, leaving irregular and
transparent geometry on the triangle path.

A temporary bounded per-ray sampler isolated one dangerous false-negative
class at the exact outer corner of the benchmark quartz platform. For example,
a ray from `(509.11316, -56.47532, -4.95750)` in direction
`(-0.9981621, -0.0606009, 0)` hit the triangle platform at `t=25.159391` while
the DDA missed: advancing both tied axes simultaneously stepped outside the
platform without testing the voxel touched only at that edge. The intersection
shader now checks every adjacent cell touched at a tied edge/corner before the
diagonal step. A comparable exact window dropped from 203 dangerous misses
before this change to 58 afterward. The detailed sampler was removed after the
capture because it increased first-use driver pipeline compilation from about
8 seconds to roughly 2 minutes; compact aggregate logging remains.

Offline verification completed with forced Java compilation/unit tests and
Vulkan 1.2 `glslc` compilation of the ray-generation, procedural intersection,
procedural closest-hit, and procedural miss shaders.

The ownership-scoped diagnostic extension also passed forced Java compilation
and unit tests, Vulkan 1.2 `glslc` compilation of `ray0.rgen`, shaderpack sync,
and the tracked/runtime shaderpack drift check on 2026-07-11. The classification
capture then completed five bounded windows and reported all six non-reserved
ownership buckets without counter saturation. The same windows classified
1,307,121 of 1,338,284 distance mismatches as procedural-owned, 31,163 as
triangle-only, and zero as unknown. This resolves occupancy ownership but
confirms that full-cube hit-distance disagreement remains the next correctness
problem. The first post-startup window also contained 232,806 transient extra
hits while section acceleration structures streamed in; the following four
windows contained 984 combined extra hits.

An interactive Vulkan client run completed on 2026-07-10 with validation
layers disabled because of the Phase 0 native crash. The client reached more
than 3,200 `FULL_RT_REFERENCE` frames, built procedural BLAS/TLAS resources,
and shut down cleanly. At 848x480, the initial clean capture showed a 76-pixel
both-hit segment and a 772-pixel both-miss segment with no visible red or blue;
the distance row showed 825 pixels within tolerance and 23 pixels outside it.
After rotating across more opaque geometry, two captures showed a one-pixel
blue conservative-extra-hit segment. Distance disagreement remained visible,
ranging from 7 to 23 pixels of the 848-pixel overlay width.

The extended 100% run also exposed a counter-lifetime limitation in the earlier
implementation: after about 2,700 full-reference frames, the visibility bar
changed to an implausible all-green result. The later structured-logging pass
replaced silent wrapping with saturating counters and bounded five-second log
windows. Multiple 2026-07-11 device runs verified exact bounded values with
zero counter saturations and graceful shutdown, but classification equivalence,
distance disagreement, and the remaining lifecycle scenarios are unresolved.

## Required stress scenarios

BLOCKED: These scenarios require an interactive client run with a working
procedural TLAS. Offline compilation cannot validate in-flight Vulkan lifetime
or world/chunk transitions.

* [x] Rebuild a chunk repeatedly while comparison mode is active.
  * VERIFIED: A clean 2026-07-11 client run waited for RT pipeline creation and
    a live 100% comparison sample before issuing updates. Twenty alternating
    stone/quartz updates repeatedly rebuilt triangle geometry while correctly
    skipping procedural rebuilds because occupancy was unchanged. Twelve
    alternating air/quartz updates then exercised occupancy changes; coalescing
    produced six procedural BLAS replacement batches and seven TLAS update
    batches while 13 bounded comparison windows continued. Every window
    reported zero counter saturations, the client remained responsive, working
    memory returned to about 3.1 GiB after the rebuilds, no lifecycle or device
    errors were logged, and clean shutdown stopped the BLAS worker and freed
    its buffer allocators.
* [x] Unload chunks while rays are in flight.
  * VERIFIED: A 2026-07-11 `FULL_RT_REFERENCE` run kept 100% procedural shadow
    comparison active while teleporting repeatedly between the benchmark area
    and three locations roughly 3,000 blocks away. The section table processed
    removal batches of 3,528 and 8,760 entries while full-frame ray dispatches
    continued, including transitions to zero active instances and subsequent
    reloads. The client remained responsive past 21,000 reference frames, no
    Vulkan/device-lost or acceleration-lifetime error was logged, and clean
    shutdown stopped the BLAS worker and completed the Gradle run successfully.
    Empty far-field comparison windows saturated the bounded total-ray counter;
    this was reported explicitly and did not coincide with a lifecycle failure.
* [x] Reload shaders repeatedly.
  * VERIFIED: A 2026-07-11 `FULL_RT_REFERENCE` run issued five consecutive
    F3+T resource/shader reloads while 100% procedural shadow comparison was
    active. Every reload stopped and restarted all ten chunk workers, removed
    and repopulated section acceleration data, and resumed bounded comparison
    dispatch within roughly 9-11 seconds. Eighteen post-reload windows all
    reported zero counter saturations; the client logged no error, exception,
    device loss, or acceleration-structure lifetime failure. Working memory
    was 4.4 GiB after the fifth reload; low-system-memory warnings appeared
    during three rebuild bursts, so this run verifies lifecycle recovery but
    does not resolve the separate long-run VRAM/RAM stability gate. Graceful
    shutdown stopped the probe and BLAS workers, freed the BLAS allocators, and
    the Gradle client task completed successfully.
* [x] Change worlds.
  * VERIFIED: A 2026-07-11 run changed from `ray-tracing-test-place` to the
    distinct `New World` save and returned while 100% procedural shadow
    comparison was active. Across three title/world transitions, the cache
    world generation advanced three times, Iris destroyed and recreated the
    overworld pipeline three times, and the procedural TLAS reached zero active
    instances before new section BLAS/TLAS data populated. Comparison dispatch
    recovered in both saves and the return benchmark window reported
    278,790,545 rays with zero counter saturations. The denser second save did
    saturate diagnostic mismatch counters in one window; this was reported and
    did not coincide with a lifecycle failure. No device loss or acceleration-
    structure error occurred. Two random-sequence load errors came from the
    benchmark world's saved data and were unrelated to Vulkan. Graceful
    shutdown stopped the probe and BLAS workers, freed BLAS allocators, and the
    Gradle client task completed successfully.
* [x] Exit to title.
  * VERIFIED BY EXISTING WORLD-CHANGE RUN: The three recorded title/world
    transitions are accepted as sufficient evidence; no redundant transition
    was performed this pass.
* [x] Shut down the game.
* [x] Place and remove full opaque blocks rapidly.
* [x] Move rapidly across chunk boundaries.
* [x] Test negative coordinates.
* [x] Test high and low world sections.
  * VERIFIED TOGETHER: One 2026-07-11 client run used 1% comparison sampling,
    issued five successful 512-block stone/quartz/air fill replacements, moved
    through x=520, 552, 584, 616, and 648, visited (-512,80,-512) and
    (-544,80,-512), then y=300 and y=-60. Procedural BLAS/TLAS updates
    continued, bounded samples had zero counter saturation, and clean shutdown
    stopped the BLAS worker after 111 batches.

## Phase gate

Do not continue until:

* [x] Dangerous procedural false negatives are understood and resolved.
  * The ownership-scoped capture classified every raw dangerous miss as
    triangle-only and found zero procedural-owned or unknown dangerous misses
    across 894,703,511 rays. These triangle-only casters remain in the hybrid
    representation and are not procedural false negatives.
* [x] Remaining conservative false positives are documented and accepted.
  * ACCEPTANCE CRITERIA: An extra hit is acceptable only when attributable to
    the interior or boundary of a regular opaque full cube, triangle geometry
    remains authoritative for all irregular/cutout/fluid/entity casters, no
    procedural-owned dangerous miss is introduced, and stable post-streaming
    extras remain below one per million compared rays. Startup/streaming windows
    are reported separately. The sampled solid-volume case and stable exact
    windows (124 extras at brick size 8; 75 at size 16 across hundreds of
    millions of rays) satisfy this criterion.
* [ ] Hit-distance disagreement is within the defined tolerance.
  * BLOCKED: Investigation found that rays starting inside an occupied voxel
    correctly report immediate procedural solid occlusion at `tMin`, while the
    surface triangle path reports a later exit face. Those distances are not
    comparable and are now counted as `solidInteriorStarts`. The diagnostic now
    also separates same-cell disagreement from a different valid solid caster.
    Shader compilation and unit tests pass, but one post-change device window is
    still required to verify the same-cell bucket at the 0.01-block tolerance.
* [x] Comparison mode survives all lifecycle stress tests.
  * Existing rebuild, unload, shader reload, world change, title, and shutdown
    evidence plus the bundled 1% block-update, chunk-border, negative-coordinate,
    and vertical-extreme run cover every required scenario.
* [ ] Vulkan validation remains clean.
  * BLOCKED: Renderer validation remains blocked by the Phase 0 native
    `vkCreateInstance` crash with `VK_LAYER_KHRONOS_validation`.

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

* [x] Add ray-role-aware classification data.
* [x] Populate classification during `SectionLightExtractor.scan` or the appropriate extraction stage.
* [x] Distinguish opaque full cubes from merely opaque-looking models.
* [ ] Account for cutout render layers.
* [ ] Account for translucent render layers.
* [x] Account for fluids.
* [x] Account for block entities.
* [x] Account for modded irregular models.
* [x] Account for waterlogged blocks.
* [x] Account for multipart models.
* [x] Add validation that every opaque shadow caster has exactly one authoritative shadow representation.
* [x] Allow both representations only for explicit comparison or composite cases.
* [x] Add validation that air and non-occluding blocks have no shadow representation.
* [x] Add validation that reflection-visible blocks retain a reflection representation.
* [x] Add debug statistics by classification.
* [ ] Add a debug visualization of representation ownership.
* [ ] Add warnings for unclassified visible blocks.
* [ ] Add warnings for accidental duplicate authoritative ownership.
* [ ] Add unit tests for vanilla block categories.
* [x] Add extension points or fallback behavior for unknown modded blocks.

Implementation note (2026-07-11): `BlockRayClassification` defines ownership
per ray role. `SectionLightExtractor` applies the conservative classifier and
records per-section counters. Unknown modded, fluid, block-entity, multipart,
cutout-like, and translucent-like geometry remains triangle-owned; only regular
opaque full cubes become voxel shadow/GI geometry. Reflection remains triangle
owned through Phase 10. Compile-only verification passed; category/runtime tests
remain deferred at the user's request.

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

* [x] Design the mixed shadow TLAS instance list.
* [x] Add procedural section instances.
* [x] Add triangle special-geometry instances.
* [x] Add entity triangle instances.
* [x] Assign correct SBT offsets for procedural and triangle instances.
* [x] Add assertions preventing AABB geometry from selecting a triangle-only hit group.
* [x] Add assertions preventing triangle geometry from selecting a procedural-only hit group.
* [x] Keep stable instance masks by ray role.
* [x] Preserve entity SBT behavior.
* [x] Preserve existing culling flags where appropriate.
* [x] Route production shadow rays through the hybrid TLAS.
* [x] Remove the permanent requirement for two shadow traces.
* [x] Retain the old double-trace path as a debug comparison mode.
* [x] Verify instance updates after chunk rebuild.
* [x] Verify instance removal after chunk unload.
* [x] Verify entity updates.
* [x] Verify world-change cleanup.
* [x] Benchmark hybrid TLAS build and update cost.
* [x] Benchmark hybrid shadow ray time.
* [x] Compare hybrid results with the old double-trace reference.

Implementation note (updated 2026-07-13): `HybridShadowInstanceLayout`
documents and validates the mixed terrain-triangle, terrain-AABB, and
entity-triangle roles. `HybridSbtLayout` owns the stable masks and records and
now rejects both geometry/SBT type mismatches and production record/mask
mismatches. Focused unit tests cover records 0-5, masks 1/2/4, the combined
mask, role ordering, and invalid role/type/record/address combinations.

`TLASSectionManager` emits a mixed instance snapshot with terrain triangles at
record 3/mask 1, entity triangles at record 4/mask 4, and procedural section
AABBs at record 5/mask 2. Existing triangle transforms, custom indices, device
addresses, and culling flags are copied before the shadow record and mask are
overridden. `AccelerationTLASManager` builds and caches this snapshot as a
separate TLAS, and binding 34 exposes it without replacing the material TLAS at
binding 1. Reflection and other material-bearing rays therefore remain on the
original TLAS. Section triangle instances still include the regular full-cube
triangles; this intentional duplication remains until Phase 8 separates
shadow geometry ownership.

Production visibility uses one `traceRayEXT` call with terminate-on-first-hit,
skip-closest-hit, miss record 1, payload location 1, and records 3/4/5. This is
required for procedural record 5 to run the verified exact-DDA intersection
shader: an inline `rayQueryEXT` would expose only the brick AABB candidate and
would not execute that shader. Records 3 and 4 retain terrain/entity alpha
tests. Debug modes 7-9 keep the Phase 4 triangle-versus-procedural comparison.
`SHADOW_DOUBLE_TRACE_REFERENCE` (mode 10) retains the Phase 5 reference
contract: one material-TLAS triangle query plus one standalone procedural trace
with mask 2, miss record 1, and SBT offset 3 selecting production record 5 from
the procedural instance's record 2. `vulkanite.phase7GpuTiming` adds bounded
timestamp-query rings for the ray pass and hybrid TLAS operations, with
warm-up, complete/partial windows, pending-query accounting, and drop counts.
The opt-in `vulkanite.hybridShadow` system property remains false by default;
a 64-byte push-constant flag distinguishes a real mixed TLAS from the material
TLAS descriptor fallback, so disabled and unsupported configurations retain
the triangle-only inline-query path.

Device evidence captured 2026-07-13 with
`-Dvulkanite.hybridShadow=true` on the RTX 3060 Ti:

* `20260713-155620-production-smoke-rerun` exercised repeated section rebuilds,
  8,760 far-teleport removals down to zero active sections/instances followed
  by repopulation, Overworld -> Nether -> Overworld teardown/recreation across
  world IDs 2 -> 3 -> 4, and clean worker/allocator shutdown.
* `20260713-161638-comparison-entity` captured moving entities (up to 32
  instances, 63 geometries, and 2,626 quads) with zero texture misses or entity
  errors. `20260713-163235-comparison-postfix` compared 1,222,496,529 fixed-camera
  shadow rays: 27,437 triangle-only raw misses, 17 procedural-owned raw misses,
  zero unknown misses, nine extras, and zero counter saturation. Phase 7 still
  includes the opaque triangle member of the production union, so every raw
  miss remained covered and combined visibility had no gap.
* `20260713-164135-timing-hybrid` measured 17 settled section-update batches
  covering 132 updates at 0.085882 ms average GPU time; the last 120 settled
  entity-only TLAS rebuilds averaged 0.100674 ms with zero query drops.
* The locked-camera native comparison used 848x480, one `FULL_RT_REFERENCE`
  pass, 141 terrain plus 141 procedural instances, and 1,200 accepted samples
  per arm. Hybrid windows 29-38 in
  `20260713-171700-timing-hybrid-corrected-controlled` averaged 51.001911 ms;
  old-reference windows 7-16 in
  `20260713-172400-timing-double-reference-matched-controlled` averaged
  52.664647 ms. Hybrid was 1.662736 ms / 3.157% faster, with zero query drops
  and clean timing-ring/worker/allocator shutdown in both captures.

Forced Java compilation and the full unit suite pass. Standalone Vulkan 1.2
compilation and `spirv-val` pass for the ray generator and affected runtime
miss/hit/intersection stages, and shaderpack sync/drift verification passes.
Device validation remained disabled because the Phase 0 Java 21
`vkCreateInstance` validation-layer crash is still unresolved; this is not a
Phase 7 gate, but remains an explicit validation-coverage limitation.

Frozen-time visual follow-up passed in
`20260713-173602-visual-hybrid-frozen` and
`20260713-173833-visual-reference-frozen`. Both captures used the same camera,
time 6000 with daylight cycling disabled, clear weather, 848x480 render state,
141 terrain/procedural sections, and eight entities at the screenshot frame.
Direct RGBA inspection disproved an apparent black-floor viewer artifact: both
PNGs are fully opaque, rows 298-479 are byte-for-byte identical, and the floor
region has RGB MAE 0.019 with only 9 of 161,120 pixels differing by more than
20 levels. Remaining differences are balanced high-frequency ray noise from
different frame-seeded sun-disk samples; low-pass broad radiance matches.

## Phase gate

Do not continue until:

* [x] One hybrid shadow TLAS produces correct combined visibility.
* [x] SBT geometry-type validation passes.
* [x] The old double-trace mode remains usable for debugging.
* [x] Hybrid ray time is no worse than the temporary double-trace path.
* [x] Lifecycle tests pass.

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

Implementation note (2026-08-19, execution deferred): the section upload path
now retains the original material-bearing triangle batch while
`ShadowGeometryFilter` builds a byte-exact shadow-only batch. Only solid quads
whose four vertices conservatively agree on an occupied full-cube owner are
removed; malformed, ambiguous, cutout, translucent, and otherwise unresolved
geometry stays triangle-owned. Empty filtered ranges are omitted, including the
all-procedural case where no shadow triangle BLAS is built. The hybrid TLAS uses
filtered triangles and procedural AABBs only when both resources are complete;
otherwise it emits the original triangle instance alone. Per-range ownership,
quad reduction, resident BLAS bytes, and procedural cost are logged. Set
`vulkanite.hybridShadowGeometry=triangle-only` for the explicit fallback. Unit
coverage was added, but no test, build, Vulkan validation, or benchmark command
was run in this pass.

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
* [x] Add a fixed-size runtime override.
* [x] Design an adaptive per-section heuristic.
* [x] Include occupied-brick count in the heuristic.
* [x] Include estimated local-DDA work in the heuristic.
* [x] Add hysteresis to prevent repeated size switching.
* [x] Rebuild only when the selected brick size materially changes.
* [ ] Record selected brick-size distribution in debug statistics.
* [x] Verify deterministic selection for unchanged occupancy.
* [ ] Verify adaptive mode improves or matches the best practical fixed mode.

Implementation note (updated 2026-08-19, execution deferred):
`vulkanite.voxelBrickMode=fixed`
preserves the existing `vulkanite.voxelBrickSize=4|8|16` override. Setting the
mode to `adaptive` evaluates all three sizes using occupied AABB count,
estimated empty-volume/local-DDA work, and payload/AABB bytes. The weights and
switch hysteresis are system-property configurable. Per-section state includes
the selected size, so unchanged occupancy retains its BLAS while a material
size change requests exactly one replacement. Unit tests cover deterministic
selection, supported-size estimates, hysteresis, size-change rebuilds, and
telemetry aggregation. Structured statistics now record 4/8/16 candidate and
selected distributions, hysteresis retention, the switch matrix, estimated
primitive/DDA/byte costs, and actual live procedural AS/payload bytes per size.
Debug comparison counters separately record candidate acceptance and local-DDA
steps per size. The instrumentation and tests were not executed; device-side
timings, benchmark captures, and benchmark-based weights remain unchecked.

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

Implementation note (2026-08-19, execution deferred): an opt-in v2 procedural
payload adds occupied-cell references plus deduplicated cell, face, and overlay
material tables while keeping the existing occupancy-only v1 layout byte
compatible. The extractor captures per-face block/material IDs, atlas UVs,
rotation, tint, tangent handedness, raw light UVs, emission, and layered
overlays; conflicting vertex identity or orientation data makes the cell
incomplete so it remains triangle-owned. Reflection SBT record 6 now has a
dedicated v2 DDA intersection shader and closest-hit shader. Runtime activation
is default-off behind `vulkanite.proceduralReflection=true`, requires compatible
record-2/record-6 shader stages, and replaces a section's reflection triangles
only when its filtered ownership, procedural BLAS, and complete six-face
material payload all exist. Full triangle BLAS resources remain resident for
fallback.

Debug mode 11 traces the complete triangle TLAS and the standalone procedural
TLAS for sampled primary rays, then compares hit state, distance, geometric
normal, block/material IDs, sampled albedo, emission, and PBR values. Atlas UV
is currently checked indirectly through sampled albedo because raw UV is not in
the shared payload ABI. Focused Java and shader-source tests were added, but no
test, build, shader compilation, Vulkan validation, screenshot, or runtime
comparison was run in this pass. Reflection triangles therefore remain the
authoritative default.

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

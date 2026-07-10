# Hybrid acceleration Phase 0 baseline

This file records the repository audit and defines the evidence still required
before Phase 1 may begin. The audit was performed on 2026-07-10 against commit
`2fe83f4872cdd3aa7f8c65ed5b2a395f1dce0c7f` plus the current uncommitted
renderer work.

## Build and toolchain

| Component | Required or observed version | Evidence |
| --- | --- | --- |
| Java compiler toolchain | JDK 21 | `build.gradle` sets `targetJavaVersion = 21` and requests a Java 21 toolchain. |
| Gradle launcher JVM | JDK 17 is sufficient for Gradle 8.7 | The wrapper ran on Oracle JDK 17.0.12 while Gradle selected the JDK 21 compiler toolchain. |
| Gradle | 8.7 | `gradle/wrapper/gradle-wrapper.properties`. |
| Fabric Loom | 1.6.12 observed | Gradle configuration output; the build requests `1.6-SNAPSHOT`. |
| LWJGL Vulkan and Shaderc bindings | 3.3.1 | `build.gradle` pins all LWJGL modules to 3.3.1. |
| Runtime shader target | Vulkan 1.2, SPIR-V 1.4 | `ShaderCompiler.compileShader`. |
| Installed Vulkan SDK | 1.4.335.0 | `VULKAN_SDK=C:\VulkanSDK\1.4.335.0`. |
| Installed standalone `glslc` | shaderc v2023.8; SPIR-V Tools v2025.5 | `glslc --version`. The renderer itself uses the LWJGL Shaderc native, not this executable. |

The committed tree was checked out into an isolated Git worktree and built
with:

```text
gradlew.bat clean build --stacktrace --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL` in 41 seconds, with 8 tasks executed. Loom emitted
known mixin class-resolution diagnostics during configuration and javac emitted
two deprecation warnings, but no build task failed. The isolated worktree was
used so the existing uncommitted changes were neither included nor modified.

The repository's GitHub workflow still provisions Java 17. That works as the
Gradle launcher only when a Java 21 toolchain is available or downloadable; the
workflow does not explicitly provision the compiler version requested by the
build.

## Audit hardware

`vulkaninfo --summary` reported:

* Vulkan loader instance version 1.4.321.
* NVIDIA GeForce RTX 3060 Ti, Vulkan 1.4.325, driver 591.86.
* Intel UHD Graphics 750, Vulkan 1.3.275, driver 101.5334.
* `VK_EXT_debug_utils` revision 2.
* `VK_LAYER_KHRONOS_validation` version 1.4.335.

This proves the audit machine has the validation layer and debug-utils
extension. It does not prove that a Minecraft frame ran under validation.

The renderer now has an opt-in validation path. Enable it with one of:

```text
config/vulkanite.properties: vulkanValidationEnabled=true
JVM: -Dvulkanite.validation=true
environment: VULKANITE_VALIDATION=true
```

The JVM property overrides the environment, which overrides the persisted
configuration. When enabled, startup checks for both
`VK_LAYER_KHRONOS_validation` and `VK_EXT_debug_utils`, requests them during
instance creation, and keeps the debug callback alive with the Vulkan context.
Missing requested support fails startup with an actionable error instead of
silently running without validation. Callback output is prefixed with
`[Vulkanite/Validation]`, and shutdown reports validation warning/error totals.

The 2026-07-10 validation-enabled client smoke run reached instance creation
and confirmed the Khronos layer was selected, then failed in native code inside
`vkCreateInstance` through the Java 21 runtime's local `msvcp140.dll`.
`VK_LOADER_LAYERS_DISABLE=~implicit~` did not change the result. A control run
with Vulkanite's toggle disabled and
`VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation` reproduced the same crash,
while `vulkaninfo --summary` succeeded with that layer. This isolates the
remaining gate blocker to the client runtime/layer combination rather than the
new toggle or debug callback. No Minecraft frame has yet run under validation.

## Current architecture map

```text
Sodium ChunkBuilderMeshingTask
  -> MixinChunkRenderRebuildTask
     -> SodiumResultAdapter: section mesh -> triangle geometry ranges
     -> SectionLightExtractor: logical states -> lights + 4096-bit opacity
  -> MixinRenderSectionManager
     -> Vulkanite.upload
        -> AccelerationManager
           -> AccelerationBlasBuilder (async queue 1)
              -> BLASJobEnqueuer / BLASBuildWorker
              -> one triangle BLAS result per non-empty render section
           -> AccelerationTLASManager
              -> TLASSectionManager: persistent terrain instances
              -> EntityCapture -> EntityBlasBuilder: transient triangle BLASes
              -> one mixed TLAS consumed by the ray pipeline

Mixed TLAS
  -> terrain instance: SBT record offset 0
     -> ray0_0.rchit + ray0_0.rahit
  -> entity instance: SBT record offset 1
     -> ray0_1.rchit + ray0_1.rahit

TLASSectionManager bindless geometry set
  -> binding 0: variable-size storage-buffer array
  -> instanceCustomIndex: first descriptor for the instance
  -> geometryIndex: offset within that instance's descriptor range
```

### Ownership details

* Chunk BLAS input capture: `MixinChunkRenderRebuildTask` and
  `SodiumResultAdapter`.
* Async chunk BLAS creation: `AccelerationBlasBuilder` and the classes in
  `acceleration/blas`; `BLASGeometryProcessor` emits triangle geometry.
* Entity capture and BLAS creation: `EntityCapture` and `EntityBlasBuilder`.
* TLAS construction, caching, timestamping, and lifetime:
  `AccelerationTLASManager`; section instance and descriptor ownership:
  `TLASSectionManager` and `TLASSectionHolder`.
* SBT construction: shader filenames are discovered by `MixinProgramSet`,
  compiled by `RaytracingShaderSet`, ordered by `RaytracePipelineBuilder`, and
  stored by `VRaytracePipeline`.
* Shadow visibility: `alphaAwareVisibility` in
  `shaderpacks/VulkaniteRT/shaders/ray0.rgen` performs an inline ray query. It
  distinguishes terrain and entities through the instance SBT offset and
  performs alpha testing only for terrain descriptors.
* Reflection and diffuse incident rays: `traceSimpleRadiance`, primary fallback
  tracing, the indirect bounce loop, and the specular bounce loop in
  `ray0.rgen` call `traceRayEXT` against the same mixed TLAS.
* Device support discovery: `DeviceCapabilities.detect`; required device
  extension checks and queue-family selection: `VInitializer`.
* Queue submission and cross-queue timeline waits: `CommandManager` and
  `AccelerationManager.buildTLAS`.

## Current radiance-cache request path

```text
CacheResolvePass compute feedback
  -> CacheFeedbackPass host-visible ring
  -> CPU readback after queue completion
  -> CacheRequestQueue deduplication and priority ordering
  -> CacheRequestBatch (bounded to 256 requests per frame)
  -> CPU-packed cache fill request buffers
  -> bounded ray-generation dispatch
```

`SectionLightManager.drainPendingCacheRequests` also creates CPU requests from
dirty world sections. Therefore request discovery is GPU-assisted but request
compaction, prioritization, batching, and dispatch sizing remain CPU-driven.

## Foundation verification

Verified:

* Sodium section meshes feed per-section triangle BLAS jobs.
* Entities use triangle BLAS geometry, a separate hit group, and instance SBT
  record offset 1.
* `SectionLightExtractor` scans logical block state and produces a 64-word,
  4096-bit opacity field.
* `SectionLightTable` builds four hierarchical occupancy mip levels.
* `VoxelBrickGeometry` emits tightly packed 24-byte AABBs and fixed-stride
  local-DDA payload records for brick sizes 4, 8, and 16.
* Queue-family selection requires graphics, compute, and transfer flags and at
  least the requested number of queues.

Corrections to the initial assumptions:

* `DeviceCapabilities` is a supported-capability snapshot. It does not carry a
  separate enabled-backend selection.
* Startup reports `supportedAccelerationTier`; it does not yet report a
  separately selected backend tier.
* `multiQueueOverlap` currently means that two queues exist in the selected
  family. It is availability, not measured overlap.

## Evidence still required for the Phase 0 gate

Resolve the documented Java 21/validation-layer native startup crash, then run
the triangle-only renderer with Vulkan validation enabled and preserve the
full validation log. In the same fixed benchmark world and camera paths,
capture all ten scenes listed in the main plan and record:

* resolution, render distance, shaderpack settings, seed, coordinates, camera
  orientation, time and weather;
* average, median, p95, p99, 1% low, and worst frame time after warm-up;
* Vulkan allocation totals and process VRAM before loading, after warm-up, and
  after the capture route;
* chunk BLAS CPU enqueue time, GPU build time, and update latency;
* TLAS CPU encode time and GPU build time;
* ray-tracing pass GPU time, including shadow and reflection timing when they
  can be isolated.

Existing screenshots under `run/screenshots` and timing lines in
`run/logs/latest.log` are not accepted as the reference set: they are not
labelled with the required scenes and do not establish triangle-only or
validation-enabled conditions.

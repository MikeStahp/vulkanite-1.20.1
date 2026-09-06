# Vulkanite RT shaderpack

The first-party shaderpack for Minecraft 1.20.1, Iris, Sodium, and Vulkanite.
Iris rasterizes primary surface records; Vulkanite traces secondary rays and
writes radiance and denoiser guides, optionally runs DLSS/RR, and blits the
result into `colortex0` before Iris final presentation.

## Source ownership

Edit `shaders/` here. `gradlew.bat runClient` synchronizes tracked shaderpacks to
`run/shaderpacks`; that directory is generated output. Ray stages are discovered
from the active pack by `MixinProgramSet`. The mod injects only its shared
ReSTIR library, `assets/vulkanite/shaders/raytracing/lib/restir.glsl`.

See [the shader index](shaders/SHADER_INDEX.md) for entry points and contracts.
Shaderpack options live in `shaders/shaders.properties`; denoising and runtime
controls are exposed through Vulkanite's configuration/UI.

## Rendering techniques

| Concern | Owner and selection |
| --- | --- |
| Primary visibility and compatibility | Iris/Sodium raster G-buffer, including entities, cutouts, and fluids. |
| Full-frame secondary lighting | `FULL_RT_REFERENCE` dispatches `ray0.rgen` at render resolution. |
| Cache-based lighting | `CACHE_ON_HIT` traces bounded fill requests, then resolves lighting; `CACHE_RESOLVE_ONLY` resolves without ray dispatch. |
| Geometry traversal | Triangle reference by default; procedural opaque shadows and reflections are separate experiments. |
| ReSTIR | Optional reservoir reuse; separate from geometry selection and DLSS/RR. |
| Reconstruction | Vulkanite denoiser selection consumes the shared radiance/guide outputs. |

`HybridAccelerationConfig` owns startup dependencies. Normal launches skip
experimental SBT groups, procedural BLAS builds, and filtered shadow batches.
Development options use `-Pvulkanite.<option>=...` with Gradle or
`-Dvulkanite.<option>=...` on a directly launched JVM:

- `hybridShadow=true`: select the hybrid shadow TLAS and its shader/BLAS dependencies.
- `compileDiagnostics=true`: compile comparison stages and build standalone procedural geometry.
- `proceduralReflection=true`: enable the experimental material-bearing reflection path.
- `hybridShadowPipeline=true`: compile shadow stages for pipeline experiments, without selecting hybrid dispatch.
- `proceduralBlas=true|false`: explicitly override procedural geometry construction.
- `hybridShadowGeometry=triangle-only`: retain unfiltered triangle shadow geometry for comparison/fallback.
- `voxelBrickMode=fixed|adaptive` and `voxelBrickSize=4|8|16`: select brick policy once procedural geometry is enabled.

These are startup options; restart after changes. Device capabilities and
compatible shader stages still gate execution. A single experiment flag does
not certify visual correctness or performance. See the
[hybrid acceleration plan](../../plans/HYBRID_GPU_ACCELERATION_PLAN.md).

PowerShell example (quote dotted property arguments):

```powershell
.\gradlew.bat runClient '-Pvulkanite.hybridShadow=true'
```

## ReSTIR contract

A pack opts into the mod-owned API in `ray0.rgen` with:

```glsl
#define VULKANITE_RESTIR 1
```

Use `0` to keep shared declarations with ReSTIR disabled; omit the define if the
pack does not use the API. The runtime ReSTIR setting also controls activation.

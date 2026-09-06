# Vulkanite RT shader index

Use source filenames and compiler diagnostics to locate code. Preprocessed line
numbers depend on defines and includes; the former estimated line table was stale.

## Entry points

| Source | Role |
| --- | --- |
| `gbuffers_terrain.*`, `gbuffers_entities.*`, `gbuffers_water.*` | Iris primary surface records. |
| `ray0.rgen` | Full-frame lighting, bounded cache fills, shadow visibility, and opt-in comparisons. |
| `ray0_0.rchit`, `ray0_0.rahit` | Terrain triangles, SBT hit record 0. |
| `ray0_1.rchit`, `ray0_1.rahit` | Entity triangles, SBT hit record 1. |
| `ray0_2.rint`, `ray0_2.rchit` | Procedural terrain and diagnostic hit data, record 2; closest-hit stage requires diagnostics. |
| `ray0_3.rchit`, `ray0_3.rahit` | Hybrid terrain-triangle shadows, record 3. |
| `ray0_4.rchit`, `ray0_4.rahit` | Hybrid entity shadows, record 4. |
| `ray0_5.rint`, `ray0_5.rchit` | Procedural opaque shadow visibility, record 5. |
| `ray0_6.rint`, `ray0_6.rchit` | Opt-in material-bearing procedural reflections, record 6. |
| `ray0_0.rmiss`, `ray0_1.rmiss`, `ray0_2.rmiss` | Radiance, production shadow, and diagnostic miss records respectively. |

Normal launches use hit records 0/1 and miss records 0/1. `HybridSbtLayout` owns
record indices and ray masks; `HybridAccelerationConfig` owns feature selection;
`RaytracingShaderSet` selects the stages to compile. Do not infer active execution
from the presence of a shader file.

## Shared contracts

- `lib/rt/payload.glsl`: ray payload ABI.
- `lib/rt/data.glsl` and `lib/rt/fragment_info.glsl`: captured geometry and hit reconstruction.
- `lib/rt/pbr.glsl`, `lib/pbr/`: material response.
- `lib/rt/settings.glsl`, `shaders.properties`: pack options.
- `lib/rt/sky.glsl`, `lib/rt/utils.glsl`: sky and shared helpers.
- Mod-owned `assets/vulkanite/shaders/raytracing/lib/restir.glsl`: injected ReSTIR API.

`PipelineDescriptorSets` defines the common ray descriptor ABI. Ray shaders may
reflect a subset of its bindings. Compute feedback/resolve passes have separate
reflected layouts; they share `RtxFrame` resources, not ray descriptor set numbers.

# Bundled shader resource audit

## September 2026 disposition

The loader audit was repeated during rendering consolidation. `MixinProgramSet`
discovers `rayN.rgen` and `rayN_M.rmiss|rchit|rahit|rint` through the active Iris
shaderpack's `sourceProvider`. Its only classpath shader read is
`assets/vulkanite/shaders/raytracing/lib/restir.glsl`.

Repository searches of Java resource loading, shader includes, mixins, resource
metadata, tests, and Gradle tasks found no loading path for the bundled legacy
ray/deferred programs. `ShaderCompiler` receives already provided source; it
does not discover a second fallback pack. Gradle copies tracked shaderpacks from
`shaderpacks/` into the development run directory.

Removed 14 inactive assets from `src/main/resources/assets/vulkanite/shaders`:

- Nine ray entry points: `ray0.rgen`, `raygen.rgen`, `ray0_0.rmiss`, `miss.rmiss`,
  `ray0_0.rchit`, `ray0_1.rchit`, `closesthit.rchit`, `ray0_0.rahit`, `ray0_1.rahit`.
- Private ray helpers `lib/utils.glsl` and `lib/lighting.glsl`.
- Deferred experiments `deferred_lighting.vert` and `deferred_lighting.frag`.
- Their unused `include/raylib.glsl` helper.

The active shaderpack stages and mod-owned ReSTIR library remain authoritative.
No helper from the removed programs is required by the current loader. This is
repository loading-path evidence; arbitrary external mods reading private
classpath files were not inventoried. The former shaderpack README and estimated
preprocessed line index now describe the actual sources and ownership.

## Validation

Java compilation, unit tests, and jar packaging are checked with Gradle. No live
Vulkan frame, GPU timing, shader reload, or external shaderpack run is implied by
those checks. See `HYBRID_GPU_ACCELERATION_PLAN.md` for current pass results.

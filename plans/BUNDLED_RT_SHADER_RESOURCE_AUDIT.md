# Bundled RT Shader Resource Audit

## Status

Audited during the first directional-lighting resource pass.

Current `MixinProgramSet` loads ray tracing stages from the active shaderpack via
Iris' `sourceProvider`:

- `ray0.rgen`, `ray1.rgen`, ...
- `rayN_M.rmiss`
- `rayN_M.rchit`
- `rayN_M.rahit`
- `rayN_M.rint`

The only bundled classpath shader resource injected by this loader is:

- `src/main/resources/assets/vulkanite/shaders/raytracing/lib/restir.glsl`

## Bundled Files Not Loaded By The Current Shaderpack Path

These files remain in `src/main/resources/assets/vulkanite/shaders/raytracing`,
but the current loader does not use them as fallback ray-tracing stages:

- `ray0.rgen`
- `raygen.rgen`
- `ray0_0.rmiss`
- `miss.rmiss`
- `ray0_0.rchit`
- `ray0_1.rchit`
- `closesthit.rchit`
- `ray0_0.rahit`
- `ray0_1.rahit`
- `lib/utils.glsl`
- `lib/lighting.glsl`

## Decision

Keep `lib/restir.glsl` as a mod-owned injected library for now.

Do not delete the other bundled files in this pass. Treat them as stale fallback
or experiment assets until a shader-load audit confirms no external pack or
development path still expects them. Useful helper code should move into
`shaderpacks/VulkaniteRT/shaders/lib/rt` before deletion.

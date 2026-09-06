# Mod-owned shader resources

`raytracing/lib/restir.glsl` is the shared ReSTIR implementation injected by
`MixinProgramSet`. Keep its API marker and injection path stable.

Ray stages and raster shaders come from the active Iris shaderpack. The tracked
first-party source is `shaderpacks/VulkaniteRT/shaders`; `run/shaderpacks` is a
generated development copy. Cache compute shaders are owned by their Java pass
classes. There is no bundled fallback ray pipeline.

Unused bundled ray/deferred entry points and their private helpers were removed
after the September 2026 loader audit. See
`plans/BUNDLED_RT_SHADER_RESOURCE_AUDIT.md` for the ownership decision.

# Vulkanite Deferred Compatibility Baseline

## Goal

Build `VulkaniteDeferred` as the main Iris shaderpack first, then add Vulkan compute optimizations only when the active shaderpack and config explicitly select Vulkanite's deferred path. The baseline must run on non-RTX Vulkan hardware and should not change behavior for unrelated Iris shaderpacks or mods.

Vulkanite ships two first-party shaderpacks:

- `VulkaniteDeferred`: main non-RTX deferred path and source of the shared G-buffer/material contract.
- `VulkaniteNormal`: standard Vulkanite path. Its RT shaders should be rewritten to consume the deferred contract instead of defining a separate one.

## Compatibility Rules

- Do not require ray tracing extensions for deferred rendering.
- Do not resize Iris render targets for ordinary shaderpacks.
- Do not apply DLSS jitter, DLSS resolution scaling, or Ray Reconstruction assumptions to the deferred baseline.
- Do not infer that `VulkaniteDeferred` is active because its folder exists on disk; use the active Iris `shaderPack` value or an explicit Vulkanite override.
- Keep Vulkan compute passes optional. The shaderpack must still have a predictable GL/Iris composition path while compute integration is being developed.
- Keep G-buffer bindings stable and documented before adding temporal features.

## Baseline G-Buffer Contract

The deferred pack owns this layout:

| Iris target | Vulkan binding | Format | Contents |
| --- | ---: | --- | --- |
| `colortex0` | 7 | `RGBA16F` | Albedo RGB, alpha A |
| `colortex1` | 8 | `RGBA16F` | F0 RGB, roughness A |
| `colortex2` | 9 | `RGBA16F` | Encoded world normal RGB, roughness A |
| `colortex3` | 10 | `RGBA32F` | World position RGB, metallic A |
| `colortex4` | 11 | `RGBA16F` | Blocklight R, skylight G, AO B, emission A |
| `colortex5` | 12 | `RGBA16F` | Shadow coord RGB, SSS A |

If an existing shader uses bindings 7-11 for `colortex1`-`colortex5`, keep that path separate from this baseline and name it as the RT contract. Do not silently mix both contracts.

## Build Order

1. Stabilize shaderpack source of truth.
   - Move the editable `VulkaniteDeferred` and `VulkaniteNormal` shaderpacks out of ignored `run/shaderpacks` into a tracked source directory.
   - Add a dev copy/sync task so runtime files are generated, not hand-edited.

2. Make the Iris path correct at full resolution.
   - G-buffer passes should render at framebuffer size.
   - Composite should produce correct lighting with no Vulkan compute dependency.
   - No jitter, no temporal reprojection, no upscaler assumptions.

3. Add Vulkan compute as an opt-in enhancement.
   - Dispatch only when `ShaderpackSettingsHandler.shouldUseDeferredRenderingPath()` is true.
   - Bind only VulkaniteDeferred-owned targets and custom images.
   - Use `RGBA16F` outputs by default unless a pass proves it needs `RGBA32F`.

4. Add optional performance modes.
   - Half or scaled G-buffers only for VulkaniteDeferred.
   - Add resolution compatibility checks before enabling scaled targets.
   - Keep fallback to full-resolution G-buffers.

5. Add temporal features last.
   - Add motion vectors and jitter only after depth, world position, and projection contracts are verified.
   - Keep jitter isolated from non-temporal deferred rendering.

## Immediate Guard Rails

- `ShaderpackSettingsHandler.getCurrentShaderpackName()` should read Iris' active pack selection instead of using folder existence.
- `MixinRenderTarget` may only scale `colortex1`-`colortex5` when `shouldUseDeferredRenderingPath()` is true.
- `DeferredLightingPass` should tolerate missing optional inputs and skip dispatch rather than binding incomplete descriptor sets.

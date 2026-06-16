# Vulkanite RT Shader Pack

A ray tracing shader pack for Vulkanite, implementing a hybrid rendering approach with lightmap generation capabilities.

## Features

- **Hybrid Rendering**: Combines rasterized G-Buffer with ray traced effects
- **Lightmap Generation**: Separate render targets for blocklight, sunlight, and LABPBR
- **Mod-owned ReSTIR**: Uses Vulkanite's shared reservoir implementation
- **Multi-layer Lighting**: Different ray tracing approaches for different light types

## Shaders

### Ray Generation (raygen.rgen)
- Reads G-Buffer data for primary ray setup
- Launches rays for block light, sun light, and LABPBR separately
- Outputs to three separate lightmap render targets

### Closest Hit (closesthit.rchit)
- Handles geometry interactions
- Different lighting calculations based on light type
- Samples block atlas textures for material properties

### Miss (miss.rmiss)
- Handles rays that don't hit any geometry
- Provides appropriate background values for each light type

### Any Hit (anyhit.rahit)
- Performs alpha testing for transparent materials
- Allows early ray termination for performance

## Lightmap Render Targets

1. **Block Light** (`blockLightImage`) - Indirect illumination from emissive blocks
2. **Sun Light** (`sunLightImage`) - Direct illumination from the sun
3. **LABPBR** (`labpbrImage`) - Advanced lighting model data (placeholder)

## Requirements

- Vulkanite mod installed
- Compatible with Minecraft 1.20.1
- Vulkan-compatible GPU with ray tracing support

## Configuration

See `shader.properties` for configurable options including:
- Ray tracing quality settings
- Hybrid rendering parameters
- Performance options

## ReSTIR Contract

Ray tracing packs opt into Vulkanite's shared ReSTIR implementation from
`ray0.rgen`:

```glsl
#define VULKANITE_RESTIR 1
```

Set the value to `0` to keep the shared declarations available while disabling
ReSTIR. Omit the define entirely for packs that do not use ReSTIR.

## Future Improvements

- Advanced denoising algorithms
- More sophisticated LABPBR lighting model
- Improved geometry sampling in closest hit shaders

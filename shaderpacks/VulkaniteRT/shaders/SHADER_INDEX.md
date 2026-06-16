# Vulkanite RT Shader Line Index

This document maps the shader source files to their approximate positions in the preprocessed output for debugging compilation errors.

## Preprocessing Overview

When shaderc compiles the shaders, it:
1. Expands all `#include` directives inline
2. Processes all `#define` macros
3. Removes comments and preprocessor directives
4. The resulting preprocessed source can be 3-5x larger than the original

## ray0.rgen (Main Ray Generation Shader)

**Original file:** `ray0.rgen` (278 lines)
**Approximate preprocessed size:** ~550-600 lines

### Line Mapping (Preprocessed Output)

| Preprocessed Lines | Source | Description |
|-------------------|--------|-------------|
| 1-50 | utils.glsl | Constants, PI, TWO_PI, saturate macros, utility functions |
| 51-150 | utils.glsl | Random number generation, hash functions |
| 151-200 | utils.glsl | Vector utilities, orthonormal basis, tone mapping |
| 201-250 | payload.glsl | Payload struct definition |
| 251-280 | data.glsl | Vertex and Quad struct definitions |
| 281-400 | restir.glsl | ReSTIR reservoir struct and functions |
| 401-420 | settings.glsl | Shader settings defines |
| 421-450 | ray0.rgen | Version, extensions, uniform blocks |
| 451-470 | ray0.rgen | Bindings and resources |
| 471-490 | ray0.rgen | Utility functions (rand, buildOrthonormalBasis) |
| 491-520 | ray0.rgen | main() - Ray setup and hybrid rendering |
| 520-550 | ray0.rgen | main() - Direct lighting setup |
| **550-580** | **ray0.rgen** | **main() - ReSTIR DI implementation** |
| 580-600 | ray0.rgen | main() - Indirect bounce |
| 600-620 | ray0.rgen | main() - Final composition and output |

## ray0_0.rchit (Closest Hit Shader)

**Original file:** `ray0_0.rchit` (93 lines)
**Approximate preprocessed size:** ~350-400 lines

### Line Mapping (Preprocessed Output)

| Preprocessed Lines | Source | Description |
|-------------------|--------|-------------|
| 1-50 | data.glsl | Vertex and Quad struct definitions |
| 51-100 | payload.glsl | Payload struct definition |
| 101-150 | fragment_info.glsl | FragmentInfo struct and functions |
| 151-180 | ray0_0.rchit | Version, extensions, uniforms |
| 181-220 | ray0_0.rchit | getRayQuad(), constants |
| 220-280 | ray0_0.rchit | main() - Hit processing |
| 280-350 | ray0_0.rchit | main() - Shadow transmission |

## ray0_0.rahit (Any Hit Shader)

**Original file:** `ray0_0.rahit` (76 lines)
**Approximate preprocessed size:** ~320-370 lines

### Line Mapping (Preprocessed Output)

| Preprocessed Lines | Source | Description |
|-------------------|--------|-------------|
| 1-50 | data.glsl | Vertex and Quad struct definitions |
| 51-100 | payload.glsl | Payload struct definition |
| 101-120 | fragment_info.glsl | FragmentInfo struct (partial) |
| 121-150 | ray0_0.rahit | Version, extensions, uniforms |
| 151-180 | ray0_0.rahit | Constants and getRayQuad() |
| 180-250 | ray0_0.rahit | main() - Transparency and absorption |
| 250-320 | ray0_0.rahit | main() - Intersection ignore logic |

## ray0_0.rmiss (Miss Shader)

**Original file:** `ray0_0.rmiss` (17 lines)
**Approximate preprocessed size:** ~120-150 lines

### Line Mapping (Preprocessed Output)

| Preprocessed Lines | Source | Description |
|-------------------|--------|-------------|
| 1-50 | payload.glsl | Payload struct definition |
| 51-80 | ray0_0.rmiss | Version, extensions, constants |
| 80-100 | ray0_0.rmiss | main() - Miss payload setup |

## Common Error Patterns

### Error: "unexpected FLOATCONSTANT, expecting IDENTIFIER"

This error typically indicates:
1. **Macro expansion issue** - A `#define` is expanding incorrectly
2. **Missing semicolon** - Previous statement missing semicolon
3. **Type mismatch** - Using a float where an identifier is expected

### Debugging Steps

1. **Check the line number** - Error at line 550 in preprocessed output ≈ line 145-160 in ray0.rgen (ReSTIR section)

2. **Look for macro issues** in the area:
   - `saturate` macro in utils.glsl (defined twice, lines 15-16 and 331-336)
   - `PI`, `TWO_PI` macros
   - Any custom defines in settings.glsl

3. **Check for missing includes** - If an include fails, the preprocessor might produce invalid code

## File Dependencies

```
ray0.rgen
├── /lib/rt/payload.glsl
│   └── (no dependencies)
├── /lib/rt/data.glsl
│   └── (no dependencies)
│   └── /lib/rt/utils.glsl
└── /lib/rt/settings.glsl
    └── (no dependencies)

ray0_0.rchit
├── /lib/rt/data.glsl
├── /lib/rt/payload.glsl
└── /lib/rt/fragment_info.glsl
    ├── /lib/rt/data.glsl (already included)
    └── /lib/rt/settings.glsl

ray0_0.rahit
├── /lib/rt/data.glsl
├── /lib/rt/payload.glsl
└── /lib/rt/fragment_info.glsl

ray0_0.rmiss
└── /lib/rt/payload.glsl
```

## Known Issues

### 1. Duplicate `saturate` Macro (utils.glsl)
- **Location:** Lines 15-20 and 331-336
- **Issue:** Macro defined twice with `#ifndef` guards on second definition only
- **Fix:** Add `#ifndef` guards to first definition or remove duplicate

### 2. ReSTIR `createOrthonormalBasis` vs `buildOrthonormalBasis`
- **utils.glsl** defines `createOrthonormalBasis()` (line 155)
- **ray0.rgen** defines `buildOrthonormalBasis()` (line 69)
- **restir.glsl** calls `createOrthonormalBasis()` (line 102)
- **Status:** These are different functions, no conflict

### 3. Settings Override
- **settings.glsl** defines constants like `GAMMA`, `SPECULAR_INTENSITY`
- **ray0.rgen** also reads these from `ShaderOptions` uniform block
- **Potential conflict:** The `#define` values might override uniform usage

## Quick Reference for Error Line 550

If you're getting an error at **preprocessed line 550**, it's likely in:

**ray0.rgen, lines 145-165** - ReSTIR DI section:
```glsl
#ifdef ENABLE_RESTIR
// --- ReSTIR DI ---
RestirReservoir r;
initReservoir(r);

uint seed = hashUint(pixelCoord.x + pixelCoord.y * 1920, cam.frameId);
float fId = float(cam.frameId);

// 1. Initial Sampling (Stochastic Sun)
vec3 tangent, bitangent;
buildOrthonormalBasis(lightDir, tangent, bitangent);  // <-- Check this line
```

**Possible causes:**
1. `hashUint` function not found (should be in utils.glsl)
2. `buildOrthonormalBasis` signature mismatch
3. `TWO_PI` not defined (used in restir.glsl)

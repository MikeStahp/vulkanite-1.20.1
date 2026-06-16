#ifndef SETTINGS_GLSL
#define SETTINGS_GLSL 1

// Internal settings
#define ATLAS_SCALE 2.0

// Lighting and color settings
#define SUN_INTENSITY 3.4 // Direct sun brightness [0.0 1.8 2.5 3.0 3.4 4.0 5.0 6.0]
#define HDR_EXPOSURE 0.92 // Linear exposure before denoise/upscale [0.6 0.75 0.85 0.92 1.0 1.15 1.35]
#define COLOR_SATURATION 1.06 // Linear color saturation [0.75 0.9 1.0 1.06 1.15 1.25 1.4]
#define COLOR_CONTRAST 1.03 // Linear contrast around middle gray [0.85 0.95 1.0 1.03 1.08 1.15]
#define GAMMA 1.0 // Final gamma control for non-DLSS paths [0.8 0.9 1.0 1.1 1.25 1.5 2.0]
#define AMBIENT_FACTOR 0.05 // Base sky ambient fill [0.0 0.02 0.05 0.08 0.12 0.18 0.25]
#define INDIRECT_SCALE 0.6 // Secondary bounce strength [0.0 0.25 0.45 0.6 0.8 1.0 1.25 1.5 2.0]
#define MIN_LIGHTING 0.05 // Floor for very dark diffuse surfaces [0.0 0.02 0.05 0.08 0.12 0.18 0.25]
#define BLOCKLIGHT_VISUAL_INTENSITY 8.0 // Smooth local-light strength [2.0 4.0 6.0 8.0 10.0 12.0 16.0]
#define BLOCKLIGHT_VISUAL_RANGE 1.45 // Smooth local-light range curve [0.75 1.0 1.25 1.45 1.7 2.0 2.4]
#define BLOCKLIGHT_LOW_FLOOR 0.08 // Keeps low local-light levels visible [0.0 0.03 0.05 0.08 0.12 0.16 0.22]
#define SPECULAR_INTENSITY 1.0 // Direct and environment specular strength [0.0 0.35 0.6 0.8 1.0 1.25 1.6 2.0 3.0]

// Soft shadow settings
#define PENUMBRA_RADIUS 0.034 // Sun angular radius for soft shadows [0.004 0.012 0.02 0.034 0.05 0.075 0.1]
#define PENUMBRA_SAMPLES 8 // Soft shadow samples [2 4 6 8 12 16]
#define PENUMBRA_DIFFUSE_BLEND 0.75 // Diffuse soft-shadow blend [0.0 0.35 0.55 0.75 0.9 1.0]

// ReSTIR Settings
#define RESTIR_MAX_HISTORY 8 // Maximum temporal history samples [4 8 12 16 24 32]
#define RESTIR_SPATIAL_RADIUS 3.0 // Spatial reuse sampling radius in pixels [1.0 2.0 3.0 4.0 6.0 8.0]
#define RESTIR_SPATIAL_SAMPLES 4 // Number of spatial reuse samples [1 2 4 6 8]

// RTX capture workload settings
#define RTX_ENTITY_CAPTURE 1 // Capture entities into the RTX acceleration structure [0 1]
#define RTX_PARTICLE_CAPTURE 1 // Capture particles into the RTX acceleration structure [0 1]
#define RTX_CAPTURE_INTERVAL 2 // Frames between transient RTX captures; higher is cheaper but less reactive [1 2 3 4 5 8 10 12 16 20]
#define RTX_MAX_CAPTURED_ENTITIES 96 // Maximum entities captured per update [16 32 64 96 128 192 256 384 512]
#define RTX_MAX_CAPTURED_PARTICLES 384 // Maximum particles captured per update [64 128 256 384 512 768 1024 1536 2048]
#define RTX_ENTITY_BLAS_CACHE 384 // Cached stable entity BLAS count [32 64 128 256 384 512 768 1024 1536 2048]

// Emission and local-light settings
#define EMISSIVE_SURFACE_INTENSITY 12.0 // Visible emitter surface intensity [4.0 6.0 8.0 10.0 12.0 16.0 20.0 24.0 32.0]
#define EMISSIVE_SURFACE_VISIBLE_INTENSITY 1.6 // Camera-visible emitter brightness [0.8 1.0 1.25 1.5 1.6 1.8 2.0 2.5 3.0]
#define SHROOMLIGHT_EMISSION 2.5 // Shroomlight emission multiplier [0.5 0.75 1.0 1.25 1.5 2.0 2.5 3.0 4.0]
#define SCULK_EMISSION 2.25 // Sculk emission multiplier [0.0 0.5 0.75 1.0 1.25 1.5 1.75 2.0 2.25 2.5 3.0]
#define REDSTONE_EMISSION 0.42 // Redstone torch surface intensity [0.15 0.25 0.35 0.42 0.5 0.65 0.8 1.0]
#define SHULKER_EMISSION 0.55 // Custom shulker-box glow intensity [0.0 0.2 0.35 0.55 0.75 1.0 1.35 1.75]
#define RT_BLOCKLIGHT_PROBES 0 // Experimental ray-query blocklight probes; 0 avoids alpha-cutout texture projection [0 1]
#define BLOCKLIGHT_SAMPLES 8 // Samples per pixel for RT blocklights [2 4 6 8 12 16]
#define BLOCKLIGHT_MAX_DISTANCE 64.0 // Maximum search distance for blocklight rays [16.0 24.0 32.0 48.0 64.0 96.0]
#define BLOCKLIGHT_EMISSION_SCALE 5.0 // Indirect emitter intensity [1.0 2.0 3.0 4.0 5.0 6.0 8.0 10.0]
#define BLOCKLIGHT_FALLOFF 0.0125 // Quadratic attenuation factor [0.01 0.0125 0.025 0.05 0.075 0.1]
#define BLOCKLIGHT_COLOR_R 1.0 // Smooth blocklight red channel [0.5 0.6 0.7 0.8 0.9 1.0]
#define BLOCKLIGHT_COLOR_G 0.82 // Smooth blocklight green channel [0.4 0.5 0.6 0.7 0.75 0.82 0.9 1.0]
#define BLOCKLIGHT_COLOR_B 0.55 // Smooth blocklight blue channel [0.25 0.35 0.45 0.55 0.65 0.75 0.9 1.0]

// Shadowed camera-space volumetrics
#define VOLUMETRICS_ENABLED 1 // [0 1]
#define VOLUMETRIC_SAMPLES 8 // Ray-march samples [4 6 8 12 16 24]
#define VOLUMETRIC_DENSITY 0.008 // Extinction density [0.0 0.002 0.004 0.006 0.008 0.012 0.016 0.024]
#define VOLUMETRIC_DISTANCE 96.0 // Maximum fog distance [32.0 48.0 64.0 96.0 128.0 192.0 256.0]
#define VOLUMETRIC_INTENSITY 0.65 // In-scattering intensity [0.0 0.25 0.4 0.5 0.65 0.8 1.0 1.25 1.5]
#define VOLUMETRIC_ANISOTROPY 0.65 // Forward scattering [0.0 0.25 0.4 0.5 0.65 0.75 0.85]
#define VOLUMETRIC_SHADOWS 1 // [0 1]

// Metal Radiance Reflection Settings
// Controls multi-bounce reflections for metals using GGX importance sampling
#define METAL_MAX_BOUNCES 3 // Maximum reflection bounces for metals [1 2 3 4 5]
#define METAL_RR_MIN_BOUNCE 1 // Minimum bounces before Russian Roulette starts [1 2]
#define METAL_REFLECTION_SCALE 1.0 // Scale factor for metal reflections [0.5 0.75 1.0 1.25 1.5]
#define METAL_GGX_IMPORTANCE 1 // Use GGX importance sampling for metal reflections [0 1]
#define REFLECTION_MAX_ROUGHNESS 0.62 // Dielectric reflection cutoff [0.35 0.45 0.55 0.62 0.7 0.8]
#define REFLECTION_RAY_DISTANCE 192.0 // Maximum reflection distance [64.0 96.0 128.0 192.0 256.0 384.0 512.0]
#define DIELECTRIC_REFLECTION_SCALE 0.65 // Non-metal environment reflection strength [0.25 0.4 0.5 0.65 0.8 1.0]
#define GOLD_MIN_ROUGHNESS 0.22 // Prevent vanilla gold from becoming a perfect mirror [0.08 0.12 0.16 0.22 0.28 0.35]
#define METAL_MIN_ROUGHNESS 0.28 // Vanilla iron/metal roughness floor [0.12 0.18 0.22 0.28 0.35 0.45]
#define COPPER_MIN_ROUGHNESS 0.24 // Vanilla copper roughness floor [0.1 0.16 0.2 0.24 0.3 0.4]
#define CRYSTAL_MIN_ROUGHNESS 0.14 // Crystal highlight roughness [0.06 0.1 0.14 0.18 0.24 0.32]

// Water, glass, sky, and foliage
#define WATER_WAVES 1 // [0 1]
#define WATER_WAVE_STRENGTH 0.12 // Surface normal wave strength [0.0 0.04 0.08 0.10 0.12 0.16 0.18 0.22]
#define WATER_WAVE_SCALE 0.0417 // Water wave frequency; 0.0417 mirrors Radiance's 1/24 world-space FFT normal scale [0.025 0.0417 0.055 0.075 0.1 0.14 0.2]
#define WATER_WAVE_SPEED 0.12 // Water animation speed [0.0 0.06 0.12 0.18 0.25 0.35 0.5 0.85]
#define WATER_MIN_ROUGHNESS 0.018 // Water reflection roughness [0.005 0.012 0.018 0.026 0.04 0.06 0.08 0.12]
#define WATER_CLARITY 0.86 // Water transmission clarity [0.25 0.4 0.55 0.72 0.86 0.95 1.0]
#define WATER_REFLECTION_STRENGTH 1.15 // Fresnel reflection strength [0.5 0.75 1.0 1.15 1.25 1.5]
#define WATER_ABSORPTION_STRENGTH 0.22 // Radiance-style water absorption scale [0.04 0.08 0.12 0.18 0.22 0.3 0.45]
#define WATER_TINT_R 0.02 // Deep water tint red channel [0.0 0.02 0.04 0.08 0.12 0.18]
#define WATER_TINT_G 0.48 // Deep water tint green channel [0.25 0.35 0.42 0.48 0.56 0.64]
#define WATER_TINT_B 0.65 // Deep water tint blue channel [0.4 0.5 0.58 0.65 0.72 0.82]
#define UNDERWATER_VISIBILITY 28.0 // Underwater view distance before strong absorption [10.0 16.0 22.0 28.0 36.0 48.0 64.0]
#define UNDERWATER_TINT_STRENGTH 0.62 // Underwater volume tint amount [0.0 0.25 0.45 0.62 0.75 0.9 1.0]
#define ICE_MIN_ROUGHNESS 0.032 // Ice reflection roughness [0.012 0.02 0.032 0.05 0.08 0.12]
#define ICE_TRANSMISSION 0.62 // Ice transmission strength [0.2 0.35 0.5 0.62 0.75 0.9]
#define ICE_FROST_STRENGTH 0.07 // Subtle frosted ice normal variation [0.0 0.025 0.05 0.07 0.1 0.14]
#define GLASS_MIN_ROUGHNESS 0.05 // Glass reflection roughness [0.03 0.05 0.08 0.12 0.18 0.25]
#define GLASS_TRANSMISSION 0.88 // Glass transmission strength [0.4 0.55 0.7 0.8 0.88 0.95 1.0]
#define CRYSTAL_TRANSMISSION 0.28 // Crystal internal light contribution [0.0 0.12 0.2 0.28 0.4 0.55]
#define FOLIAGE_WIND 1 // [0 1]
#define FOLIAGE_WIND_STRENGTH 0.08 // Shading-normal wind strength [0.0 0.03 0.05 0.08 0.12 0.16]
#define FOLIAGE_WIND_SPEED 1.0 // Foliage wind speed [0.0 0.35 0.6 1.0 1.4 2.0]
#define SKY_CLOUDS 1 // [0 1]
#define SKY_CLOUD_COVERAGE 0.56 // Cloud coverage [0.35 0.45 0.5 0.56 0.62 0.7 0.8]
#define SKY_CLOUD_SPEED 0.012 // Cloud motion speed [0.0 0.004 0.008 0.012 0.02 0.03]
#define SKY_STARS 1 // [0 1]
#define SUNSET_WARMTH 0.72 // Warm color at low sun angles [0.0 0.35 0.55 0.72 0.9 1.0]
#define SKY_RAYLEIGH_SCALE 0.9 // Blue-sky scattering strength [0.4 0.65 0.9 1.0 1.25 1.5]

#endif // SETTINGS_GLSL

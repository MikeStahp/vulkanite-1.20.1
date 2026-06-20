# Vulkanite Optimization Baseline

Snapshot date: 2026-06-20 (America/Chihuahua)

This directory defines the Part 0 capture contract. It does not contain a
completed performance baseline yet. Populate the CSV files with raw samples;
do not replace them with summaries.

## Build and machine identity

- Git commit/branch: `979a61c651379c56c5630c63f8c6c8598835c934` / `DLSSWORKING`
- OS: Windows 11 Pro 10.0.26200, build 26200
- CPU/RAM: Intel Core i9-11900K, 8 cores / 16 threads, 31.71 GiB
- Render GPU: NVIDIA GeForce RTX 3060 Ti, 8192 MiB
- NVIDIA driver: 591.86 (`32.0.15.9186` in WMI)
- Display reported by WMI: 1920 x 1080 at 60 Hz
- Standalone shell Java: Oracle Java 23.0.2
- Gradle daemon Java: Oracle JDK 17.0.12
- Game Java toolchain: Java 21
- Game JVM: `-Xmx7G`, `-Dorg.lwjgl.system.stackSize=8192`, and the six
  `--add-opens` entries in `build.gradle`
- Gradle 8.7; Fabric Loom 1.6.12
- Idle GPU observation: P8, 390 MHz graphics, 405 MHz memory, 42 C, 240 W
  power limit. This is not a loaded capture clock.

The initial `git status --short` contained 48 tracked changes and five
untracked implementation/planning files before this Part 0 pass. The exact
output is preserved in `initial-git-status.txt`. The paths span `build.gradle`,
`gradle.properties`, the active VulkaniteRT shader/settings, acceleration,
lighting, entity capture, render orchestration, command/resource lifetime
code, mixins, and the two deleted render resource managers. Treat all of that
as pre-existing work.

## Current configuration snapshot

The latest log confirms `VulkaniteRT`, Iris profile `Custom`, Vulkan RTX,
DLSS Ray Reconstruction, quality `QUALITY`, and ReSTIR disabled. It records a
1920 x 1008 output with a 1280 x 672 internal render size. The game is windowed
with VSync off, a 260 FPS cap, 10-chunk render distance, 9-chunk simulation
distance, entity distance 1.0, all particles, and no debug view. Part 0 raised
the runtime cap from 60 to 260 before accepted performance captures. Vulkanite's
separate command-manager pacer is code-verified disabled by default and has no
caller in the current tree.

The active `run/shaderpacks/VulkaniteRT.txt` overrides are:

```properties
BLOCKLIGHT_FALLOFF=0.1
EMISSIVE_SURFACE_INTENSITY=24.0
METAL_MAX_BOUNCES=4
MIN_LIGHTING=0.02
REFLECTION_RAY_DISTANCE=384.0
RESTIR_SPATIAL_SAMPLES=8
RTX_ENTITY_BLAS_CACHE=256
SCULK_EMISSION=0.75
SECTION_LIGHT_SPARSE_RT_CORRECTION=1
SHULKER_EMISSION=1.35
SKY_CLOUDS=0
SUN_INTENSITY=2.5
WATER_WAVE_SPEED=0.0
```

The remaining RT options use the values in the tracked `settings.glsl` and
`shaders.properties` identified below. Active DLSS properties are DLSS RR
enabled, FSR disabled, quality `QUALITY`, sharpness 0.5, jitter disabled,
motion-vector override disabled, and ReSTIR disabled.
`run/vulkanite/dlss_config.json` contains different experimental values; the
latest log agrees with `run/config/vulkanite-dlss.properties`, so the JSON is
recorded but not treated as the active source.

| File | SHA-256 |
|---|---|
| `run/options.txt` | `AAC0277A4A0E380618085C4F047DB3B58AF9627057832A972B7FFEBB2AF15C7A` |
| `run/config/iris.properties` | `7F89E535ECA6F40C3384F37C327563C597870951FA733A20E8360259CB390836` |
| `run/config/sodium-options.json` | `C95C71EC7AA61E9FCC3626DB6D2EDC3A8EEF56484E764BED3727144996DF76F1` |
| `run/config/vulkanite-dlss.properties` | `AA3AE97654C8516E74D6AEFB1D1F93E41C5E60847EE4F3CE0EBBD29A6894F740` |
| `run/vulkanite/dlss_config.json` | `22EC7C87029CE2E4A7B802D9540D5BA66BB993A0F3C12B1AC5BE9DB040BEA67F` |
| `run/shaderpacks/VulkaniteRT.txt` | `5878293592B2084C3E3FE313A6D6EF07517A083B8C354081D79C0F5293E3FE58` |
| `shaderpacks/VulkaniteRT/shaders/lib/rt/settings.glsl` | `EEFEED22722F37551F339553B01765854EF90563FC1EC722C544AE8B8CA31AEC` |
| `shaderpacks/VulkaniteRT/shaders/shaders.properties` | `23B337F6E9AEDCB6758717543B793EBBE40D389FD627F51958BBD05C941CD981` |

Before every capture, copy the effective configuration files and
`nvidia-smi` output into that capture's artifact directory. Reject the run if
these hashes differ without an intentional manifest change.

The machine has PresentMon 1.7.12119.0 through NVIDIA FrameView and RenderDoc
installed. The `FvSvc` service is protected and was stopped during discovery;
the capture script therefore requires an elevated PowerShell, starts the
service when necessary, and rejects PresentMon's otherwise-silent empty output.

## Capture protocol

Use the existing `ray-tracing-test-place` world. Record dimension, XYZ, yaw,
pitch, time, and weather for each scene before the first accepted run. Those
coordinates are not yet recorded, so the scene matrix is not complete.

Steady-state scene IDs:

1. `static-exterior`: fixed camera, sun, and open terrain.
2. `camera-motion`: identical scripted path and duration.
3. `emitter-interior`: enclosed room with multiple emissive block types.
4. `materials`: water, glass, metal, and emissive surfaces in frame.
5. `volumetrics`: long view with visible fog and sun shadowing.
6. `entities`: bounded animated entities and particles.
7. `chunk-traversal`: automated fast-flight route into uncached chunks.
8. `stress-entities`: dense cluster of 32 animated entities with various armor/equipment.
9. `volumetric-room`: enclosed room with powder snow and campfires to stress volumetric scattering.

Lifecycle cases:

1. `startup-cold`: stop Gradle daemons, launch, and measure process start to
   main menu and first stable rendered world frame.
2. `startup-warm`: repeat without clearing caches; do not mix with cold runs.
3. `shader-reload`: invoke Iris reload once in `static-exterior`; measure input
   to first stable frame and retain the compile log.
4. `resize`: resize from 1920 x 1008 client output to 1280 x 720 and back;
   measure each direction separately.
5. `world-switch`: leave `ray-tracing-test-place`, enter a second fixed world,
   then return; record each transition separately.
6. `shutdown-idle`: quit from a warmed static scene.
7. `shutdown-busy`: quit during `chunk-traversal` with BLAS work pending.

For every steady-state run:

- Use the same declared machine preconditioning procedure.
- Disable VSync and use an FPS cap high enough not to bind the measurement.
- Keep validation, overlays, recording tools, power mode, and background
  applications identical and record whether each is active.
- Warm the scene for 60 seconds, then capture at least 120 consecutive frames.
- Collect three independent runs per scene, returning to the exact camera start.
- Save raw per-frame rows in `frame_samples.csv`; save lifecycle repetitions in
  `lifecycle_samples.csv`.
- Record loaded GPU clocks, temperature, power, utilization, VRAM, JVM heap,
  native memory when available, and any thermal/power-limit event.
- Report median, P95, and P99 for CPU frame, GPU frame, RT, reconstruction,
  queue wait, and relevant spike counters. Report run-to-run spread (CoV).
- **Reject outlier captures**: if the capture script detects >5% of frames as IQR outliers or thermal throttling, investigate background interference.

From an elevated PowerShell, you can run the automated multi-scene orchestrator:

```powershell
./scripts/optimization/Capture-VulkaniteAllScenes.ps1 -Tag baseline -RunsPerScene 3
```

Or run a specific scene manually:

```powershell
./scripts/optimization/Capture-VulkaniteBaseline.ps1 `
  -Scene static-exterior -Run 1 -DurationSeconds 15 -Tag baseline `
  -Dimension minecraft:overworld -X 274.34317 -Y -56 -Z -106.14960 `
  -Yaw 83.47290 -Pitch 9.75007 -WorldTime 13000 -Weather clear

./scripts/optimization/Summarize-VulkaniteBaseline.ps1
```

The saved local-player state in `level.dat` provides that first candidate
camera. It is a reproducible coordinate, not yet a verified scene label.

## Acceptance thresholds

- Retain a steady-state change only if its full-frame median gain exceeds
  `max(3%, baseline run-to-run spread)` and no P95/P99 target regresses by more
  than `max(2%, baseline run-to-run spread)`.
- For stutter, the targeted P95/P99 must improve by
  `max(5%, baseline run-to-run spread)` without moving the spike elsewhere.
- For lifecycle latency or memory, require at least a 5% improvement unless the
  change fixes a reproduced hang, leak, or lifetime error.
- Deterministic guide/debug images must have no NaN/Inf and no unexpected
  nonzero pixel difference. For stochastic final images, require SSIM >= 0.99
  and mean absolute linear-RGB error <= 1% against the warmed reference over at
  least 16 aligned frames.
- Camera motion, disocclusion, reload, resize, world switch, and camera cut must
  show no new persistent trail, flicker, light leak, missing geometry, or stale
  history. A visible structural regression rejects the change even if image
  metrics pass.

The first complete baseline may tighten these thresholds after noise is known;
it must not loosen them after an optimization result is seen.

Use the comparison script to automatically test these thresholds with Welch's t-test:

```powershell
./scripts/optimization/Compare-VulkaniteOptimization.ps1 `
  -BaselineRoot run/vulkanite-baselines/baseline `
  -OptimizationRoot run/vulkanite-baselines/optimization
```

## Remaining Part 0 work

- Record coordinates/state for all seven scenes.
- Capture three runs of at least 120 warmed frames for every scene.
- Capture all lifecycle cases at least three times.
- Add loaded clock/power/thermal data, memory data, reference images, guide
  images, and known pre-existing visual artifacts.
- Compute summaries and run-to-run spread without deleting raw rows.

# Vulkanite Optimization Lab

The reproducible benchmark lab is implemented as the tracked datapack in
`tools/vulkanite-benchmark-datapack` and installed into the existing
`ray-tracing-test-place` development world. It builds once, guarded by the
`vulkanite_benchmark:state` command storage value.

The lab freezes time and weather, disables random ticks and natural mob
spawning, constructs paired exterior/interior light tests, provides material
and entity scenes, and adds a runway extending across 24 chunks. Run these
functions from chat to select exact cameras:

```text
/function vulkanite_benchmark:camera/outdoor
/function vulkanite_benchmark:camera/indoor_lit
/function vulkanite_benchmark:camera/indoor_dark
/function vulkanite_benchmark:camera/materials
/function vulkanite_benchmark:camera/volumetrics
/function vulkanite_benchmark:camera/entities
/function vulkanite_benchmark:camera/stress_entities
/function vulkanite_benchmark:camera/volumetric_room
/function vulkanite_benchmark:camera/traversal_start
```

Camera pads are yellow, lime, red, blue, magenta, cyan, orange (stress entities), and purple (volumetric room) respectively. The
outdoor, indoor-lit, and indoor-dark cameras are deliberately paired so that
light leaks and missing occlusion are easy to spot. To recreate damaged lab
geometry, run:

```text
/function vulkanite_benchmark:rebuild
```

Install while Minecraft is closed:

```powershell
./scripts/optimization/Install-VulkaniteBenchmarkWorld.ps1
./gradlew runClient --args="--quickPlaySingleplayer ray-tracing-test-place"
```

## Running an Automated A/B Test

The easiest way to capture all scenes is with the orchestrator, which will prompt you to run each camera command in-game:

```powershell
./scripts/optimization/Capture-VulkaniteAllScenes.ps1 -Tag baseline -RunsPerScene 3
```

After implementing an optimization, capture again:

```powershell
./scripts/optimization/Capture-VulkaniteAllScenes.ps1 -Tag optimization -RunsPerScene 3
```

Then compare the two:

```powershell
./scripts/optimization/Compare-VulkaniteOptimization.ps1 -BaselineRoot run/vulkanite-baselines/baseline -OptimizationRoot run/vulkanite-baselines/optimization
```

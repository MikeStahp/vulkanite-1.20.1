## 2024-05-18 - Replacing streams in hot paths
**Learning:** Found several hot paths in `MixinIrisRenderingPipeline`, `VulkanPipeline`, `VCmdBuff`, and `CommandManager` using Java Streams, which cause allocations, boxing, and generic closures overhead on every frame, reducing rendering performance and causing GC pressure.
**Action:** Replaced stream operations with standard loops and explicitly pre-allocated arrays and Lists using known sizes. Used `Arrays.fill` for constant population.

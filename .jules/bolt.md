## 2024-05-14 - Removed Stream Overhead in Hot Rendering Paths
**Learning:** In a heavily performance-critical path like Vulkanite's per-frame rendering loop, Java 8 Streams introduce non-negligible allocation overhead and GC pressure. Converting these `.stream()` maps to standard array pre-allocations with enhanced/traditional `for` loops eliminates object creations, avoiding frame-time spikes.
**Action:** Default to standard iteration loops with pre-allocated size structures instead of functional Streams in loops like `VulkanPipeline`, `CommandManager`, and `MixinIrisRenderingPipeline`.

## 2024-05-24 - Stream Optimizations
**Learning:** In hot rendering paths like `VCmdBuff.bindDSet`, `VulkanPipeline.renderPostShadows`, and `MixinIrisRenderingPipeline.getCustomTextures`, avoid using Java Streams (`.stream().mapToInt()`, `.stream().mapToLong().max()`, `Arrays.stream()`). They cause unnecessary boxing, O(N) per-frame allocations, and increase GC pressure.
**Action:** Use simple loops and basic arrays/lists with pre-allocated sizes (e.g., `new ArrayList<>(source.size())` or `new int[source.size()]`) to eliminate dynamic resizing and stream overhead.

## 2024-03-24 - Avoid Streams in Hot Paths
**Learning:** In hot rendering paths like `VulkanPipeline.renderPostShadows`, `VCmdBuff.bindDSet`, avoid using Java Streams. Use simple loops and basic arrays/lists instead to eliminate unnecessary boxing, O(N) per-frame allocations, and reduce GC pressure.
**Action:** Replace `stream()` with standard loops where they are executed frequently (per frame).

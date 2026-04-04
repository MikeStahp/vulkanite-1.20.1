## 2025-01-20 - Java Streams in Hot Rendering Paths
**Learning:** Using Java Streams (`.stream().mapToInt()`, `.stream().mapToLong().max()`, `Arrays.stream()`) in hot paths like `VulkanPipeline` and `VCmdBuff` introduces significant per-frame overhead and GC pressure. When optimizing, combining multiple stream passes over the same collection into a single loop yields additional performance benefits.
**Action:** Always prefer pre-sized collections and standard iterative `for` loops in performance-critical sections of the Vulkan renderer to avoid boxing and iterator allocations.

## 2024-05-24 - Avoid Java Streams in hot rendering paths
**Learning:** Using Java Streams (e.g., `.stream().mapToInt()`) in hot rendering paths like `VulkanPipeline.renderPostShadows` creates O(N) object allocations per frame. This significantly increases Garbage Collection (GC) pressure, which causes frame drops or stutters in a real-time rendering application like a Minecraft mod.
**Action:** Replace stream operations with simple `for` loops and basic arrays/lists in code executed per-frame to eliminate unnecessary object allocation and minimize GC overhead.

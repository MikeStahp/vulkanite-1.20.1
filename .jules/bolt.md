## 2024-05-24 - Avoid Streams in Hot Paths
**Learning:** The `VulkanPipeline.renderPostShadows` method is a hot path executed every frame. Using Java Streams (e.g., `.stream().mapToInt()`) inside this loop causes significant memory allocations and GC overhead due to the creation of Stream instances, lambdas, and boxing/unboxing overhead.
**Action:** Replace stream operations with basic loops and simple arrays in frequently executed paths to eliminate per-frame allocations and reduce GC pressure.

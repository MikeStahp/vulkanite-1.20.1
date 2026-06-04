## 2024-06-04 - Stream allocations in hot rendering paths
**Learning:** Java Streams (e.g., `.stream().mapToLong().toArray()`) in per-frame rendering hot paths (like `VulkanPipeline`, `VCmdBuff`, `CommandManager`) cause severe garbage collection pressure due to object allocation and boxing overhead.
**Action:** Replace streams with traditional loops and pre-allocated arrays/collections to eliminate GC overhead in performance-critical rendering code.

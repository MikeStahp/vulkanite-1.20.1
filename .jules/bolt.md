## 2024-05-18 - Avoid Stream API in Hot Paths
**Learning:** In performance-critical rendering paths (like `VulkanPipeline`, `VCmdBuff`, and `CommandManager`), the use of Java Streams creates per-frame object allocations (lambdas, iterators, spliterators) that cause high GC pressure and micro-stutters.
**Action:** Replace streams with traditional `for` loops, pre-allocated arrays, `Arrays.fill`, and `.ensureCapacity()` when dealing with lists to eliminate O(N) allocations and maintain zero-allocation frame loops.

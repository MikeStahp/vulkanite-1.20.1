## 2024-05-24 - [Avoid Java Streams in Hot Rendering Paths]
**Learning:** Java Streams (e.g., `.stream().mapToLong()`, `.toArray()`) create unnecessary object allocations (iterators, closures, intermediary lists) per frame, causing significant GC pressure and unboxing overhead in hot rendering loops like `VCmdBuff.bindDSet`.
**Action:** Always prefer explicit `for` loops and pre-allocated arrays in performance-critical execution paths to eliminate per-frame GC allocations and reduce GC pressure.

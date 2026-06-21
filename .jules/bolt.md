## 2024-05-24 - Stream Optimizations in Hot Paths
**Learning:** Found multiple usages of Java Streams (`.stream().mapToInt()`, `.stream().mapToLong()`, `.stream().map()`) in hot rendering paths (like `renderPostShadows`, `bindDSet`, `waitForExecutions`). These incur significant unboxing overhead and per-frame GC allocations (iterators, wrappers).
**Action:** Always replace Java Streams with pre-allocated raw arrays (`int[]`, `long[]`) or properly sized `ArrayList`s paired with indexed `for` loops in rendering loops to eliminate these allocations.

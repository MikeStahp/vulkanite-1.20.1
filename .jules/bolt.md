## 2025-02-12 - Explicit loop vs stream performance improvement
**Learning:** In hot rendering paths of Vulkanite, using Java streams introduces significant overhead due to per-frame garbage collection and unboxing.
**Action:** Replace stream-based collection parsing and modification like `.stream().mapToInt(...)` or `.stream().mapToLong(Long::longValue).max().orElse(...)` with explicit pre-allocated arrays and indexed/enhanced `for` loops.

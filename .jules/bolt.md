## 2024-05-18 - Avoid Streams in Hot Paths
**Learning:** Java Streams (e.g., `.stream().mapToInt()`, `.toList()`) in hot rendering paths (like per-frame descriptor binding, rendering loops, or command buffer submission) create significant iterator, lambda, and boxing overhead resulting in GC pressure and stutters.
**Action:** Replace stream operations with traditional `for` loops and pre-allocated arrays/collections (e.g., `new long[sets.size()]` or `new ArrayList<>(sets.size())`) in all performance-critical Vulkan/rendering paths.

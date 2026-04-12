## 2026-04-12 - Eliminate Java Streams in Hot Rendering Paths
**Learning:** Java Stream API methods like `.stream().mapToInt().toArray()`, `.map().toList()`, and `.forEach()` create significant object allocation overhead (streams, lambdas, iterators) which causes noticeable GC pressure and CPU overhead when used in per-frame rendering hot paths.
**Action:** Replace Stream API usages with standard `for` loops and pre-sized arrays/collections in high-frequency methods (e.g., render loop code and Vulkan command buffer construction) to maintain high performance.

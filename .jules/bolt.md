
## $(date +%Y-%m-%d) - Replace Java Stream APIs with traditional loops in hot render paths
**Learning:** In highly execution-sensitive paths like rendering pipelines (e.g. `VulkanPipeline.renderPostShadows`), Java Stream API usage such as `.stream().mapToInt(...).toArray()` or `.stream().map(...).toList()` introduces significant overhead and GC pressure per frame due to lambda instantiation and boxing.
**Action:** When working on performance enhancements in similar paths, refactor stream operations into traditional `for` loops using pre-sized arrays or `ArrayList` to minimize object allocations. Note that replacing `toList()` with `ArrayList` will yield a modifiable list, which may have different semantics but is often acceptable in localized scope.

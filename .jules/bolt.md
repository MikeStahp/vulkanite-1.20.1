## 2026-04-06 - Eliminate Java Streams in hot rendering paths
**Learning:** In hot rendering paths like `VulkanPipeline.renderPostShadows`, `VCmdBuff.bindDSet`, and `CommandManager.Queue.waitForExecutions`, Java Streams (`.stream().mapToInt()`, `.stream().max().orElse()`, `.toList()`) cause significant per-frame object allocation (boxing, iterators, closures) and GC pressure, which degrade performance.
**Action:** Always prefer standard Java loops (, enhanced ) and direct array or  allocations with pre-computed sizes over Streams in hot paths to avoid boxing and iterator overhead.
## 2025-04-06 - Eliminate Java Streams in hot rendering paths
**Learning:** In hot rendering paths like `VulkanPipeline.renderPostShadows`, `VCmdBuff.bindDSet`, and `CommandManager.Queue.waitForExecutions`, Java Streams (`.stream().mapToInt()`, `.stream().max().orElse()`, `.toList()`) cause significant per-frame object allocation (boxing, iterators, closures) and GC pressure, which degrade performance.
**Action:** Always prefer standard Java loops (`for`, enhanced `for`) and direct array or `ArrayList` allocations with pre-computed sizes over Streams in hot paths to avoid boxing and iterator overhead.

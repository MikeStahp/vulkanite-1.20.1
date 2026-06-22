## 2024-05-20 - [Performance] Removing Streams in hot paths
**Learning:** Java Streams (e.g. `.stream().mapToInt()`, `.stream().mapToLong()`) introduce boxing overhead, object allocations (Iterator, Stream pipeline objects), and per-frame GC pressure which is particularly harmful in rendering/command buffer hot paths like `VCmdBuff.bindDSet` or `VulkanPipeline.renderPostShadows`.
**Action:** Replace these Streams with explicit `for` loops or pre-sized arrays/collections to eliminate GC allocations during rendering loops.

## 2024-03-24 - Stream API Overhead in Render Loop
**Learning:** Java 8 Streams create significant allocation overhead (pipelines, lambdas, boxed primitive iterators) which directly impacts GC and CPU performance when used in hot, per-frame Vulkan execution paths like `VulkanPipeline.renderPostShadows`, `CommandManager.Queue.waitForExecutions`, and `VCmdBuff.bindDSet`.
**Action:** Always replace `.stream()` chains with traditional `for` loops and pre-allocated arrays in per-frame rendering code to maintain zero-allocation performance patterns.

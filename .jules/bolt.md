## 2024-05-18 - Avoid Java Streams in hot paths
**Learning:** Java Streams (e.g., `stream().mapToInt()`, `stream().max()`) create unacceptable per-frame object allocation overhead in hot rendering paths (like `VulkanPipeline.renderPostShadows`, `VCmdBuff.bindDSet`, `CommandManager.Queue.waitForExecutions`), increasing GC pressure.
**Action:** Replace all Stream API usages in high-frequency rendering code with standard `for` loops and pre-sized arrays/lists to ensure zero-allocation or minimal-allocation iteration.

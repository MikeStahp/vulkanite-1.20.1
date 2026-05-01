## 2024-05-15 - Stream overhead in hot rendering paths
**Learning:** Vulkanite's hot rendering paths like `VulkanPipeline.renderPostShadows`, `VCmdBuff.bindDSet`, and `CommandManager.Queue.waitForExecutions` are allocating excessive garbage by using Java Streams (e.g., `stream().mapToInt()`, `stream().mapToLong().max()`, `stream().map().toList()`). Since these are called per-frame, this adds significant GC pressure and overhead.
**Action:** Replace `stream()` operations with traditional `for` loops and pre-sized arrays/lists in these critical paths.

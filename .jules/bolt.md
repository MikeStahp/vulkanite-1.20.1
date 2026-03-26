## 2024-05-24 - [Avoid Java Streams in Hot Rendering Paths]
**Learning:** Java Streams (e.g., `stream().map()`, `Arrays.stream()`) in per-frame rendering loops (like `VulkanPipeline.renderPostShadows`, `VCmdBuff.bindDSet`, `CommandManager.Queue.waitForExecutions`) create significant GC pressure and unnecessary object allocations (Stream, Spliterator, boxing).
**Action:** Replace all Stream usage in hot paths with traditional `for` loops and pre-allocated arrays to eliminate per-frame garbage generation and improve frame time consistency.

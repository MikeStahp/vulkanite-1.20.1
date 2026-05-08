## 2024-05-08 - [Avoid stream operations in hot loops]
**Learning:** In hot rendering paths like `VulkanPipeline.renderPostShadows`, `VCmdBuff.bindDSet`, `CommandManager.Queue.waitForExecutions`, and `MixinIrisRenderingPipeline.getCustomTextures`, the use of Java Streams `.stream().mapToInt()`, `.stream().mapToLong().max()`, `Arrays.stream()` allocations per frame adds GC pressure and object allocation overhead (boxing).
**Action:** Replace Java streams with simple arrays/loops to reduce unnecessary allocations per frame.

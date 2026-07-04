## 2024-05-24 - Stream allocations in hot paths
**Learning:** In a Vulkan rendering context, avoiding Stream allocations and lambda captures per-frame is a critical and highly effective micro-optimization that prevents GC stutter.
**Action:** Replace `stream().mapToInt(...)` and `stream().map(...)` operations with explicit `for` loops, pre-sized `ArrayList`s, and `Arrays.fill()` in hot paths like `VCmdBuff.bindDSet`, `VulkanPipeline.renderPostShadows`, and `CommandManager.Queue.waitForExecutions`. Make sure to avoid lambda captures or iterator allocations inside rendering loops.

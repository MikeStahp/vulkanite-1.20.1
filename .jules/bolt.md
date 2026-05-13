
## 2024-05-18 - [Eliminate Java Streams in Hot Paths]
**Learning:** This codebase relies on extremely low-level OpenGL/Vulkan interop with high-frequency rendering loops. Using Java Streams (e.g., `.stream().mapToInt(...)`, `.max().orElse(...)`) in methods like `VCmdBuff.bindDSet`, `VulkanPipeline.renderPostShadows` or `CommandManager.Queue.waitForExecutions` incurs non-trivial allocation/boxing overhead and places significant pressure on the Garbage Collector during hot frame times.
**Action:** Always prefer explicit `for` loops, pre-allocated sizing (e.g., `new ArrayList<>(size)`, `new long[size]`), and functions like `Arrays.fill` instead of Java Streams for collections inside the render loop or command buffer generation paths.

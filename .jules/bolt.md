## 2024-04-23 - Java Stream Refactoring in Hot Paths
**Learning:** In Minecraft rendering logic (like Vulkanite's `renderPostShadows`), the per-frame allocations from Java Streams (`.stream().mapToInt()`, `.toList()`) cause micro-stutters. Replacing them with explicit `for` loops and array/list pre-allocations (using `size()`) effectively mitigates this.
**Action:** Always scan rendering and command buffer recording hot paths for stream usage, and proactively convert them to direct loops, explicitly pre-allocating the resulting collections using the input's size.

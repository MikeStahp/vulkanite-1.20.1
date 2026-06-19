## 2024-10-24 - Stream allocations in Rendering Hot Paths
**Learning:** Even simple stream mappings (like `stream().mapToInt().toArray()`) cause significant per-frame memory allocation and unboxing overhead in critical rendering paths (e.g., VulkanPipeline.java, VCmdBuff.java), potentially leading to GC frame stutters.
**Action:** Always replace Java Streams with explicit pre-sized arrays and standard for-loops in any rendering loops (e.g. `renderPostShadows`, `bindDSet`).

## 2024-05-24 - Stream allocations in hot paths
**Learning:** Java Streams introduce excessive per-frame allocations (stream instances, iterators, boxing) in critical rendering hot paths (VulkanPipeline, VCmdBuff, CommandManager).
**Action:** Replaced stream chains with simple iterative loops to eliminate per-frame allocations and reduce GC pressure without affecting logic.

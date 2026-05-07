## 2023-11-20 - Hot Path GC Pressure via Streams
**Learning:** Hot rendering paths in `VulkanPipeline`, `VCmdBuff`, and `CommandManager` were allocating significant garbage per-frame using Java Streams (e.g. `.stream().map(...)`, `.stream().mapToInt(...)`, `.max()`). This caused GC spikes.
**Action:** Replaced stream usages with basic arrays and simple loops. When pre-allocating an array for variable length data is needed and only `List` length is known, use simple index loops or `ArrayList<?>` pattern matching for `ensureCapacity()`.

## 2024-05-24 - Hot path Stream usages
**Learning:** Hot rendering paths in Vulkanite (e.g., `VCmdBuff`, `CommandManager`) still contain `java.util.stream.Stream` usage which allocates iterator objects per-frame and causes GC pressure.
**Action:** Replace `Stream` with simple loops or arrays where sizes are known.

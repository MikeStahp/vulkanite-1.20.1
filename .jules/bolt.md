## 2024-05-23 - Memory vs Reality Mismatch
**Learning:** The memory stated that `VObject` exposes `getRefCount()`, but the actual code did not have it. Memory can be outdated or refer to intended/future states.
**Action:** Always verify memory claims against the actual codebase (`read_file`) before writing code that depends on them.

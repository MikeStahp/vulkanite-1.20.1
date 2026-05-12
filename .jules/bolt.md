## 2024-06-25 - Entity Rendering Loop Allocations
**Learning:** In Minecraft rendering loops (like `EntityCapture.VertexCaptureProvider`), the use of lambda-based APIs like `Map.compute` and `Map.forEach` in per-frame code causes significant hidden object allocation (capturing lambdas) and GC overhead.
**Action:** Always replace `compute` and `forEach` with traditional `get/put` and enhanced `for` loops in hot rendering paths.

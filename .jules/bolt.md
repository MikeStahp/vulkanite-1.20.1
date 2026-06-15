## 2024-05-24 - Avoid Map.compute lambda allocation in hot rendering paths
**Learning:** Using `Map.compute` in extremely hot paths (like `VertexCaptureProvider.getBuffer`, which is called for many vertices/components per entity per frame) causes per-frame closure object allocations. This creates unnecessary garbage and GC pressure.
**Action:** Replace `Map.compute` with direct `Map.get()` and `Map.put()` checks to completely avoid capturing lambda allocations. Additionally, replace `Map.forEach` with a standard `for` loop over `entrySet()` to avoid further closure allocations.

## 2024-05-24 - Cached Descriptor Sets
**Learning:** Vulkanite allocates descriptor sets dynamically per frame for Ray tracing passes (in `RenderPassExecutor.java`), doing per-frame object allocations and `vkAllocateDescriptorSets` calls. A memory snippet previously suggested `Vulkanite` maintains an `emptyDescriptorSets` cache, but this does not currently exist in the code base.
**Action:** Adding an `emptyDescriptorSets` map in `Vulkanite` and a `getEmptySet(layout)` method that caches a single empty descriptor set for missing layouts will prevent per-frame allocations.

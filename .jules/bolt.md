## 2026-04-03 - Cached and reused empty descriptor sets
**Learning:** To reduce per-frame object allocations and `vkAllocateDescriptorSets` calls in hot rendering paths like `VulkanPipeline.renderPostShadows`, we should reuse cached 'empty' descriptor sets via `Vulkanite.INSTANCE.getEmptySet(layout)`.
**Action:** Next time I optimize rendering hot paths, I will look for repeated resource allocations like `VDescriptorSet` instances and cache them to avoid unnecessary `vkAllocateDescriptorSets` per-frame overhead.

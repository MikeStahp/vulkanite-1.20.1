## 2024-05-24 - Descriptor Set Optimization
**Learning:** Vulkanite descriptor set caching was hinted at in memory but the `getEmptySet` method does not exist yet. `emptyDescriptorSets` and `getEmptySet` needs to be implemented.
**Action:** Implement `emptyDescriptorSets` cache in `Vulkanite` to reuse descriptor sets instead of allocating them every frame.

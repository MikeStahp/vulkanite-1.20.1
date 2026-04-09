## 2024-05-24 - Empty Descriptor Set Reuse
**Learning:** In hot rendering paths, empty descriptor sets (sets with no applied updates) were being allocated on every frame, generating garbage and costly Vulkan API calls (`vkAllocateDescriptorSets`).
**Action:** Implemented an `emptyDescriptorSets` cache in `Vulkanite` to reuse empty sets, dramatically reducing allocations.

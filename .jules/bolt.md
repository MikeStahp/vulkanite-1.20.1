## 2024-06-25 - [Replacing Streams with Loops in Hot Paths]
**Learning:** In highly active rendering loops like `VulkanPipeline` and `VCmdBuff`, using standard Java Streams (`.stream().map(...).toList()`) generates significant garbage (allocating Iterators, Lambda wrappers, and temporary Stream objects), causing micro-stutters.
**Action:** Replace `Arrays.stream()` and `Collection.stream()` usage in performance-critical areas with basic `for` loops and explicitly pre-allocated arrays/collections based on known sizes.

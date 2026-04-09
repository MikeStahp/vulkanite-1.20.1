1.  **Modify `Vulkanite` to cache empty descriptor sets:**
    *   Add a map to cache empty descriptor sets: `private final HashMap<VDescriptorSetLayout, VRef<VDescriptorSet>> emptyDescriptorSets = new HashMap<>();`
    *   Add a method `getEmptySet(VRef<VDescriptorSetLayout> layout)` that returns an empty descriptor set from the cache, allocating one if it doesn't exist yet. Make sure it adds a reference using `.addRef()` when returning it.
    *   Update `removePoolByLayout(VDescriptorSetLayout layout)` to also remove and close the cached empty set for that layout:
        ```java
        public void removePoolByLayout(VDescriptorSetLayout layout) {
            synchronized (descriptorPools) {
                descriptorPools.remove(layout);
            }
            synchronized (emptyDescriptorSets) {
                var set = emptyDescriptorSets.remove(layout);
                if (set != null) {
                    set.close();
                }
            }
        }
        ```
    *   Update `destroy()` to also close and clear the cached empty descriptor sets:
        ```java
        public void destroy() {
            vkDeviceWaitIdle(ctx.device);
            descriptorPools.clear();
            synchronized (emptyDescriptorSets) {
                for (var set : emptyDescriptorSets.values()) {
                    set.close();
                }
                emptyDescriptorSets.clear();
            }
        }
        ```

2.  **Modify `RenderPassExecutor` to use the cached empty descriptor sets:**
    *   In the `execute` method, instead of calling `Vulkanite.INSTANCE.getPoolByLayout(layouts.get(i)).get().allocateSet()` to fill gaps, call `Vulkanite.INSTANCE.getEmptySet(layouts.get(i))` to reuse the cached empty sets. This avoids allocating a new empty descriptor set on every frame for gaps in the descriptor set layout.

3.  **Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.**
4.  **Submit the pull request with appropriate title and description.**

## 2026-02-28 - Exponential Growth Strategy in Shared Buffer
**Learning:** Hardcoded reallocations in static, globally shared Vulkan buffers (like `SharedQuadVkIndexBuffer`) lead to frequent GPU buffer resizing under variable loads.
**Action:** Implement exponential capacity growth (e.g., `1.5x + base\_padding`) for dynamically sized Vulkan buffers to prevent reallocation bottlenecks, and explicitly close old buffers to prevent memory leaks.

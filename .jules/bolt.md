## 2024-05-18 - Missing Early Return in EntityCapture
**Learning:** `EntityCapture.capture` iterates over all entities in the world but could return null/empty before performing expensive rendering setup if the world has no entities or is uninitialized.
**Action:** Always check if the collection is empty before setting up rendering state like `MatrixStack` and invoking complex logic.

## 2024-10-25 - Avoid forEach with Lambdas in Hot Loops
**Learning:** Using `.forEach` with capturing lambdas (e.g., `builderMap.forEach((layer, buffer) -> { ... })` where external variables like lists are captured) or method references (e.g., `VRef::close`) allocates memory per invocation, which increases GC pressure in hot rendering paths.
**Action:** Always prefer enhanced `for` loops (e.g., `for (var entry : map.entrySet())` or `for (var item : list)`) over `.forEach` in performance-critical code segments like `EntityCapture` and command buffer clearing.

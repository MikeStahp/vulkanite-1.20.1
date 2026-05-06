## 2026-05-06 - Replaced Hot-Path Streams with Standard Loops
**Learning:** In performance-critical components (like CommandManager and VCmdBuff), Java streams (`.stream().mapToInt()`, `.max().orElse()`, etc.) create hidden object allocations, causing unnecessary GC pressure and, in some cases, logic bugs (e.g., ignoring default max values).
**Action:** When working in rendering engines or hot paths, manually translate stream operations into traditional `for` loops with pre-allocated array capacities to maintain (N)$ efficiency without allocation overhead.

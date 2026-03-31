## 2024-05-24 - Timeline Semaphore Maximums
**Learning:** When calculating timeline semaphore maximums (e.g., in CommandManager.Queue.waitForExecutions), ensure the new value is strictly monotonically increasing. Previous stream-based implementations (like `max().orElse()`) contained bugs that could incorrectly decrease the wait value if the incoming executions max was lower than the current wait.
**Action:** Always use a simple loop tracking `Long.max(currentMax, newValue)` to guarantee the maximum wait is never lowered.

## 2024-05-24 - Stream to Loop Refactoring Mutability
**Learning:** When refactoring Java Streams (e.g., `.toList()`) to traditional loops in performance-critical paths, replacing `toList()` with `new ArrayList<>()` yields a modifiable list. This difference in mutability semantics is usually acceptable in localized scopes but should be considered.
**Action:** Be mindful of returning modifiable collections when the caller expects an immutable one. If necessary, wrap with `Collections.unmodifiableList()`, but prefer avoiding it in hot loops to save allocations.

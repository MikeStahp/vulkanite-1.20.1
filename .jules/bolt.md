## 2024-05-24 - Timeline Semaphore Decrement Bug
**Learning:** Calculating timeline semaphore maximums using `executions.stream().mapToLong(Long::longValue).max().orElse(waitingFor.getOrDefault(execQueue, 0))` contains a subtle bug: if `executions` is not empty but its max value is *lower* than the current `waitingFor` value, it can incorrectly decrease the wait value, breaking the strict monotonic increase requirement of timeline semaphores.
**Action:** Always include the current waiting value inside the maximum calculation loop to strictly enforce monotonic increases.

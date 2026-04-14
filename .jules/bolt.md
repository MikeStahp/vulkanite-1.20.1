## 2024-04-14 - Timeline Semaphore Monotonicity with Streams
**Learning:** Using `stream().max().orElse(defaultValue)` on a list of executions breaks the monotonically increasing requirement of timeline semaphores if the stream is non-empty but its maximum is less than `defaultValue`. The `orElse` is only returned if the stream is empty.
**Action:** Use a manual loop to compute the maximum, starting with the current maximum as the initial value, to guarantee both allocation-free execution and strict monotonic increases.


## $(date +%Y-%m-%d) - Replaced Hot-Path Java Streams
**Learning:** In hot rendering paths like `VCmdBuff.bindDSet` and `CommandManager.Queue.waitForExecutions`, Java Streams introduce unacceptable allocation and GC overhead per frame. Additionally, the stream implementation in `waitForExecutions` contained a subtle bug where it could decrease the timeline wait value if the incoming executions max was lower than the current wait.
**Action:** Always replace Java Streams with simple `for` loops and arrays in methods that execute on every frame or during command buffer encoding to eliminate boxing and lambda allocation overhead, and ensure correctness of timeline semaphore maximums.

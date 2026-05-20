## 2024-05-17 - [Stream Allocation Overhead in Hot Paths]
**Learning:** Hot rendering paths (like `VulkanPipeline.renderPostShadows`, `VCmdBuff.bindDSet`, `CommandManager.Queue.waitForExecutions`, `MixinIrisRenderingPipeline.getCustomTextures`) use Java Streams which cause significant GC pressure due to lambda allocation, boxing, and stream pipeline setup.
**Action:** Replace `stream().map...` with pre-allocated basic arrays and lists using enhanced for-loops to eliminate per-frame object allocation and maintain consistent frame rates. Use `Arrays.fill` where constant arrays are needed.

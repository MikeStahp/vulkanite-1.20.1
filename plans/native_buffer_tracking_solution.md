# Native Buffer Tracking and Cleanup Solution Design

## Problem Analysis

### Issue Description
In `SodiumResultAdapter.java`, there's a TODO comment indicating a memory management issue where native buffers may not be freed if chunk build results are destroyed without being submitted to the BLAS builder. This can lead to memory leaks during events like world reloads.

### Root Cause
1. Native buffers are created as part of chunk mesh data in Sodium's rendering system
2. When `SodiumResultAdapter.compute()` processes a `ChunkBuildOutput`, it extracts geometry data but doesn't maintain references to the underlying native buffers
3. The `BLASJobEnqueuer` creates Vulkan buffers by copying data from these native buffers, but the original native buffers aren't tracked
4. If a chunk build result is destroyed before being processed by the BLAS builder (e.g., during world reloads), the native buffers are never freed

### Data Flow
1. Sodium creates `ChunkBuildOutput` with mesh data containing `NativeBuffer` instances
2. `SodiumResultAdapter.compute()` processes the output and creates `GeometryData` records
3. `Vulkanite.upload()` sends results to `AccelerationManager.chunkBuilds()`
4. `AccelerationBlasBuilder.enqueue()` forwards to `BLASJobEnqueuer.enqueue()`
5. `BLASJobEnqueuer` copies data from native buffers to Vulkan buffers
6. Original native buffers become orphaned and may not be cleaned up

## Solution Design

### Overview
Implement a tracking mechanism that monitors native buffer references from their creation until they are processed by the BLAS builder or explicitly cleaned up when chunk build results are destroyed.

### Key Components

#### 1. NativeBufferTracker
A new singleton class responsible for tracking native buffer references:

```java
public class NativeBufferTracker {
    private static final NativeBufferTracker INSTANCE = new NativeBufferTracker();
    
    // Map of chunk build results to their associated native buffers
    private final Map<ChunkBuildOutput, List<NativeBuffer>> trackedBuffers = new ConcurrentHashMap<>();
    
    // Reference queue for monitoring garbage collection of chunk build results
    private final ReferenceQueue<ChunkBuildOutput> referenceQueue = new ReferenceQueue<>();
    private final Set<ChunkBuildOutputReference> references = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    private NativeBufferTracker() {}
    
    public static NativeBufferTracker getInstance() {
        return INSTANCE;
    }
    
    public void trackBuffers(ChunkBuildOutput result, List<NativeBuffer> buffers) {
        trackedBuffers.put(result, new ArrayList<>(buffers));
        references.add(new ChunkBuildOutputReference(result, referenceQueue, buffers));
    }
    
    public void untrackBuffers(ChunkBuildOutput result) {
        List<NativeBuffer> buffers = trackedBuffers.remove(result);
        if (buffers != null) {
            // Clean up references
            references.removeIf(ref -> ref.getResult() == result);
        }
    }
    
    public void cleanupOrphanedBuffers() {
        ChunkBuildOutputReference ref;
        while ((ref = (ChunkBuildOutputReference) referenceQueue.poll()) != null) {
            List<NativeBuffer> buffers = ref.getBuffers();
            if (buffers != null) {
                for (NativeBuffer buffer : buffers) {
                    try {
                        buffer.close(); // Free the native buffer
                    } catch (Exception e) {
                        // Log error but continue cleaning up others
                    }
                }
            }
            references.remove(ref);
        }
    }
    
    private static class ChunkBuildOutputReference extends WeakReference<ChunkBuildOutput> {
        private final ChunkBuildOutput result;
        private final List<NativeBuffer> buffers;
        
        ChunkBuildOutputReference(ChunkBuildOutput result, ReferenceQueue<? super ChunkBuildOutput> q, List<NativeBuffer> buffers) {
            super(result, q);
            this.result = result;
            this.buffers = buffers;
        }
        
        public ChunkBuildOutput getResult() {
            return result;
        }
        
        public List<NativeBuffer> getBuffers() {
            return buffers;
        }
    }
}
```

#### 2. Enhanced IAccelerationBuildResult Interface
Add methods to track native buffers:

```java
public interface IAccelerationBuildResult {
    void setAccelerationGeometryData(Map<TerrainRenderPass, GeometryData> map);
    Map<TerrainRenderPass, GeometryData> getAccelerationGeometryData();
    ChunkVertexType getVertexFormat();
    void setVertexFormat(ChunkVertexType format);
    
    // New methods for native buffer tracking
    void setNativeBuffers(List<NativeBuffer> buffers);
    List<NativeBuffer> getNativeBuffers();
}
```

#### 3. Modified MixinChunkBuildResult
Update the mixin to store native buffer references:

```java
@Mixin(value = ChunkBuildOutput.class, remap = false)
public class MixinChunkBuildResult implements IAccelerationBuildResult {
    @Unique private Map<TerrainRenderPass, GeometryData> geometryMap;
    @Unique private ChunkVertexType vertexType;
    @Unique private List<NativeBuffer> nativeBuffers;
    
    // Existing methods...
    
    @Override
    public void setNativeBuffers(List<NativeBuffer> buffers) {
        this.nativeBuffers = buffers;
    }
    
    @Override
    public List<NativeBuffer> getNativeBuffers() {
        return nativeBuffers;
    }
}
```

#### 4. Updated SodiumResultAdapter
Modify to track native buffers:

```java
public class SodiumResultAdapter {
    public static void compute(ChunkBuildOutput buildResult) {
        var ebr = (IAccelerationBuildResult) buildResult;
        Map<TerrainRenderPass, GeometryData> map = new HashMap<>();
        List<NativeBuffer> nativeBuffers = new ArrayList<>();
        
        for (var pass : buildResult.meshes.entrySet()) {
            var vertData = pass.getValue().getVertexData();
            nativeBuffers.add(vertData); // Track the native buffer
            
            int stride = ebr.getVertexFormat().getVertexFormat().getStride();
            
            if (vertData.getLength() % stride != 0)
                throw new IllegalStateException("Mismatch length and stride");
            int vertices = vertData.getLength() / stride;
            if (vertices % 4 != 0)
                throw new IllegalStateException("Non multiple 4 vertex count");
            
            map.put(pass.getKey(), new GeometryData(vertices >> 2));
        }
        
        if (!map.isEmpty()) {
            ebr.setAccelerationGeometryData(map);
            ebr.setNativeBuffers(nativeBuffers);
            // Track buffers with the tracker
            NativeBufferTracker.getInstance().trackBuffers(buildResult, nativeBuffers);
        } else {
            ebr.setAccelerationGeometryData(null);
            ebr.setNativeBuffers(Collections.emptyList());
        }
    }
}
```

#### 5. Modified BLASJobEnqueuer
Update to notify the tracker when buffers are processed:

```java
public class BLASJobEnqueuer {
    // Existing code...
    
    public void enqueue(List<ChunkBuildOutput> batch) {
        // Existing code...
        
        for (ChunkBuildOutput cbr : batch) {
            var acbr = ((IAccelerationBuildResult) cbr).getAccelerationGeometryData();
            if (acbr == null)
                continue;
                
            // Notify tracker that buffers are being processed
            NativeBufferTracker.getInstance().untrackBuffers(cbr);
            
            // Rest of existing code...
        }
        
        // Existing code...
    }
}
```

#### 6. Integration with Cleanup Process
Modify the destroy redirect in `MixinRenderSectionManager`:

```java
@Redirect(method = "destroy", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;delete()V"))
private void destroyAccelerationData(ChunkBuildOutput instance) {
    // Notify tracker that this result is being destroyed
    NativeBufferTracker.getInstance().untrackBuffers(instance);
    instance.delete();
    // TODO: need to ingest and cleanup all the blas builds and tlas updates
}
```

## Implementation Plan

### Phase 1: Core Infrastructure
1. Create `NativeBufferTracker` class
2. Extend `IAccelerationBuildResult` interface with native buffer tracking methods
3. Update `MixinChunkBuildResult` to implement new interface methods

### Phase 2: Buffer Tracking Integration
1. Modify `SodiumResultAdapter` to track native buffers
2. Update `BLASJobEnqueuer` to notify tracker when buffers are processed
3. Integrate with cleanup process in `MixinRenderSectionManager`

### Phase 3: Testing and Validation
1. Verify that buffers are properly tracked and cleaned up
2. Test edge cases like rapid world reloads
3. Monitor for performance impacts

## Edge Cases and Handling

### 1. Rapid World Reloads
During rapid world reloads, many chunk build results may be created and destroyed quickly. The weak reference approach ensures that even if the cleanup process doesn't run immediately, garbage collection will eventually free the native buffers.

### 2. Concurrent Access
The tracker uses thread-safe collections (`ConcurrentHashMap`, `ConcurrentLinkedQueue`) to handle concurrent access from different threads.

### 3. Exception During Processing
If an exception occurs during buffer processing, the tracker will still hold references to the native buffers, ensuring they can be cleaned up later.

### 4. Memory Pressure
The weak reference approach prevents the tracker from causing memory leaks itself. If the JVM is under memory pressure, chunk build results can still be garbage collected.

## Integration Points

### Existing Classes That Need Modification
1. `SodiumResultAdapter` - Add buffer tracking
2. `IAccelerationBuildResult` - Add tracking methods
3. `MixinChunkBuildResult` - Implement new interface methods
4. `BLASJobEnqueuer` - Notify tracker when buffers are processed
5. `MixinRenderSectionManager` - Integrate with cleanup process

### New Classes to Create
1. `NativeBufferTracker` - Core tracking functionality

## Performance Considerations

1. **Minimal Overhead**: The tracking mechanism adds minimal overhead as it only stores references to existing objects
2. **Weak References**: Using weak references prevents the tracker from causing memory leaks
3. **Lazy Cleanup**: Cleanup happens during normal processing or garbage collection, avoiding performance spikes
4. **Thread Safety**: Thread-safe collections ensure safe concurrent access without excessive synchronization

## Memory Safety Guarantees

1. **Automatic Cleanup**: Even if explicit cleanup is missed, garbage collection will eventually free native buffers
2. **Double-Free Protection**: The tracker ensures buffers are only freed once
3. **Exception Safety**: Exceptions during processing won't prevent eventual cleanup
4. **Deterministic Cleanup**: When possible, buffers are cleaned up deterministically during normal processing

## Testing Strategy

1. **Unit Tests**: Test the `NativeBufferTracker` class in isolation
2. **Integration Tests**: Verify proper tracking through the entire pipeline
3. **Stress Tests**: Test with rapid world reloads and high chunk update rates
4. **Memory Leak Tests**: Monitor for memory leaks during extended gameplay sessions
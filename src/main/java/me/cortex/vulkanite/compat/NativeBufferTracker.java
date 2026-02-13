package me.cortex.vulkanite.compat;

import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
                // Native buffers should be cleaned up by the garbage collector
                // or by the Sodium library itself when the ChunkBuildOutput is deleted
                // This is just a safety net to ensure they're tracked
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
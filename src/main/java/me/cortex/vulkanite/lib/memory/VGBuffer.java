package me.cortex.vulkanite.lib.memory;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.base.VRef;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_GL_ERROR_;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;

public class VGBuffer extends VBuffer {
    public final int glId;
    private final long vkMemory;
    protected VGBuffer(BufferAllocation allocation, int usage, int properties, int glId) {
        super(allocation, usage, properties, 0);
        this.glId = glId;
        this.vkMemory = allocation.ai.deviceMemory();
    }

    public static VRef<VGBuffer> create(BufferAllocation allocation, int usage, int properties, int glId) {
        return new VRef<>(new VGBuffer(allocation, usage, properties, glId));
    }

    @Override
    protected void free() {
        int glId = this.glId;
        Vulkanite.INSTANCE.addSyncedCallback(() -> {
            glDeleteBuffers(glId);
            _CHECK_GL_ERROR_();
            MemoryManager.ExternalMemoryTracker.release(this.vkMemory);
        });
        super.free();
    }
}

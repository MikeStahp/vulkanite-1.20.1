package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.acceleration.SharedQuadVkIndexBuffer;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.other.VUtil;
import me.cortex.vulkanite.lib.other.sync.VFence;
import net.irisshaders.iris.layer.OuterWrappedRenderType;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.Pair;
import net.minecraft.world.World;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.util.*;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class EntityCapture {
    private final VertexCaptureProvider capture = new VertexCaptureProvider();

    //TODO: dont pass camera position of 0,0,0 in due to loss of precision
    public List<Pair<RenderLayer, BufferBuilder.BuiltBuffer>> capture(float delta, ClientWorld world) {
        LevelRendererAccessor lra = (LevelRendererAccessor) MinecraftClient.getInstance().worldRenderer;

        MatrixStack stack = new MatrixStack();

        //ImmediateState.renderWithExtendedVertexFormat = true;
        for (var entity : world.getEntities()) {
            lra.invokeRenderEntity(entity, 0,0,0, delta, stack, capture);
        }
        //ImmediateState.renderWithExtendedVertexFormat = false;

        //Note the lifetime of the buffer data is till the next call of render
        var buffers = capture.end();
        if (buffers.isEmpty()) {
            return null;
        }
        return buffers;
    }


    private static class VertexCaptureProvider implements VertexConsumerProvider {
        private final Map<RenderLayer, BufferBuilder> builderMap = new HashMap<>();
        private final List<Pair<RenderLayer, BufferBuilder.BuiltBuffer>> reusableBuffers = new ArrayList<>();

        @Override
        public VertexConsumer getBuffer(RenderLayer layer) {
            BufferBuilder builder = builderMap.get(layer);
            if (builder == null) {
                builder = new BufferBuilder(420);
                builderMap.put(layer, builder);
            }
            if (!builder.isBuilding()) {
                builder.reset();
                builder.begin(layer.getDrawMode(), layer.getVertexFormat());
            }
            return builder;
        }

        public List<Pair<RenderLayer, BufferBuilder.BuiltBuffer>> end() {
            reusableBuffers.clear();
            for (Map.Entry<RenderLayer, BufferBuilder> entry : builderMap.entrySet()) {
                RenderLayer layer = entry.getKey();
                BufferBuilder buffer = entry.getValue();

                if (buffer.isBuilding()) {
                    var builtBuffer = buffer.end();
                    if (builtBuffer.getParameters().getBufferSize() == 0) {
                        continue;//Dont add empty buffers
                    }
                    //TODO: Doesnt support terrian vertex format yet, requires a second blas so that the instance offset can be the same
                    // as terrain instance offset
                    if (builtBuffer.getParameters().format().equals(IrisVertexFormats.TERRAIN)) {
                        System.out.println("Skipping block entities (TERRAIN format)");
                        continue;
                    }

                    // TODO: Support anything other than ENTITY
                    if (!builtBuffer.getParameters().format().equals(IrisVertexFormats.ENTITY)) {
                        System.out.println("Skipping non-Entity format: " + builtBuffer.getParameters().format().toString());
                        continue;
                    }

                    //Dont support no texture things
//                    if (!(layer instanceof OuterWrappedRenderType)) {
//                        System.out.println("Skipping render layer that's not a MultiPhase, is " + layer.getClass().getName() + " instead");
//                        continue;
//                    }
//
//                    var texture = ((RenderLayer.MultiPhase)layer).phases.texture;
//                    if ((texture == null) || (texture.getId().isEmpty())) {
//                        System.out.println("Skipping render layer with no texture");
//                        continue;
//                    }

                    reusableBuffers.add(new Pair<>(layer, builtBuffer));
                }
            }
            return reusableBuffers;
        }
    }
}


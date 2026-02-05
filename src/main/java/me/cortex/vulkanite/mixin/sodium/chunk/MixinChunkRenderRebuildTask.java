package me.cortex.vulkanite.mixin.sodium.chunk;

import me.cortex.vulkanite.client.rendering.Light;
import me.cortex.vulkanite.client.rendering.LightColorHelper;
import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.compat.ILightHolder;
import me.cortex.vulkanite.compat.SodiumResultAdapter;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import me.jellysquid.mods.sodium.client.util.task.CancellationToken;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public class MixinChunkRenderRebuildTask {
    @Shadow
    @Final
    private RenderSection render;

    @Inject(method = "execute(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lme/jellysquid/mods/sodium/client/util/task/CancellationToken;)Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;", at = @At("TAIL"))
    private void performExtraBuild(ChunkBuildContext buildContext, CancellationToken cancellationToken,
            CallbackInfoReturnable<ChunkBuildOutput> cir) {
        if (IrisApi.getInstance().isShaderPackInUse()) {
            var buildResult = cir.getReturnValue();
            ((IAccelerationBuildResult) buildResult)
                    .setVertexFormat(((VertexFormatAccessor) buildContext.buffers).getVertexType());
            SodiumResultAdapter.compute(buildResult);

            // Light Collection Logic
            List<Light> lights = new ArrayList<>();
            int originX = this.render.getChunkX() << 4;
            int originY = this.render.getChunkY() << 4;
            int originZ = this.render.getChunkZ() << 4;

            BlockView slice = buildContext.cache.getWorldSlice();
            BlockPos.Mutable pos = new BlockPos.Mutable();

            for (int y = 0; y < 16; y++) {
                if (cancellationToken.isCancelled())
                    return;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int absX = originX + x;
                        int absY = originY + y;
                        int absZ = originZ + z;

                        pos.set(absX, absY, absZ);
                        BlockState state = slice.getBlockState(pos);
                        if (state.getLuminance() > 0) {
                            Vector3f position = new Vector3f(absX + 0.5f, absY + 0.5f, absZ + 0.5f);
                            float radius = (float) state.getLuminance();
                            Vector3f color = LightColorHelper.getColor(state);
                            lights.add(new Light(position, radius, color, Light.TYPE_BLOCK));
                        }
                    }
                }
            }

            if (!lights.isEmpty()) {
                ((ILightHolder) buildResult).setLights(lights);
            }
        }
    }
}

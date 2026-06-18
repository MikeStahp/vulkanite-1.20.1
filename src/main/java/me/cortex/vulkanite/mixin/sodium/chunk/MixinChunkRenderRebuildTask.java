package me.cortex.vulkanite.mixin.sodium.chunk;

import me.cortex.vulkanite.compat.ISectionLightBuildResult;
import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.compat.SectionLightTable;
import me.cortex.vulkanite.compat.SectionLightExtractor;
import me.cortex.vulkanite.compat.SodiumResultAdapter;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import me.jellysquid.mods.sodium.client.util.task.CancellationToken;
import net.irisshaders.iris.api.v0.IrisApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public class MixinChunkRenderRebuildTask {
    @Unique
    private static final Logger VULKANITE_LOGGER = LoggerFactory.getLogger(MixinChunkRenderRebuildTask.class);
    @Unique
    private static final long VULKANITE_INFO_LOG_INTERVAL_NANOS = 5_000_000_000L;
    @Unique
    private static final long VULKANITE_SLOW_CAPTURE_LOG_NANOS = 2_000_000L;
    @Unique
    private static long vulkanite$lastBuildTimingLogNanos;

    @Inject(method = "execute(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lme/jellysquid/mods/sodium/client/util/task/CancellationToken;)Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;", at = @At("TAIL"))
    private void performExtraBuild(ChunkBuildContext buildContext, CancellationToken cancellationToken, CallbackInfoReturnable<ChunkBuildOutput> cir) {
        if (IrisApi.getInstance().isShaderPackInUse()) {
            var buildResult = cir.getReturnValue();
            if (buildResult == null) {
                return;
            }
            long totalStartNanos = System.nanoTime();
            long geometryStartNanos = totalStartNanos;
            SodiumResultAdapter.compute(buildResult, ((VertexFormatAccessor) buildContext.buffers).getVertexType());
            long geometryNanos = System.nanoTime() - geometryStartNanos;
            long lightStartNanos = System.nanoTime();
            SectionLightTable sectionLights = SectionLightExtractor.scan(
                    buildResult.render, buildContext.cache.getWorldSlice());
            long lightNanos = System.nanoTime() - lightStartNanos;
            ((ISectionLightBuildResult) buildResult).setSectionLights(sectionLights);
            vulkanite$logBuildTiming(buildResult, sectionLights, geometryNanos, lightNanos,
                    System.nanoTime() - totalStartNanos);
        }
    }

    @Unique
    private static void vulkanite$logBuildTiming(ChunkBuildOutput buildResult, SectionLightTable sectionLights,
            long geometryNanos, long lightNanos, long totalNanos) {
        var geometry = ((IAccelerationBuildResult) buildResult).getAccelerationGeometry();
        int geometryRanges = geometry == null ? 0 : geometry.geometries().size();
        long geometryBytes = geometry == null ? 0L : geometry.totalSizeBytes();
        int lightCount = sectionLights == null ? 0 : sectionLights.size();
        boolean hasOpaque = sectionLights != null && sectionLights.hasOpaqueBlocks();
        long now = System.nanoTime();
        boolean info = lightCount > 0
                || totalNanos >= VULKANITE_SLOW_CAPTURE_LOG_NANOS
                || now - vulkanite$lastBuildTimingLogNanos >= VULKANITE_INFO_LOG_INTERVAL_NANOS;
        if (info) {
            vulkanite$lastBuildTimingLogNanos = now;
            VULKANITE_LOGGER.info("[Vulkanite] Sodium rebuild tail: section={}, geometryRanges={}, geometryBytes={}, lights={}, opacity={}, capture={} ms, lightScan={} ms, total={} ms",
                    buildResult.render.getPosition(), geometryRanges, geometryBytes, lightCount, hasOpaque,
                    vulkanite$formatMillis(geometryNanos), vulkanite$formatMillis(lightNanos),
                    vulkanite$formatMillis(totalNanos));
        } else {
            VULKANITE_LOGGER.debug("[Vulkanite] Sodium rebuild tail: section={}, geometryRanges={}, geometryBytes={}, lights={}, opacity={}, capture={} ms, lightScan={} ms, total={} ms",
                    buildResult.render.getPosition(), geometryRanges, geometryBytes, lightCount, hasOpaque,
                    vulkanite$formatMillis(geometryNanos), vulkanite$formatMillis(lightNanos),
                    vulkanite$formatMillis(totalNanos));
        }
    }

    @Unique
    private static String vulkanite$formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }
}

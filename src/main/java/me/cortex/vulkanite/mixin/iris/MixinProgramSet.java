package me.cortex.vulkanite.mixin.iris;

import me.cortex.vulkanite.compat.IGetRaytracingSource;
import me.cortex.vulkanite.compat.RaytracingShaderSource;
import me.cortex.vulkanite.client.config.DLSSConfig;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Mixin(value = ProgramSet.class, remap = false)
public abstract class MixinProgramSet implements IGetRaytracingSource {
    @Unique
    private RaytracingShaderSource[] sources;

    @Unique
    private String injectDefines(String source, String defines) {
        if (source == null)
            return null;
        if (defines == null || defines.isEmpty())
            return source;

        // Find the #version line
        int versionIndex = source.indexOf("#version");
        if (versionIndex != -1) {
            int nextLineIndex = source.indexOf('\n', versionIndex);
            if (nextLineIndex != -1) {
                // Insert after #version line
                return source.substring(0, nextLineIndex + 1) + defines + source.substring(nextLineIndex + 1);
            }
        }
        // Fallback: prepend if no #version found
        return defines + source;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void injectRTShaders(AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider,
            ShaderProperties shaderProperties, ShaderPack pack, CallbackInfo ci) {
        System.out.println("[Vulkanite] Checking for ray tracing shaders...");

        // Load DLSS config
        DLSSConfig dlssConfig = DLSSConfig.load();
        boolean enableDLSSRR = dlssConfig.isRayReconstructionEnabled();
        boolean enableReSTIR = dlssConfig.isReSTIREnabled();

        // Extract shader properties to inject as defines
        StringBuilder definesBuilder = new StringBuilder();
        try {
            Field variablesField = ShaderProperties.class.getDeclaredField("variables");
            variablesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> variables = (Map<String, String>) variablesField.get(shaderProperties);
            if (variables != null) {
                for (Map.Entry<String, String> entry : variables.entrySet()) {
                    // Skip defines that we control via DLSSConfig to avoid redefinition
                    if (entry.getKey().equals("ENABLE_DLSS_RR") || entry.getKey().equals("ENABLE_RESTIR")) {
                        continue;
                    }
                    definesBuilder.append("#define ").append(entry.getKey()).append(" ").append(entry.getValue())
                            .append("\n");
                }
            }

            System.out.println("[Vulkanite] Injected shader properties: " + definesBuilder.length() + " chars");
        } catch (Exception e) {
            System.err.println("[Vulkanite] Failed to extract shader properties: " + e.getMessage());
        }

        // Inject DLSSConfig settings
        definesBuilder.append("#define ENABLE_DLSS_RR ").append(enableDLSSRR ? 1 : 0).append("\n");
        definesBuilder.append("#define ENABLE_RESTIR ").append(enableReSTIR ? 1 : 0).append("\n");

        // RT Quality Defaults
        definesBuilder.append("#define SUN_INTENSITY ").append(dlssConfig.getSunIntensity()).append("\n");
        definesBuilder.append("#define INDIRECT_SCALE ").append(dlssConfig.getIndirectScale()).append("\n");
        definesBuilder.append("#define AMBIENT_FACTOR ").append(dlssConfig.getAmbientFactor()).append("\n");
        definesBuilder.append("#define MIN_LIGHTING ").append(dlssConfig.getMinLighting()).append("\n");
        definesBuilder.append("#define SPECULAR_INTENSITY ").append(dlssConfig.getSpecularIntensity()).append("\n");
        definesBuilder.append("#define GAMMA ").append(dlssConfig.getGamma()).append("\n");

        // ReSTIR Advanced Config
        definesBuilder.append("#define RESTIR_MAX_HISTORY ").append(dlssConfig.getRestirMaxHistory()).append("\n");
        definesBuilder.append("#define RESTIR_SPATIAL_RADIUS ").append(dlssConfig.getRestirSpatialRadius())
                .append("\n");
        definesBuilder.append("#define RESTIR_SPATIAL_SAMPLES ").append(dlssConfig.getRestirSpatialSamples())
                .append("\n");

        String defines = definesBuilder.toString();

        List<RaytracingShaderSource> sourceList = new ArrayList<>();
        int passId = 0;
        while (true) {
            int pass = passId++;
            var gen = sourceProvider.apply(directory.resolve("ray" + pass + ".rgen"));
            System.out.println("[Vulkanite] Looking for ray" + pass + ".rgen, found: " + (gen != null));
            if (gen == null)
                break;
            // Inject defines into raygen shader
            gen = injectDefines(gen, defines);

            List<String> missSources = new ArrayList<>();
            int missId = 0;
            while (true) {
                var miss = sourceProvider.apply(directory.resolve("ray" + pass + "_" + (missId++) + ".rmiss"));
                if (miss == null)
                    break;
                // Inject defines into miss shader
                missSources.add(injectDefines(miss, defines));
            }
            List<RaytracingShaderSource.RayHitSource> hitSources = new ArrayList<>();
            int hitId = 0;
            while (true) {
                int hit = hitId++;
                var close = sourceProvider.apply(directory.resolve("ray" + pass + "_" + hit + ".rchit"));
                var any = sourceProvider.apply(directory.resolve("ray" + pass + "_" + hit + ".rahit"));
                var intersect = sourceProvider.apply(directory.resolve("ray" + pass + "_" + hit + ".rint"));
                if (close == null && any == null && intersect == null)
                    break;

                // Inject defines into hit shaders if they exist
                if (close != null)
                    close = injectDefines(close, defines);
                if (any != null)
                    any = injectDefines(any, defines);
                if (intersect != null)
                    intersect = injectDefines(intersect, defines);

                hitSources.add(new RaytracingShaderSource.RayHitSource(close, any, intersect));
            }
            if (missSources.isEmpty()) {
                throw new IllegalStateException("No miss shaders for pass " + pass);
            }
            if (hitSources.isEmpty()) {
                throw new IllegalStateException("No hit shaders for pass " + pass);
            }
            sourceList.add(new RaytracingShaderSource("raypass_" + pass,
                    gen,
                    missSources.toArray(new String[0]),
                    hitSources.toArray(new RaytracingShaderSource.RayHitSource[0])));
            System.out.println("[Vulkanite] Found ray pass " + pass + " with " + missSources.size()
                    + " miss shaders and " + hitSources.size() + " hit shaders");
        }
        if (!sourceList.isEmpty()) {
            sources = sourceList.toArray(new RaytracingShaderSource[0]);
            System.out.println("[Vulkanite] Ray tracing shaders loaded: " + sources.length + " passes");
        } else {
            System.out.println("[Vulkanite] No ray tracing shaders found");
        }
    }

    @Override
    public RaytracingShaderSource[] getRaytracingSource() {
        return sources;
    }
}

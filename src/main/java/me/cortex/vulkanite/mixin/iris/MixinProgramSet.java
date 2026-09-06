package me.cortex.vulkanite.mixin.iris;

import me.cortex.vulkanite.acceleration.HybridAccelerationConfig;
import me.cortex.vulkanite.compat.IGetRaytracingSource;
import me.cortex.vulkanite.compat.RaytracingShaderSource;
import me.cortex.vulkanite.client.config.DLSSConfig;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(value = ProgramSet.class, remap = false)
public abstract class MixinProgramSet implements IGetRaytracingSource {
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinProgramSet.class);

    @Unique
    private static final String VULKANITE_RESTIR_DEFINE = "VULKANITE_RESTIR";

    @Unique
    private static final String RESTIR_LIBRARY_RESOURCE =
            "/assets/vulkanite/shaders/raytracing/lib/restir.glsl";

    @Unique
    private static final String RESTIR_API_VERSION_MARKER =
            "#define VULKANITE_RESTIR_API_VERSION 2";

    @Unique
    private RaytracingShaderSource[] sources;

    @Unique
    private String injectDefines(String source, String defines) {
        if (source == null)
            return null;
        if (defines == null || defines.isEmpty())
            return source;

        String updatedSource = source;
        StringBuilder missingDefines = new StringBuilder();
        for (String define : defines.split("\\R")) {
            String trimmed = define.trim();
            String[] parts = trimmed.split("\\s+", 3);
            if (parts.length < 3 || !"#define".equals(parts[0])) {
                continue;
            }

            Pattern existingDefine = Pattern.compile(
                    "(?m)^[\\t ]*#define[\\t ]+" + Pattern.quote(parts[1]) + "(?:[\\t ]+.*)?$");
            Matcher matcher = existingDefine.matcher(updatedSource);
            if (matcher.find()) {
                updatedSource = matcher.replaceAll(Matcher.quoteReplacement(trimmed));
            } else {
                missingDefines.append(trimmed).append('\n');
            }
        }

        if (missingDefines.isEmpty()) {
            return updatedSource;
        }

        // Find the #version line
        int versionIndex = updatedSource.indexOf("#version");
        if (versionIndex != -1) {
            int nextLineIndex = updatedSource.indexOf('\n', versionIndex);
            if (nextLineIndex != -1) {
                // Insert after #version line
                return updatedSource.substring(0, nextLineIndex + 1)
                        + missingDefines
                        + updatedSource.substring(nextLineIndex + 1);
            }
        }
        // Fallback: prepend if no #version found
        return missingDefines + updatedSource;
    }

    @Unique
    private Boolean getPackRestirSetting(String source) {
        if (source == null) {
            return null;
        }

        for (String line : source.split("\\R")) {
            String trimmed = line.trim();
            String[] defineParts = trimmed.split("\\s+", 3);
            if (defineParts.length < 2
                    || !"#define".equals(defineParts[0])
                    || !VULKANITE_RESTIR_DEFINE.equals(defineParts[1])) {
                continue;
            }

            String value = defineParts.length == 3 ? defineParts[2].trim() : "";
            int commentStart = value.indexOf("//");
            if (commentStart >= 0) {
                value = value.substring(0, commentStart).trim();
            }

            if (value.isEmpty()) {
                return Boolean.TRUE;
            }

            String token = value.split("\\s+", 2)[0];
            if ("1".equals(token) || "true".equalsIgnoreCase(token)) {
                return Boolean.TRUE;
            }
            if ("0".equals(token) || "false".equalsIgnoreCase(token)) {
                return Boolean.FALSE;
            }

            throw new IllegalArgumentException(
                    VULKANITE_RESTIR_DEFINE + " must be defined as 0/1 or false/true");
        }

        return null;
    }

    @Unique
    private boolean usesRestirApi(String source) {
        return source != null
                && (source.contains("RestirReservoir")
                        || source.contains("initReservoir(")
                        || source.contains("updateReservoir(")
                        || source.contains("combineReservoir(")
                        || source.contains("finalizeReservoir(")
                        || source.contains("spatialReuse(")
                        || source.contains("resolveSunShadowHistory(")
                        || source.contains("clearSunShadowHistory("));
    }

    @Unique
    private boolean hasVulkaniteRestirLibrary(String source) {
        return source != null && source.contains(RESTIR_API_VERSION_MARKER);
    }

    @Unique
    private String injectAfterExtensions(String source, String injectedSource) {
        int insertAt = -1;
        int lineStart = 0;

        while (lineStart < source.length()) {
            int lineEnd = source.indexOf('\n', lineStart);
            if (lineEnd == -1) {
                lineEnd = source.length();
            }

            String line = source.substring(lineStart, lineEnd).trim();
            if (line.startsWith("#version") || line.startsWith("#extension")) {
                insertAt = lineEnd < source.length() ? lineEnd + 1 : lineEnd;
            } else if (!line.isEmpty() && insertAt >= 0) {
                break;
            }

            lineStart = lineEnd + 1;
        }

        if (insertAt < 0) {
            return injectedSource + "\n" + source;
        }

        return source.substring(0, insertAt)
                + "\n// Vulkanite mod-owned ReSTIR implementation\n"
                + injectedSource
                + "\n// End Vulkanite ReSTIR implementation\n\n"
                + source.substring(insertAt);
    }

    @Unique
    private String injectRestirLibrary(String source) {
        if (hasVulkaniteRestirLibrary(source)) {
            return source;
        }

        try (InputStream stream = MixinProgramSet.class.getResourceAsStream(RESTIR_LIBRARY_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled shader library " + RESTIR_LIBRARY_RESOURCE);
            }

            String library = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String injected = injectAfterExtensions(source, library);
            if (!hasVulkaniteRestirLibrary(injected)) {
                throw new IllegalStateException("ReSTIR library injection did not provide the Vulkanite API");
            }
            return injected;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load bundled ReSTIR shader library", e);
        }
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void injectRTShaders(AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider,
            ShaderProperties shaderProperties, ShaderPack pack, CallbackInfo ci) {
        LOGGER.info("Checking for ray tracing shaders");

        DLSSConfig dlssConfig = DLSSConfig.load();
        boolean enableDLSSRR = dlssConfig.isRayReconstructionEnabled();
        String firstRaygen = sourceProvider.apply(directory.resolve("ray0.rgen"));
        boolean enableReSTIR = dlssConfig.isReSTIREnabled()
                && (Boolean.TRUE.equals(getPackRestirSetting(firstRaygen)) || usesRestirApi(firstRaygen));

        var accelerationConfig = HybridAccelerationConfig.fromSystemProperties();
        StringBuilder definesBuilder = new StringBuilder();
        definesBuilder.append("#define ENABLE_DLSS_RR ").append(enableDLSSRR ? 1 : 0).append("\n");
        definesBuilder.append("#define ENABLE_RESTIR ").append(enableReSTIR ? 1 : 0).append("\n");
        definesBuilder.append("#define VULKANITE_COMPILE_DIAGNOSTICS ")
                .append(accelerationConfig.compileDiagnostics() ? 1 : 0)
                .append("\n");
        definesBuilder.append("#define VULKANITE_HYBRID_SHADOW_PIPELINE ")
                .append(accelerationConfig.compileHybridShadows() ? 1 : 0)
                .append("\n");

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
            var gen = pass == 0 ? firstRaygen : sourceProvider.apply(directory.resolve("ray" + pass + ".rgen"));
            LOGGER.debug("Looking for ray{}.rgen, found: {}", pass, gen != null);
            if (gen == null)
                break;

            Boolean passRestirSetting = getPackRestirSetting(gen);
            boolean passUsesRestir = usesRestirApi(gen);
            if (passRestirSetting != null || passUsesRestir) {
                gen = injectRestirLibrary(gen);
            }
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
                if (intersect != null && intersect.contains("VULKANITE_REUSE_INTERSECTION_2")) {
                    intersect = sourceProvider.apply(directory.resolve("ray" + pass + "_2.rint"));
                    if (intersect == null) {
                        throw new IllegalStateException("Procedural shadow hit group requires ray" + pass + "_2.rint");
                    }
                }
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
            LOGGER.info("Found ray pass {} with {} miss shaders and {} hit shaders",
                    pass, missSources.size(), hitSources.size());
        }
        if (!sourceList.isEmpty()) {
            sources = sourceList.toArray(new RaytracingShaderSource[0]);
            LOGGER.info("Ray tracing shaders loaded: {} passes", sources.length);
        } else {
            LOGGER.info("No ray tracing shaders found");
        }
    }

    @Override
    public RaytracingShaderSource[] getRaytracingSource() {
        return sources;
    }
}

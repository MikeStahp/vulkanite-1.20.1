package me.cortex.vulkanite.acceleration;

import java.util.Properties;

/**
 * Startup selection and dependencies for experimental geometry by ray role.
 * Device capabilities and shaderpack compatibility are checked by the consumers.
 */
public record HybridAccelerationConfig(
        boolean compileDiagnostics,
        boolean compileHybridShadows,
        boolean hybridShadows,
        boolean proceduralReflections,
        boolean proceduralBlas,
        boolean filterShadowGeometry) {
    public static final String DIAGNOSTICS_PROPERTY = "vulkanite.compileDiagnostics";
    public static final String SHADOW_PIPELINE_PROPERTY = "vulkanite.hybridShadowPipeline";
    public static final String SHADOW_PROPERTY = "vulkanite.hybridShadow";
    public static final String REFLECTION_PROPERTY = "vulkanite.proceduralReflection";
    public static final String BLAS_PROPERTY = "vulkanite.proceduralBlas";
    public static final String SHADOW_GEOMETRY_PROPERTY = "vulkanite.hybridShadowGeometry";

    public static HybridAccelerationConfig fromSystemProperties() {
        return fromProperties(System.getProperties());
    }

    public static HybridAccelerationConfig fromProperties(Properties properties) {
        boolean diagnostics = enabled(properties, DIAGNOSTICS_PROPERTY, false);
        boolean shadows = enabled(properties, SHADOW_PROPERTY, false);
        boolean reflections = enabled(properties, REFLECTION_PROPERTY, false);
        boolean shadowPipeline = diagnostics || shadows
                || enabled(properties, SHADOW_PIPELINE_PROPERTY, false);
        // Compiling experimental stages alone does not require section geometry.
        // Explicit BLAS-only builds remain available for development measurements.
        boolean blas = enabled(properties, BLAS_PROPERTY, diagnostics || shadows || reflections);
        boolean filtered = blas && (shadows || reflections)
                && !"triangle-only".equalsIgnoreCase(
                        properties.getProperty(SHADOW_GEOMETRY_PROPERTY, "filtered"));
        return new HybridAccelerationConfig(diagnostics, shadowPipeline, shadows,
                reflections, blas, filtered);
    }

    private static boolean enabled(Properties properties, String name, boolean defaultValue) {
        return Boolean.parseBoolean(properties.getProperty(name, Boolean.toString(defaultValue)));
    }
}

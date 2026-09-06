package me.cortex.vulkanite.acceleration;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static me.cortex.vulkanite.acceleration.HybridAccelerationConfig.*;
import static org.junit.jupiter.api.Assertions.*;

class HybridAccelerationConfigTest {
    @Test
    void normalLaunchDoesNotBuildExperimentalGeometryOrCompileItsStages() {
        var config = fromProperties(new Properties());
        assertFalse(config.compileDiagnostics());
        assertFalse(config.compileHybridShadows());
        assertFalse(config.hybridShadows());
        assertFalse(config.proceduralReflections());
        assertFalse(config.proceduralBlas());
        assertFalse(config.filterShadowGeometry());
    }

    @Test
    void shadowSelectionSuppliesCompilationAndGeometryDependencies() {
        var config = configured(SHADOW_PROPERTY, "true", SHADOW_PIPELINE_PROPERTY, "false");
        assertTrue(config.compileHybridShadows());
        assertTrue(config.hybridShadows());
        assertTrue(config.proceduralBlas());
        assertTrue(config.filterShadowGeometry());
        assertFalse(config.proceduralReflections());
        assertFalse(config.compileDiagnostics());
    }

    @Test
    void reflectionSelectionDoesNotEnableShadowDispatch() {
        var config = configured(REFLECTION_PROPERTY, "true");
        assertTrue(config.proceduralReflections());
        assertTrue(config.proceduralBlas());
        assertTrue(config.filterShadowGeometry());
        assertFalse(config.hybridShadows());
        assertFalse(config.compileHybridShadows());
    }

    @Test
    void diagnosticsKeepTheStandaloneComparisonGeometryWithoutProductionFiltering() {
        var config = configured(DIAGNOSTICS_PROPERTY, "true");
        assertTrue(config.compileDiagnostics());
        assertTrue(config.compileHybridShadows());
        assertTrue(config.proceduralBlas());
        assertFalse(config.hybridShadows());
        assertFalse(config.proceduralReflections());
        assertFalse(config.filterShadowGeometry());
    }

    @Test
    void compilationAndBlasMeasurementsCanBeSelectedIndependently() {
        var compileOnly = configured(SHADOW_PIPELINE_PROPERTY, "true");
        assertTrue(compileOnly.compileHybridShadows());
        assertFalse(compileOnly.proceduralBlas());
        var blasOnly = configured(BLAS_PROPERTY, "true");
        assertTrue(blasOnly.proceduralBlas());
        assertFalse(blasOnly.compileHybridShadows());
        assertFalse(blasOnly.filterShadowGeometry());
    }

    @Test
    void explicitGeometryFallbacksOverrideExperimentalSelections() {
        var disabled = configured(SHADOW_PROPERTY, "true", REFLECTION_PROPERTY, "true",
                DIAGNOSTICS_PROPERTY, "true", BLAS_PROPERTY, "false");
        assertFalse(disabled.proceduralBlas());
        assertFalse(disabled.filterShadowGeometry());
        var unfiltered = configured(SHADOW_PROPERTY, "true", SHADOW_GEOMETRY_PROPERTY, "TRIANGLE-ONLY");
        assertTrue(unfiltered.proceduralBlas());
        assertFalse(unfiltered.filterShadowGeometry());
    }

    private static HybridAccelerationConfig configured(String... pairs) {
        Properties properties = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.setProperty(pairs[i], pairs[i + 1]);
        }
        return fromProperties(properties);
    }
}

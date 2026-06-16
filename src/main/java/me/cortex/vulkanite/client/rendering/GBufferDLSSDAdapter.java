package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.other.VImageView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter for extracting DLSSD inputs from G-buffer views.
 * 
 * <p>This class provides utility methods for mapping Iris G-buffer textures
 * to DLSSD input parameters as defined in the NVIDIA NGX SDK.</p>
 * 
 * <h2>G-Buffer Mapping</h2>
 * <ul>
 *   <li>colortex1 (index 0) - Albedo/Diffuse color</li>
 *   <li>colortex2 (index 1) - Material properties (roughness, metallic, etc.)</li>
 *   <li>colortex3 (index 2) - World-space normals</li>
 *   <li>colortex4 (index 3) - World position</li>
 *   <li>colortex5 (index 4) - Blocklight, skylight, and ambient occlusion</li>
 * </ul>
 * 
 * <h2>DLSSD Input Requirements (from nvsdk_ngx_helpers_dlssd_vk.h)</h2>
 * <ul>
 *   <li>pInDiffuseAlbedo - RGB diffuse color</li>
 *   <li>pInSpecularAlbedo - F0 reflectance values</li>
 *   <li>pInNormals - World-space normals, roughness in .w if packed</li>
 *   <li>pInRoughness - Separate roughness buffer if unpacked mode</li>
 * </ul>
 * 
 * @see DLSSDProcessor
 * @see DLSSConfig
 */
public class GBufferDLSSDAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(GBufferDLSSDAdapter.class);

    // G-buffer indices (matching Iris colortex bindings)
    public static final int GBUFFER_ALBEDO = 0;      // colortex1
    public static final int GBUFFER_MATERIAL = 1;    // colortex2
    public static final int GBUFFER_NORMALS = 2;     // colortex3
    public static final int GBUFFER_WORLDPOS = 3;    // colortex4
    public static final int GBUFFER_EXTRA = 4;       // colortex5

    /**
     * Container for extracted DLSSD inputs.
     */
    public static class DLSSDInputs {
        public VRef<VImageView> diffuseAlbedoView;
        public VRef<VImageView> specularAlbedoView;
        public VRef<VImageView> normalsView;
        public VRef<VImageView> roughnessView;
        public boolean roughnessPacked = true;

        /**
         * Check if the required inputs are available.
         * 
         * @return true if minimum required inputs are present
         */
        public boolean hasRequiredInputs() {
            return diffuseAlbedoView != null && normalsView != null;
        }
    }

    /**
     * Extract DLSSD inputs from G-buffer views.
     * 
     * @param gbufferViews Array of G-buffer image views
     * @return Container with extracted inputs
     */
    public static DLSSDInputs extractDLSSDInputs(VRef<VImageView>[] gbufferViews) {
        DLSSDInputs inputs = new DLSSDInputs();

        if (gbufferViews == null || gbufferViews.length == 0) {
            LOGGER.warn("G-buffer views array is null or empty");
            return inputs;
        }

        // Extract diffuse albedo (colortex1)
        if (gbufferViews.length > GBUFFER_ALBEDO && gbufferViews[GBUFFER_ALBEDO] != null) {
            inputs.diffuseAlbedoView = gbufferViews[GBUFFER_ALBEDO];
            LOGGER.debug("Diffuse albedo: colortex1");
        }

        // VulkaniteRT stores specular F0 in colortex2.rgb. colortex5 contains
        // blocklight/skylight/AO, so using it here corrupts RR's material guide.
        if (gbufferViews.length > GBUFFER_MATERIAL && gbufferViews[GBUFFER_MATERIAL] != null) {
            inputs.specularAlbedoView = gbufferViews[GBUFFER_MATERIAL];
            LOGGER.debug("Specular albedo: colortex2");
        }

        // Extract normals (colortex3)
        if (gbufferViews.length > GBUFFER_NORMALS && gbufferViews[GBUFFER_NORMALS] != null) {
            inputs.normalsView = gbufferViews[GBUFFER_NORMALS];
            LOGGER.debug("Normals: colortex3");
        }

        // Roughness is typically packed in normals.w for DLSSD
        // If unpacked mode is needed, it would come from material buffer
        inputs.roughnessPacked = true;
        inputs.roughnessView = null;

        return inputs;
    }

    /**
     * Log the current G-buffer configuration for debugging.
     * 
     * @param gbufferViews Array of G-buffer image views
     */
    public static void logGBufferConfiguration(VRef<VImageView>[] gbufferViews) {
        if (gbufferViews == null) {
            LOGGER.info("[G-Buffer Config] gbufferViews is null");
            return;
        }

        LOGGER.info("[G-Buffer Config] {} views available:", gbufferViews.length);

        String[] names = {"Albedo", "Material", "Normals", "WorldPos", "Extra"};
        for (int i = 0; i < gbufferViews.length; i++) {
            String name = (i < names.length) ? names[i] : "Unknown";
            if (gbufferViews[i] != null) {
                var view = gbufferViews[i].get();
                if (view != null && view.image != null) {
                    var img = view.image.get();
                    LOGGER.info("  [{}] {}: {}x{}, format={}",
                            i, name, img.width, img.height, img.format);
                } else {
                    LOGGER.info("  [{}] {}: view or image is null", i, name);
                }
            } else {
                LOGGER.info("  [{}] {}: null", i, name);
            }
        }
    }

    /**
     * Validate that G-buffer has the required textures for DLSSD.
     * 
     * @param gbufferViews Array of G-buffer image views
     * @return true if minimum requirements are met
     */
    public static boolean validateGBufferForDLSSD(VRef<VImageView>[] gbufferViews) {
        if (gbufferViews == null || gbufferViews.length < 3) {
            LOGGER.warn("G-buffer validation failed: insufficient views (need at least 3, have {})",
                    gbufferViews == null ? 0 : gbufferViews.length);
            return false;
        }

        // Required: Albedo (index 0) and Normals (index 2)
        if (gbufferViews[GBUFFER_ALBEDO] == null) {
            LOGGER.warn("G-buffer validation failed: Albedo (index 0) is null");
            return false;
        }

        if (gbufferViews[GBUFFER_NORMALS] == null) {
            LOGGER.warn("G-buffer validation failed: Normals (index 2) is null");
            return false;
        }

        LOGGER.debug("G-buffer validation passed for DLSSD");
        return true;
    }
}

package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;

/**
 * Adapter class that maps Iris G-buffer textures to DLSSD inputs.
 * 
 * Iris G-buffer layout (colortex1-5):
 * - colortex1 (gbufferViews[0]): Albedo/Diffuse color (RGB)
 * - colortex2 (gbufferViews[1]): Material properties (metallic, roughness, etc.)
 * - colortex3 (gbufferViews[2]): World-space normals (RGB, roughness in A if packed)
 * - colortex4 (gbufferViews[3]): World position or other data
 * - colortex5 (gbufferViews[4]): Additional properties (specular, etc.)
 * 
 * DLSSD Required Inputs:
 * - Diffuse Albedo: RGB surface diffuse color
 * - Specular Albedo: F0 reflectance values
 * - Normals: World-space normals with roughness in .w (packed mode)
 * - Roughness: Separate texture (only if unpacked mode)
 * 
 * This adapter handles the mapping between these formats.
 */
public class GBufferDLSSDAdapter {

    /**
     * Result class containing all DLSSD G-buffer inputs
     */
    public static class DLSSDGBufferInputs {
        public VRef<VImageView> diffuseAlbedoView;
        public VRef<VImageView> specularAlbedoView;
        public VRef<VImageView> normalsView;
        public VRef<VImageView> roughnessView; // May be null if packed in normals.w

        // Image references for transition operations
        public VRef<VImage> diffuseAlbedoImage;
        public VRef<VImage> specularAlbedoImage;
        public VRef<VImage> normalsImage;
        public VRef<VImage> roughnessImage;

        public boolean roughnessPacked = true;
    }

    /**
     * Extract DLSSD inputs from Iris G-buffer views.
     * 
     * @param gbufferViews Array of G-buffer views from Iris:
     *                     [0] = colortex1 (Albedo)
     *                     [1] = colortex2 (Material)
     *                     [2] = colortex3 (Normals)
     *                     [3] = colortex4 (World Position)
     *                     [4] = colortex5 (Extra)
     * @param packedRoughness If true, roughness is packed in normals.w
     * @return DLSSDGBufferInputs containing the mapped views
     */
    public static DLSSDGBufferInputs extractDLSSDInputs(
            VRef<VImageView>[] gbufferViews,
            boolean packedRoughness) {

        DLSSDGBufferInputs inputs = new DLSSDGBufferInputs();
        inputs.roughnessPacked = packedRoughness;

        if (gbufferViews == null || gbufferViews.length < 3) {
            System.err.println("[GBufferDLSSDAdapter] Insufficient G-buffer views provided!");
            return inputs;
        }

        // Diffuse Albedo comes from colortex1 (gbufferViews[0])
        // This is the primary surface color (albedo)
        if (gbufferViews[0] != null) {
            inputs.diffuseAlbedoView = gbufferViews[0];
            // Note: We don't have direct access to the image here, 
            // the view contains what we need for DLSSD
            System.out.println("[GBufferDLSSDAdapter] Diffuse Albedo mapped from colortex1");
        } else {
            System.err.println("[GBufferDLSSDAdapter] WARNING: colortex1 (Albedo) is null!");
        }

        // Specular Albedo (F0) - typically comes from colortex2 or colortex5
        // In many shader packs:
        // - colortex2 contains material properties (metallic, roughness, ao, etc.)
        // - colortex5 may contain specular data
        // 
        // For DLSSD, we need F0 reflectance values. This is often calculated as:
        // - For dielectrics: ~0.04 (4% reflectance)
        // - For metals: albedo value (colored F0)
        //
        // If colortex5 has specular data, use it; otherwise use colortex2
        if (gbufferViews.length > 4 && gbufferViews[4] != null) {
            inputs.specularAlbedoView = gbufferViews[4];
            System.out.println("[GBufferDLSSDAdapter] Specular Albedo mapped from colortex5");
        } else if (gbufferViews[1] != null) {
            inputs.specularAlbedoView = gbufferViews[1];
            System.out.println("[GBufferDLSSDAdapter] Specular Albedo mapped from colortex2 (material)");
        } else {
            System.err.println("[GBufferDLSSDAdapter] WARNING: No specular albedo source available!");
        }

        // Normals come from colortex3 (gbufferViews[2])
        // In packed mode, roughness is stored in the .w component
        if (gbufferViews[2] != null) {
            inputs.normalsView = gbufferViews[2];
            System.out.println("[GBufferDLSSDAdapter] Normals mapped from colortex3 (packed roughness: " + packedRoughness + ")");
        } else {
            System.err.println("[GBufferDLSSDAdapter] WARNING: colortex3 (Normals) is null!");
        }

        // Roughness - if unpacked mode, we need a separate roughness texture
        // This could come from colortex2.g (common convention) or a separate buffer
        if (!packedRoughness && gbufferViews[1] != null) {
            // In unpacked mode, roughness might be in colortex2
            // The shader would need to extract it, but for DLSSD we pass the whole texture
            inputs.roughnessView = gbufferViews[1];
            System.out.println("[GBufferDLSSDAdapter] Roughness mapped from colortex2 (unpacked mode)");
        }

        return inputs;
    }

    /**
     * Get the image from a VImageView VRef.
     * This is a helper method to extract the underlying image for transitions.
     * 
     * @param viewRef Reference to the image view
     * @return Reference to the underlying image, or null if not available
     */
    public static VRef<VImage> getImageFromView(VRef<VImageView> viewRef) {
        if (viewRef == null || viewRef.get() == null) {
            return null;
        }
        // The VImageView has a public 'image' field that references the underlying image
        return viewRef.get().image;
    }

    /**
     * Validate that all required DLSSD inputs are present.
     * 
     * @param inputs The G-buffer inputs to validate
     * @return true if all required inputs are present
     */
    public static boolean validateInputs(DLSSDGBufferInputs inputs) {
        if (inputs == null) {
            System.err.println("[GBufferDLSSDAdapter] Inputs are null!");
            return false;
        }

        boolean valid = true;

        if (inputs.diffuseAlbedoView == null) {
            System.err.println("[GBufferDLSSDAdapter] Missing diffuse albedo!");
            valid = false;
        }

        if (inputs.specularAlbedoView == null) {
            System.err.println("[GBufferDLSSDAdapter] Missing specular albedo!");
            valid = false;
        }

        if (inputs.normalsView == null) {
            System.err.println("[GBufferDLSSDAdapter] Missing normals!");
            valid = false;
        }

        if (!inputs.roughnessPacked && inputs.roughnessView == null) {
            System.err.println("[GBufferDLSSDAdapter] Missing roughness (unpacked mode)!");
            valid = false;
        }

        return valid;
    }

    /**
     * Log the current G-buffer configuration for debugging.
     * 
     * @param gbufferViews The G-buffer views to log
     */
    public static void logGBufferConfiguration(VRef<VImageView>[] gbufferViews) {
        System.out.println("[GBufferDLSSDAdapter] G-buffer Configuration:");
        String[] names = {"colortex1 (Albedo)", "colortex2 (Material)", 
                          "colortex3 (Normals)", "colortex4 (Position)", "colortex5 (Extra)"};
        
        for (int i = 0; i < Math.min(gbufferViews.length, names.length); i++) {
            String status = (gbufferViews[i] != null) ? "present" : "NULL";
            System.out.println("  [" + i + "] " + names[i] + ": " + status);
        }
    }
}
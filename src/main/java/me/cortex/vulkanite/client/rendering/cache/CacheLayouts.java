package me.cortex.vulkanite.client.rendering.cache;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CacheLayouts {
    
    public static final class SurfaceDirect {
        public static final int HEADER_BYTES = 16;
        // Key + metadata + 96 stable three-word lights + directional/dependency payloads.
        public static final int ENTRY_BYTES = 1216;
        public static final int FILL_REQUEST_RECORD_BYTES = 32;
        public static final int MAX_ENTRIES = 32_768;
        public static final int CACHE_BUFFER_BYTES = HEADER_BYTES + MAX_ENTRIES * ENTRY_BYTES;
    }

    public static final class DiffuseRadiance {
        public static final int HEADER_BYTES = 16;
        public static final int ENTRY_BYTES = 32;
        public static final int FILL_REQUEST_RECORD_BYTES = 32;
        public static final int MAX_ENTRIES = 16_384;
        public static final int CACHE_BUFFER_BYTES = HEADER_BYTES + MAX_ENTRIES * ENTRY_BYTES;
    }

    public static final class SpecularTransport {
        public static final int HEADER_BYTES = 16;
        public static final int ENTRY_BYTES = 48;
        public static final int FILL_REQUEST_RECORD_BYTES = 48;
        public static final int MAX_ENTRIES = 32_768;
        public static final int CACHE_BUFFER_BYTES = HEADER_BYTES + MAX_ENTRIES * ENTRY_BYTES;
    }

    public static final int MAX_FILL_REQUESTS = 256;

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\$\\{([A-Z_]+)\\}");

    public static String injectGlslConstants(String template) {
        Matcher matcher = TOKEN_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = switch (token) {
                // Surface Direct
                case "SURFACE_DIRECT_HEADER_BYTES" -> String.valueOf(SurfaceDirect.HEADER_BYTES);
                case "SURFACE_DIRECT_ENTRY_BYTES" -> String.valueOf(SurfaceDirect.ENTRY_BYTES);
                case "SURFACE_DIRECT_MAX_ENTRIES" -> String.valueOf(SurfaceDirect.MAX_ENTRIES);
                
                // Diffuse Radiance
                case "DIFFUSE_RADIANCE_HEADER_BYTES" -> String.valueOf(DiffuseRadiance.HEADER_BYTES);
                case "DIFFUSE_RADIANCE_ENTRY_BYTES" -> String.valueOf(DiffuseRadiance.ENTRY_BYTES);
                case "DIFFUSE_RADIANCE_MAX_ENTRIES" -> String.valueOf(DiffuseRadiance.MAX_ENTRIES);
                
                // Specular Transport
                case "SPECULAR_TRANSPORT_HEADER_BYTES" -> String.valueOf(SpecularTransport.HEADER_BYTES);
                case "SPECULAR_TRANSPORT_ENTRY_BYTES" -> String.valueOf(SpecularTransport.ENTRY_BYTES);
                case "SPECULAR_TRANSPORT_MAX_ENTRIES" -> String.valueOf(SpecularTransport.MAX_ENTRIES);

                // Global
                case "MAX_FILL_REQUESTS" -> String.valueOf(MAX_FILL_REQUESTS);
                
                default -> throw new IllegalStateException("Unresolved GLSL template token: ${" + token + "}");
            };
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);
        return result.toString();
    }
}

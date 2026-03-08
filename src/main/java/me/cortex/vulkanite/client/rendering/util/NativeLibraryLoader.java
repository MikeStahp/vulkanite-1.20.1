package me.cortex.vulkanite.client.rendering.util;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public class NativeLibraryLoader {
    private static final String LIB_EXTENSION = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? ".dll" : ".so";

    /**
     * Loads a native library, extracting it from the JAR if necessary.
     * 
     * @param libName The name of the library (without extension)
     * @return true if loaded successfully
     */
    public static boolean loadLibrary(String libName) {
        String fullName = libName + LIB_EXTENSION;
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path nativeDir = gameDir.resolve("natives");
        
        try {
            Files.createDirectories(nativeDir);
        } catch (IOException e) {
            System.err.println("[Vulkanite] Failed to create natives directory: " + e.getMessage());
            return false;
        }

        Path libPath = nativeDir.resolve(fullName);

        // Always try to extract/update the library from the JAR to ensure version match
        if (extractLibrary(fullName, libPath)) {
            try {
                System.load(libPath.toAbsolutePath().toString());
                System.out.println("[Vulkanite] Loaded native library: " + libPath.toAbsolutePath());
                return true;
            } catch (UnsatisfiedLinkError e) {
                System.err.println("[Vulkanite] Failed to load library " + libPath + ": " + e.getMessage());
            }
        } else {
             // If extraction failed (maybe not in JAR), try loading from system path or working directory
             try {
                 System.loadLibrary(libName);
                 System.out.println("[Vulkanite] Loaded system library: " + libName);
                 return true;
             } catch (UnsatisfiedLinkError e) {
                 System.err.println("[Vulkanite] Failed to load system library " + libName + ": " + e.getMessage());
             }
        }
        
        return false;
    }

    /**
     * Ensures that the NVIDIA DLSS binaries are present in the working directory.
     * The DLSS SDK requires these files to be in the same directory as the executable (or working dir).
     */
    public static void ensureDLSSBinaries() {
        String[] dlssFiles = { "nvngx_dlss.dll", "nvngx_dlssd.dll" };
        // On Linux they would be .so, but user specifically mentioned Windows/DLLs for DLSS
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
             dlssFiles = new String[] { "libnvngx_dlss.so", "libnvngx_dlssd.so" };
        }

        Path workingDir = FabricLoader.getInstance().getGameDir(); // Usually the working directory

        for (String fileName : dlssFiles) {
            Path targetPath = workingDir.resolve(fileName);
            // We only extract if missing, or maybe we should overwrite to ensure correct version?
            // For now, let's extract if missing to avoid overwriting user's custom DLLs (e.g. newer versions)
            if (!Files.exists(targetPath)) {
                if (extractLibrary(fileName, targetPath)) {
                    System.out.println("[Vulkanite] Extracted DLSS binary: " + fileName);
                } else {
                    System.err.println("[Vulkanite] Could not find bundled " + fileName + ". Please install it manually.");
                }
            }
        }
    }

    private static boolean extractLibrary(String fileName, Path targetPath) {
        // Try to find the file in the classpath (root or /natives/)
        String[] possiblePaths = { "/" + fileName, "/natives/" + fileName, "/assets/vulkanite/natives/" + fileName };
        
        for (String path : possiblePaths) {
            try (InputStream is = NativeLibraryLoader.class.getResourceAsStream(path)) {
                if (is != null) {
                    Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    return true;
                }
            } catch (IOException e) {
                // Continue to next path
            }
        }
        return false;
    }
}

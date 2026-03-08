package me.cortex.vulkanite.client.rendering;

import com.sun.jna.Native;
import me.cortex.vulkanite.client.rendering.util.NativeLibraryLoader;

/**
 * Manages the loading and access to the DLSSBridge native library.
 * Allows for runtime reloading (e.g. after user drops in missing DLLs).
 */
public class DLSSLoader {
    private static DLSSBridge instance;
    private static boolean attemptedLoad = false;

    /**
     * Returns the loaded DLSSBridge instance, or null if not loaded.
     * Does NOT trigger a load if it hasn't been attempted yet.
     */
    public static synchronized DLSSBridge getInstance() {
        if (!attemptedLoad) {
            load();
        }
        return instance;
    }

    /**
     * Attempts to load the DLSS native library.
     * Can be called multiple times to retry loading (e.g. "Recheck" button).
     */
    public static synchronized void load() {
        attemptedLoad = true;
        
        // First, ensure DLSS binaries are present in the working directory
        NativeLibraryLoader.ensureDLSSBinaries();

        // Pre-load NGX binary to assist driver discovery
        // COMMENTED OUT: Corrupts SDK state (bad00007). Let C++ bridge handle loading.
        /*
        try {
            java.nio.file.Path workingDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
            String dllName = System.getProperty("os.name").toLowerCase().contains("win") ? "nvngx_dlss.dll" : "libnvngx_dlss.so";
            java.nio.file.Path dllPath = workingDir.resolve(dllName);
            if (java.nio.file.Files.exists(dllPath)) {
                System.load(dllPath.toAbsolutePath().toString());
                System.out.println("[Vulkanite DLSS] Pre-loaded " + dllName);
            }
        } catch (Throwable t) {
            System.err.println("[Vulkanite DLSS] Failed to pre-load NGX binary: " + t.getMessage());
        }
        */
        
        // Then load our bridge library
        if (NativeLibraryLoader.loadLibrary("vulkanite_dlss_bridge")) {
            try {
                // Map the JNA interface
                instance = Native.load("vulkanite_dlss_bridge", DLSSBridge.class);
                System.out.println("[Vulkanite DLSS] DLSS Bridge loaded successfully.");
            } catch (UnsatisfiedLinkError e) {
                 System.err.println("[Vulkanite DLSS] Failed to map JNA interface: " + e.getMessage());
                 instance = null;
            }
        } else {
            // Fallback to JNA default loading if manual loading failed
            try {
                instance = Native.load("vulkanite_dlss_bridge", DLSSBridge.class);
                System.out.println("[Vulkanite DLSS] DLSS Bridge loaded via JNA default.");
            } catch (UnsatisfiedLinkError e) {
                System.err.println("[Vulkanite DLSS] Failed to load vulkanite_dlss_bridge: " + e.getMessage());
                instance = null;
            }
        }
    }
    
    /**
     * Checks if the bridge is currently loaded.
     */
    public static synchronized boolean isAvailable() {
        return getInstance() != null;
    }
}

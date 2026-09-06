package me.cortex.vulkanite.lib.shader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

final class ShaderBinaryCache {
    private static final String CACHE_KEY_VERSION = "vulkanite-shaderc-v2|vulkan-1.2|spirv-1.4";
    private static final int SPIRV_MAGIC = 0x07230203;
    private static final int SPIRV_HEADER_BYTES = 5 * Integer.BYTES;
    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;

    private final Path directory;

    ShaderBinaryCache(Path directory) {
        this.directory = directory;
    }

    static Path defaultDirectory() {
        String configured = System.getProperty("vulkanite.shaderCacheDir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String workingDirectory = System.getProperty("user.dir");
        Path gameDirectory = workingDirectory == null || workingDirectory.isBlank()
                ? Path.of(".")
                : Path.of(workingDirectory);
        return gameDirectory.resolve("cache").resolve("vulkanite").resolve("shaderc-v2");
    }

    static String keyFor(String source, int vulkanStage, boolean debugInfo, Optimization optimization) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(CACHE_KEY_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(vulkanStage)
                    .array());
            digest.update((byte) (debugInfo ? 1 : 0));
            digest.update(optimization.name().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    Optional<byte[]> read(String key) throws IOException {
        Path entry = entryPath(key);
        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        long size = Files.size(entry);
        if (size < SPIRV_HEADER_BYTES || size > MAX_ENTRY_BYTES || size % Integer.BYTES != 0) {
            return Optional.empty();
        }
        byte[] bytes = Files.readAllBytes(entry);
        return isValidSpirv(bytes) ? Optional.of(bytes) : Optional.empty();
    }

    void write(String key, byte[] bytes) throws IOException {
        if (!isValidSpirv(bytes)) {
            throw new IllegalArgumentException("Refusing to cache malformed SPIR-V");
        }
        Files.createDirectories(directory);
        Path entry = entryPath(key);
        Path temporary = Files.createTempFile(directory, key + ".", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, entry,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, entry, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Path entryPath(String key) {
        if (key.length() != 64 || !key.chars().allMatch(ShaderBinaryCache::isLowerHexDigit)) {
            throw new IllegalArgumentException("Invalid shader cache key");
        }
        return directory.resolve(key + ".spv");
    }

    static boolean isValidSpirv(byte[] bytes) {
        return bytes.length >= SPIRV_HEADER_BYTES
                && bytes.length <= MAX_ENTRY_BYTES
                && bytes.length % Integer.BYTES == 0
                && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt() == SPIRV_MAGIC;
    }

    private static boolean isLowerHexDigit(int value) {
        return value >= '0' && value <= '9' || value >= 'a' && value <= 'f';
    }

    enum Optimization {
        ZERO,
        PERFORMANCE
    }
}

package me.cortex.vulkanite.lib.shader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderBinaryCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keyChangesWithEveryCompilationInput() {
        String baseline = ShaderBinaryCache.keyFor(
                "void main() {}", 1, false, ShaderBinaryCache.Optimization.PERFORMANCE);

        assertNotEquals(baseline, ShaderBinaryCache.keyFor(
                "void main() { int x = 1; }", 1, false, ShaderBinaryCache.Optimization.PERFORMANCE));
        assertNotEquals(baseline, ShaderBinaryCache.keyFor(
                "void main() {}", 2, false, ShaderBinaryCache.Optimization.PERFORMANCE));
        assertNotEquals(baseline, ShaderBinaryCache.keyFor(
                "void main() {}", 1, true, ShaderBinaryCache.Optimization.PERFORMANCE));
        assertNotEquals(baseline, ShaderBinaryCache.keyFor(
                "void main() {}", 1, false, ShaderBinaryCache.Optimization.ZERO));
    }

    @Test
    void validSpirvRoundTripsThroughTheCache() throws Exception {
        ShaderBinaryCache cache = new ShaderBinaryCache(temporaryDirectory);
        String key = ShaderBinaryCache.keyFor("shader", 1, false, ShaderBinaryCache.Optimization.PERFORMANCE);
        byte[] spirv = minimalSpirvHeader();

        cache.write(key, spirv);

        assertArrayEquals(spirv, cache.read(key).orElseThrow());
    }

    @Test
    void malformedOrTruncatedEntriesAreIgnored() throws Exception {
        ShaderBinaryCache cache = new ShaderBinaryCache(temporaryDirectory);
        String key = ShaderBinaryCache.keyFor("shader", 1, false, ShaderBinaryCache.Optimization.PERFORMANCE);
        Files.createDirectories(temporaryDirectory);
        Files.write(cache.entryPath(key), new byte[] {0, 1, 2, 3});

        assertTrue(cache.read(key).isEmpty());
        assertFalse(ShaderBinaryCache.isValidSpirv(new byte[] {0, 1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> cache.write(key, new byte[] {0, 1, 2, 3}));
    }

    @Test
    void cacheKeysCannotEscapeTheCacheDirectory() {
        ShaderBinaryCache cache = new ShaderBinaryCache(temporaryDirectory);

        assertThrows(IllegalArgumentException.class, () -> cache.entryPath("../shader"));
        assertThrows(IllegalArgumentException.class, () -> cache.entryPath("A".repeat(64)));
    }

    private static byte[] minimalSpirvHeader() {
        return ByteBuffer.allocate(5 * Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x07230203)
                .putInt(0x00010400)
                .putInt(0)
                .putInt(1)
                .putInt(0)
                .array();
    }
}

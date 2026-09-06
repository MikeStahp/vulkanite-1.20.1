package me.cortex.vulkanite.lib.other;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GpuTimestampMathTest {
    @Test
    void computesOrdinaryAndWrappedDeltas() {
        assertEquals(25L, GpuTimestampMath.deltaTicks(100L, 125L, 64));
        assertEquals(8L, GpuTimestampMath.deltaTicks(-5L, 3L, 64));

        long wrap48 = 1L << 48;
        assertEquals(9L, GpuTimestampMath.deltaTicks(wrap48 - 4L, 5L, 48));
    }

    @Test
    void convertsTicksUsingTheDeviceTimestampPeriod() {
        assertEquals(0.0005, GpuTimestampMath.deltaMillis(20L, 220L, 64, 2.5f), 1.0e-12);
    }

    @Test
    void rejectsUnsupportedTimestampMetadata() {
        assertThrows(IllegalArgumentException.class, () -> GpuTimestampMath.deltaTicks(0L, 1L, 0));
        assertThrows(IllegalArgumentException.class, () -> GpuTimestampMath.deltaTicks(0L, 1L, 65));
        assertThrows(IllegalArgumentException.class,
                () -> GpuTimestampMath.deltaMillis(0L, 1L, 64, Float.NaN));
    }
}

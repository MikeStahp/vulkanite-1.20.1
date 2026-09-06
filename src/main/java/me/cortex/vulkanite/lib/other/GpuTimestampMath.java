package me.cortex.vulkanite.lib.other;

/** Conversion helpers for queue timestamps, including sub-64-bit wraparound. */
public final class GpuTimestampMath {
    private GpuTimestampMath() {
    }

    public static long deltaTicks(long start, long end, int validBits) {
        if (validBits < 1 || validBits > Long.SIZE) {
            throw new IllegalArgumentException("validBits must be in [1, 64]");
        }

        long delta = end - start;
        if (validBits == Long.SIZE) {
            // Java's two's-complement subtraction already performs modulo 2^64.
            // A measured command interval is necessarily far below 2^63 ticks.
            if (delta < 0L) {
                throw new IllegalArgumentException("timestamp interval exceeds signed 64-bit range");
            }
            return delta;
        }

        long mask = (1L << validBits) - 1L;
        return delta & mask;
    }

    public static double deltaMillis(long start, long end, int validBits, float timestampPeriodNanos) {
        if (!(timestampPeriodNanos > 0.0f) || !Float.isFinite(timestampPeriodNanos)) {
            throw new IllegalArgumentException("timestampPeriodNanos must be finite and positive");
        }
        return deltaTicks(start, end, validBits) * (double) timestampPeriodNanos / 1_000_000.0;
    }
}

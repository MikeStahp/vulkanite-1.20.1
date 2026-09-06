package me.cortex.vulkanite.compat;

import net.minecraft.util.math.ChunkSectionPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionLightTableTest {
    @Test
    void keepsProbeAndProceduralOccupancyIndependent() {
        long[] probeMask = SectionLightTable.newOpacityMask();
        long[] proceduralMask = SectionLightTable.newOpacityMask();
        SectionLightTable.setOpaque(probeMask, 1, 2, 3);
        SectionLightTable.setOpaque(probeMask, 4, 5, 6);
        SectionLightTable.setOpaque(proceduralMask, 4, 5, 6);

        SectionLightTable table = new SectionLightTable(
                ChunkSectionPos.from(0, 0, 0),
                List.of(),
                probeMask,
                null,
                true,
                proceduralMask);

        assertTrue(table.isOpaque(1, 2, 3));
        assertFalse(table.isProceduralFullCube(1, 2, 3));
        assertTrue(table.isOpaque(4, 5, 6));
        assertTrue(table.isProceduralFullCube(4, 5, 6));
    }
}

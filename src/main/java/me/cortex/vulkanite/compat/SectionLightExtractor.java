package me.cortex.vulkanite.compat;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class SectionLightExtractor {
    private static final int SECTION_SIZE = 16;
    private static final ConcurrentMap<BlockState, BlockScanProfile> PROFILE_CACHE = new ConcurrentHashMap<>();

    private SectionLightExtractor() {
    }

    public static SectionLightTable scan(RenderSection section, WorldSlice worldSlice) {
        if (section == null) {
            return null;
        }
        if (worldSlice == null) {
            return SectionLightTable.empty(section.getPosition());
        }

        int originX = section.getOriginX();
        int originY = section.getOriginY();
        int originZ = section.getOriginZ();
        List<SectionLight> lights = null;
        long[] opaqueBlocks = null;
        boolean hasOpaqueBlocks = false;
        BlockState lastState = null;
        BlockScanProfile lastProfile = null;

        for (int y = 0; y < SECTION_SIZE; y++) {
            int blockY = originY + y;
            for (int z = 0; z < SECTION_SIZE; z++) {
                int blockZ = originZ + z;
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int blockX = originX + x;
                    BlockState state = worldSlice.getBlockState(blockX, blockY, blockZ);
                    BlockScanProfile profile;
                    if (state == lastState) {
                        profile = lastProfile;
                    } else {
                        profile = profileFor(state);
                        lastState = state;
                        lastProfile = profile;
                    }

                    if (profile.opaqueForProbeVisibility()) {
                        if (opaqueBlocks == null) {
                            opaqueBlocks = SectionLightTable.newOpacityMask();
                        }
                        SectionLightTable.setOpaque(opaqueBlocks, x, y, z);
                        hasOpaqueBlocks = true;
                    }
                    if (profile.packedRgbEmission() == 0) {
                        continue;
                    }
                    if (lights == null) {
                        lights = new ArrayList<>();
                    }
                    lights.add(new SectionLight(
                            SectionLight.packLocalBlockPos(x, y, z),
                            profile.packedRgbEmission(),
                            profile.radius(),
                            profile.flags()));
                }
            }
        }

        if (lights == null && !hasOpaqueBlocks) {
            return SectionLightTable.empty(section.getPosition());
        }

        return new SectionLightTable(
                section.getPosition(),
                lights == null ? List.of() : lights,
                opaqueBlocks,
                hasOpaqueBlocks);
    }

    private static BlockScanProfile profileFor(BlockState state) {
        return PROFILE_CACHE.computeIfAbsent(state, SectionLightExtractor::createProfile);
    }

    private static BlockScanProfile createProfile(BlockState state) {
        boolean isAir = state.isAir();
        boolean fluidEmpty = isAir || state.getFluidState().isEmpty();
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        String namespace = id.getNamespace();
        String path = id.getPath();
        boolean opaqueForProbeVisibility = blocksProbeVisibility(state, path, isAir, fluidEmpty);

        int emission = state.getLuminance();
        if (emission <= 0) {
            return new BlockScanProfile(opaqueForProbeVisibility, 0, (short) 0, (short) 0);
        }

        int rgb = colorFor(path, emission);
        short flags = flagsFor(state, namespace, path, fluidEmpty);
        return new BlockScanProfile(
                opaqueForProbeVisibility,
                SectionLight.packRgbEmission(red(rgb), green(rgb), blue(rgb), emission),
                (short) Math.max(1, Math.min(255, emission)),
                flags);
    }

    private static boolean blocksProbeVisibility(BlockState state, String path, boolean isAir, boolean fluidEmpty) {
        // Match the RTX visibility path more than vanilla light opacity: mesh blockers
        // occlude probes, while transparent materials and tiny attachments do not.
        if (isAir || !fluidEmpty || isProbeTransparent(path) || isTinyNonOccluder(path)) {
            return false;
        }
        if (state.isOpaque()) {
            return true;
        }
        return isConservativeMeshBlocker(path);
    }

    private static boolean isProbeTransparent(String path) {
        return path.contains("glass")
                || path.contains("ice")
                || path.contains("water")
                || path.contains("portal")
                || path.contains("beacon");
    }

    private static boolean isTinyNonOccluder(String path) {
        return path.contains("torch")
                || path.contains("button")
                || path.contains("lever")
                || path.contains("pressure_plate")
                || path.contains("tripwire")
                || path.contains("rail")
                || path.contains("ladder")
                || path.contains("vine")
                || path.contains("flower")
                || path.contains("sapling")
                || path.contains("mushroom")
                || path.contains("banner")
                || path.contains("carpet")
                || path.contains("wire")
                || path.contains("dust")
                || path.contains("sign");
    }

    private static boolean isConservativeMeshBlocker(String path) {
        return path.contains("door")
                || path.contains("trapdoor")
                || path.contains("slab")
                || path.contains("stairs")
                || path.contains("fence")
                || path.contains("wall")
                || path.contains("bars")
                || path.contains("pane")
                || path.contains("anvil")
                || path.contains("chest")
                || path.contains("shulker_box")
                || path.contains("leaves");
    }

    private static short flagsFor(BlockState state, String namespace, String path, boolean fluidEmpty) {
        short flags = 0;
        if (!fluidEmpty) {
            flags |= SectionLight.FLAG_FLUID;
        }
        if (state.hasBlockEntity()) {
            flags |= SectionLight.FLAG_BLOCK_ENTITY;
        }
        if (!"minecraft".equals(namespace)) {
            flags |= SectionLight.FLAG_MODDED;
        }
        if (path.contains("redstone")) {
            flags |= SectionLight.FLAG_REDSTONE;
        }
        if (path.contains("soul")) {
            flags |= SectionLight.FLAG_SOUL;
        }
        return flags;
    }

    private static int colorFor(String path, int emission) {
        if (path.contains("soul")) {
            return rgb(86, 178, 255);
        }
        if (path.contains("redstone")) {
            return rgb(255, 48, 36);
        }
        int namedColor = colorFromBlockName(path);
        if (namedColor >= 0) {
            return namedColor;
        }
        if (path.contains("sea_lantern") || path.contains("conduit") || path.contains("beacon")) {
            return rgb(188, 255, 234);
        }
        if (path.contains("end_rod") || path.contains("end_gateway") || path.contains("end_portal")) {
            return rgb(228, 220, 255);
        }
        if (path.contains("sculk")) {
            return rgb(48, 180, 220);
        }
        if (path.contains("lava") || path.contains("magma")) {
            return rgb(255, 104, 22);
        }
        if (path.contains("glowstone")
                || path.contains("shroomlight")
                || path.contains("lantern")
                || path.contains("torch")
                || path.contains("campfire")
                || path.contains("candle")) {
            return rgb(255, 188, 94);
        }

        int floor = Math.max(96, emission * 12);
        return rgb(Math.min(255, floor + 80), Math.min(255, floor + 36), Math.min(255, floor));
    }

    private static int colorFromBlockName(String path) {
        if (path.contains("black")) {
            return rgb(80, 76, 92);
        }
        if (path.contains("gray")) {
            return rgb(150, 150, 150);
        }
        if (path.contains("white")) {
            return rgb(255, 246, 220);
        }
        if (path.contains("pink")) {
            return rgb(255, 120, 196);
        }
        if (path.contains("magenta")) {
            return rgb(230, 74, 255);
        }
        if (path.contains("purple")) {
            return rgb(160, 84, 255);
        }
        if (path.contains("blue")) {
            return rgb(84, 132, 255);
        }
        if (path.contains("cyan")) {
            return rgb(64, 224, 255);
        }
        if (path.contains("lime")) {
            return rgb(128, 255, 72);
        }
        if (path.contains("green")) {
            return rgb(68, 232, 96);
        }
        if (path.contains("yellow")) {
            return rgb(255, 236, 72);
        }
        if (path.contains("orange")) {
            return rgb(255, 150, 48);
        }
        if (path.contains("brown")) {
            return rgb(164, 108, 64);
        }
        if (path.contains("red")) {
            return rgb(255, 72, 48);
        }
        return -1;
    }

    private static int rgb(int red, int green, int blue) {
        return (red << 16) | (green << 8) | blue;
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    private record BlockScanProfile(
            boolean opaqueForProbeVisibility,
            int packedRgbEmission,
            short radius,
            short flags) {
    }
}

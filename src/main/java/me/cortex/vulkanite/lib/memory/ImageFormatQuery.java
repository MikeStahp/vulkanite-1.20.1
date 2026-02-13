package me.cortex.vulkanite.lib.memory;

public record ImageFormatQuery(int format, int imageType, int tiling, int usage, int flags) {}
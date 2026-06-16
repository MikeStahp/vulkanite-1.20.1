package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.other.VImageView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_GENERAL_EXT;

/**
 * Collects the OpenGL texture payload for a hybrid GL/Vulkan frame boundary.
 */
final class HybridInterop {
    private HybridInterop() {
    }

    record ImageBatch(int[] glIds, int[] glLayouts) {
    }

    static ImageBatch collect(
            List<VRef<VGImage>> outputImages,
            VRef<VImageView>[] gbufferViews,
            List<VRef<VGImage>> sampledSharedImages) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();

        for (VRef<VGImage> output : outputImages) {
            if (output != null && output.get() != null) {
                ids.add(output.get().glId);
            }
        }

        if (gbufferViews != null) {
            for (VRef<VImageView> view : gbufferViews) {
                addSharedImageId(ids, view);
            }
        }
        if (sampledSharedImages != null) {
            for (VRef<VGImage> image : sampledSharedImages) {
                if (image != null && image.get() != null) {
                    ids.add(image.get().glId);
                }
            }
        }

        int[] glIds = ids.stream().mapToInt(Integer::intValue).toArray();
        int[] layouts = new int[glIds.length];
        Arrays.fill(layouts, GL_LAYOUT_GENERAL_EXT);
        return new ImageBatch(glIds, layouts);
    }

    private static void addSharedImageId(java.util.Set<Integer> ids, VRef<VImageView> view) {
        if (view == null || view.get() == null || view.get().image == null) {
            return;
        }

        var image = view.get().image.get();
        if (image instanceof VGImage sharedImage) {
            ids.add(sharedImage.glId);
        }
    }
}

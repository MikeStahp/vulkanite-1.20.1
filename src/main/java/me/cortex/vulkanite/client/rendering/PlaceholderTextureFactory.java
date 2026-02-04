package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Factory for creating placeholder textures used when PBR textures are not
 * available.
 */
public final class PlaceholderTextureFactory {

    // Default normal value (pointing up in tangent space)
    private static final float[] DEFAULT_NORMAL = { 0.5f, 0.5f, 1.0f, 1.0f };

    private PlaceholderTextureFactory() {
    } // Prevent instantiation

    /**
     * Result containing a placeholder image and its view.
     */
    public record PlaceholderTexture(VRef<VImage> image, VRef<VImageView> view) {
    }

    /**
     * Creates a 4x4 placeholder specular texture filled with zeros.
     */
    public static PlaceholderTexture createPlaceholderSpecular(VContext ctx) {
        return createPlaceholder(ctx, VK_FORMAT_R8G8B8A8_UNORM, 4 * 4 * 4, PlaceholderTextureFactory::fillZeros);
    }

    /**
     * Creates a 4x4 placeholder normals texture with default normal (0.5, 0.5, 1.0,
     * 1.0).
     */
    public static PlaceholderTexture createPlaceholderNormals(VContext ctx) {
        return createPlaceholder(ctx, VK_FORMAT_R32G32B32A32_SFLOAT, 4 * 4 * 4 * 4,
                PlaceholderTextureFactory::fillNormals);
    }

    private static PlaceholderTexture createPlaceholder(VContext ctx, int format, long dataSize,
            java.util.function.BiConsumer<org.lwjgl.system.MemoryStack, long[]> dataFiller) {
        var image = ctx.memory.createImage2D(4, 4, 1, format,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        var view = VImageView.create(ctx, image);

        try (var stack = stackPush()) {
            long[] addressHolder = new long[1];
            dataFiller.accept(stack, addressHolder);
            long dataAddress = addressHolder[0];

            ctx.cmd.executeWait(cmd -> {
                cmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
                cmd.encodeImageUpload(ctx.memory, dataAddress, image, dataSize, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                cmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
            });
        }

        return new PlaceholderTexture(image, view);
    }

    private static void fillZeros(org.lwjgl.system.MemoryStack stack, long[] addressHolder) {
        IntBuffer data = stack.callocInt(4 * 4);
        addressHolder[0] = MemoryUtil.memAddress(data);
    }

    private static void fillNormals(org.lwjgl.system.MemoryStack stack, long[] addressHolder) {
        FloatBuffer data = stack.mallocFloat(4 * 4 * 4);
        for (int i = 0; i < 4 * 4; i++) {
            data.put(DEFAULT_NORMAL);
        }
        data.rewind();
        addressHolder[0] = MemoryUtil.memAddress(data);
    }
}

package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;

import java.util.function.Supplier;

public class SharedImageViewTracker {
    private final VContext ctx;
    private final Supplier<VRef<VGImage>> supplier;
    private VRef<VImageView> view;

    public SharedImageViewTracker(VContext ctx, Supplier<VRef<VGImage>> imageSupplier) {
        this.supplier = imageSupplier;
        this.ctx = ctx;
    }

    // NOTE: getting the image doesnt invalidate/check for a different image
    public VRef<VImage> getImage() {
        if (view != null) {
            return view.get().image.addRef();
        }
        return null;
    }

    public VRef<VImageView> getView() {
        return getView(this.supplier);
    }

    public VRef<VImageView> getView(Supplier<VRef<VGImage>> imageSupplier) {
        VRef<VGImage> image = imageSupplier.get();
        try {
            boolean NeedsUpdate = false;
            if (view == null && image != null)
                NeedsUpdate = true;
            if (view != null && image == null)
                NeedsUpdate = true;
            if (view != null && image != null) {
                // Check for null image or allocation
                if (view.get() == null || view.get().image == null || view.get().image.get() == null) {
                    NeedsUpdate = true;
                } else {
                    try {
                        // Check if allocation is null (image was freed)
                        VImage existingImage = view.get().image.get();
                        if (existingImage == null) {
                            NeedsUpdate = true;
                        } else {
                            // Try to access the Vulkan image handle - will throw if allocation is null
                            try {
                                long existingVkImage = existingImage.image();
                                if (!view.get().isDerivedFrom(image.get()))
                                    NeedsUpdate = true;
                            } catch (NullPointerException e) {
                                // Allocation is null - image was freed, need to update
                                System.err.println("[SharedImageViewTracker] Image allocation is null (freed prematurely), forcing update");
                                NeedsUpdate = true;
                            }
                        }
                    } catch (NullPointerException e) {
                        // Handle any null pointer in the existing image
                        System.err.println("[SharedImageViewTracker] Error accessing existing image view: " + e.getMessage());
                        NeedsUpdate = true;
                    }
                }
            }

            if (NeedsUpdate) {
                // TODO: move this to like a fence free that you pass in via an arg
                if (view != null) {
                    view.close();
                    view = null;
                }

                if (image != null && image.get() != null) {
                    view = VImageView.create(ctx, (VRef) image);
                } else {
                    view = null;
                }
            }
            return view == null ? null : view.addRef();
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }
}

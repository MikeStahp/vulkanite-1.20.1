package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

public class SharedImageViewTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(SharedImageViewTracker.class);

    private final VContext ctx;
    private final Supplier<VRef<VGImage>> supplier;
    private VRef<VImageView> view;

    public SharedImageViewTracker(VContext ctx, Supplier<VRef<VGImage>> imageSupplier) {
        this.supplier = imageSupplier;
        this.ctx = ctx;
    }

    // NOTE: getting the image doesnt invalidate/check for a different image
    public VRef<VImage> getImage() {
        if (view == null) {
            return null;
        }
        try {
            VImageView imageView = view.get();
            if (imageView.image == null) {
                return null;
            }
            // Touch the handle so a freed image is treated like no image instead of
            // crashing during shaderpack/resource reload.
            imageView.image.get().image();
            return imageView.image.addRef();
        } catch (NullPointerException e) {
            invalidateView("Shared image view points to a freed image; invalidating cached view");
        }
        return null;
    }

    public VRef<VImageView> getView() {
        return getView(this.supplier);
    }

    public VRef<VImageView> getView(Supplier<VRef<VGImage>> imageSupplier) {
        VRef<VGImage> image = imageSupplier == null ? null : imageSupplier.get();
        try {
            if (needsUpdate(image)) {
                // TODO: move this to like a fence free that you pass in via an arg
                clearView();

                if (hasLiveAllocation(image)) {
                    try {
                        view = VImageView.create(ctx, (VRef) image);
                    } catch (NullPointerException e) {
                        LOGGER.debug("Skipping image view creation for freed image during resource reload");
                        view = null;
                    }
                } else {
                    view = null;
                }
            }
            return retainView();
        } finally {
            safeClose(image);
        }
    }

    private boolean hasLiveAllocation(VRef<? extends VImage> image) {
        if (image == null) {
            return false;
        }
        try {
            image.get().image();
            return true;
        } catch (NullPointerException e) {
            return false;
        }
    }

    private boolean needsUpdate(VRef<VGImage> image) {
        if (view == null) {
            return image != null;
        }
        if (image == null) {
            return true;
        }
        try {
            VImageView imageView = view.get();
            if (imageView.image == null || !hasLiveAllocation(imageView.image) || !hasLiveAllocation(image)) {
                return true;
            }
            return !imageView.isDerivedFrom(image.get());
        } catch (NullPointerException e) {
            return true;
        }
    }

    private VRef<VImageView> retainView() {
        if (view == null) {
            return null;
        }
        try {
            return view.addRef();
        } catch (NullPointerException e) {
            invalidateView("Cached image view was freed before it could be retained; invalidating");
            return null;
        }
    }

    private void invalidateView(String message) {
        LOGGER.debug(message);
        clearView();
    }

    private void clearView() {
        safeClose(view);
        view = null;
    }

    private static void safeClose(VRef<?> ref) {
        if (ref == null) {
            return;
        }
        try {
            ref.close();
        } catch (NullPointerException ignored) {
            // Stale weak refs can appear during shader/resource reload. Treat as already closed.
        }
    }

    public void destroy() {
        clearView();
    }
}

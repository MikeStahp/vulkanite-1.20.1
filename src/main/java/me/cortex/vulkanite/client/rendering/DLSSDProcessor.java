package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.config.DLSSConfig;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.MinecraftClient;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Radiance-style DLSS/DLSSD coordinator.
 *
 * <p>Radiance treats DLSS as a module with a fixed resource contract. Vulkanite
 * now follows the same shape: ray tracing produces the named DLSS inputs and
 * this class only evaluates NGX against those images.</p>
 */
public class DLSSDProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DLSSDProcessor.class);

    public static final int DEPTH_TYPE_LINEAR = 0;
    public static final int DEPTH_TYPE_HW = 1;
    public static final int ROUGHNESS_MODE_UNPACKED = 0;
    public static final int ROUGHNESS_MODE_PACKED = 1;

    private static final int MAX_INIT_FAILURES = 3;
    private static final int MAX_EVALUATION_FAILURES = 3;

    private final VContext context;
    private final Matrix4f rrProjection = new Matrix4f();
    private final float[] rrWorldToView = new float[16];
    private final float[] rrViewToClip = new float[16];

    private boolean initialized;
    private boolean supported;
    private boolean usingStandardDLSS;
    private boolean temporalHistoryValid;
    private boolean hasValidOutput;
    private long featureHandle;
    private int renderWidth;
    private int renderHeight;
    private int outputWidth;
    private int outputHeight;
    private int initFailures;
    private int consecutiveEvaluationFailures;

    private record ImageBinding(VRef<VImage> image, VRef<VImageView> view) {
        long imageHandle() {
            return image.get().image();
        }

        long viewHandle() {
            return view.get().view;
        }

        int format() {
            return image.get().format;
        }
    }

    public DLSSDProcessor(VContext context) {
        this.context = context;
        this.supported = DLSSBridge.isNativeLibraryLoaded();
        LOGGER.info("DLSS processor created. Native library loaded: {}", supported);
    }

    public void initialize(int outputWidth, int outputHeight) {
        ResolutionScaleManager scaleManager = ResolutionScaleManager.getInstance();
        scaleManager.update(outputWidth, outputHeight);
        initialize(scaleManager.getRenderWidth(), scaleManager.getRenderHeight(),
                scaleManager.getOutputWidth(), scaleManager.getOutputHeight());
    }

    public void initialize(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
        if (!supported) {
            LOGGER.warn("Cannot initialize DLSS - native bridge is not loaded");
            return;
        }
        if (initFailures >= MAX_INIT_FAILURES) {
            return;
        }

        int[] renderSize = ResolutionScaleManager.alignDimensions(renderWidth, renderHeight);
        int[] outputSize = ResolutionScaleManager.alignDimensions(outputWidth, outputHeight);
        renderWidth = renderSize[0];
        renderHeight = renderSize[1];
        outputWidth = outputSize[0];
        outputHeight = outputSize[1];

        if (initialized && matchesDimensions(renderWidth, renderHeight, outputWidth, outputHeight)) {
            return;
        }

        cleanup();

        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;

        long instanceHandle = context.instance.address();
        long physicalDeviceHandle = context.physicalDevice.address();
        long deviceHandle = context.device.address();

        DLSSConfig config = DLSSConfig.load();
        int qualityMode = config.getQualityPreset().getNgxValue();

        LOGGER.info("Initializing Radiance-style DLSS module: render={}x{}, output={}x{}, mode={}",
                renderWidth, renderHeight, outputWidth, outputHeight, config.getDenoiser());

        if (!config.isRayReconstructionEnabled()) {
            initializeStandardDLSS(instanceHandle, physicalDeviceHandle, deviceHandle);
            return;
        }

        featureHandle = DLSSBridge.createDLSSDFeature(
                instanceHandle,
                physicalDeviceHandle,
                deviceHandle,
                renderWidth,
                renderHeight,
                outputWidth,
                outputHeight,
                qualityMode,
                ROUGHNESS_MODE_PACKED,
                DEPTH_TYPE_LINEAR);

        if (featureHandle == 0) {
            initFailures++;
            LOGGER.warn("DLSSD creation failed (attempt {}/{}); trying standard DLSS fallback",
                    initFailures, MAX_INIT_FAILURES);
            initializeStandardDLSS(instanceHandle, physicalDeviceHandle, deviceHandle);
            return;
        }

        initialized = true;
        usingStandardDLSS = false;
        initFailures = 0;
        consecutiveEvaluationFailures = 0;
        temporalHistoryValid = false;
        hasValidOutput = false;
        LOGGER.info("Radiance-style DLSSD initialized");
    }

    private void initializeStandardDLSS(long instanceHandle, long physicalDeviceHandle, long deviceHandle) {
        boolean ok = DLSSBridge.initStandardDLSS(
                instanceHandle,
                physicalDeviceHandle,
                deviceHandle,
                renderWidth,
                renderHeight,
                outputWidth,
                outputHeight);

        if (ok) {
            initialized = true;
            usingStandardDLSS = true;
            initFailures = 0;
            consecutiveEvaluationFailures = 0;
            temporalHistoryValid = false;
            hasValidOutput = false;
            LOGGER.info("Standard DLSS initialized for Radiance-style module");
        } else {
            initialized = false;
            initFailures++;
            LOGGER.error("Standard DLSS initialization failed (attempt {}/{})",
                    initFailures, MAX_INIT_FAILURES);
        }
    }

    public boolean matchesDimensions(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
        return this.renderWidth == renderWidth
                && this.renderHeight == renderHeight
                && this.outputWidth == outputWidth
                && this.outputHeight == outputHeight;
    }

    public boolean canUseConfiguredRenderScale(MinecraftClient mc, DLSSConfig config) {
        return isRuntimeEligible(mc, config);
    }

    public boolean prepareForFrame(MinecraftClient mc, DLSSConfig config, RtxFrameImages images, List<?> outImgs) {
        if (!isRuntimeEligible(mc, config) || images == null || outImgs == null || outImgs.isEmpty()) {
            return false;
        }

        if (!initialized || !matchesDimensions(images.renderWidth(), images.renderHeight(),
                images.outputWidth(), images.outputHeight())) {
            initialize(images.renderWidth(), images.renderHeight(), images.outputWidth(), images.outputHeight());
        }

        return initialized
                && matchesDimensions(images.renderWidth(), images.renderHeight(),
                        images.outputWidth(), images.outputHeight());
    }

    private boolean isRuntimeEligible(MinecraftClient mc, DLSSConfig config) {
        return mc != null
                && mc.world != null
                && mc.currentScreen == null
                && !mc.isPaused()
                && config != null
                && config.isEnabled()
                && !config.isDebugEnabled()
                && supported;
    }

    public VRef<VImage> processFrame(VCmdBuff cmd, RtxFrameImages images, float deltaTime) {
        if (!initialized || !supported || images == null) {
            return null;
        }
        if (!matchesDimensions(images.renderWidth(), images.renderHeight(),
                images.outputWidth(), images.outputHeight())) {
            LOGGER.warn("DLSS dimensions changed without reinitialization; skipping frame");
            return null;
        }

        if (usingStandardDLSS) {
            return processStandardDLSS(cmd, images);
        }

        try {
            ImageBinding radiance = bind(images.radiance(), images.radianceView());
            ImageBinding depth = bind(images.linearDepth(), images.linearDepthView());
            ImageBinding motion = bind(images.motionVector(), images.motionVectorView());
            ImageBinding output = bind(images.processed(), images.processedView());
            ImageBinding diffuse = bind(images.diffuseAlbedoMetallic(), images.diffuseAlbedoMetallicView());
            ImageBinding specular = bind(images.specularAlbedo(), images.specularAlbedoView());
            ImageBinding normalRoughness = bind(images.normalRoughness(), images.normalRoughnessView());
            ImageBinding specularHitDepth = bind(images.specularHitDepth(), images.specularHitDepthView());

            updateMatrices();

            boolean success = DLSSBridge.evaluateDLSSD(
                    cmd.buffer().address(),
                    featureHandle,
                    radiance.viewHandle(),
                    radiance.imageHandle(),
                    radiance.format(),
                    depth.viewHandle(),
                    depth.imageHandle(),
                    depth.format(),
                    motion.viewHandle(),
                    motion.imageHandle(),
                    motion.format(),
                    output.viewHandle(),
                    output.imageHandle(),
                    output.format(),
                    diffuse.viewHandle(),
                    diffuse.imageHandle(),
                    diffuse.format(),
                    specular.viewHandle(),
                    specular.imageHandle(),
                    specular.format(),
                    normalRoughness.viewHandle(),
                    normalRoughness.imageHandle(),
                    normalRoughness.format(),
                    0,
                    0,
                    0,
                    specularHitDepth.viewHandle(),
                    specularHitDepth.imageHandle(),
                    specularHitDepth.format(),
                    JitterManager.getJitterX(),
                    JitterManager.getJitterY(),
                    temporalHistoryValid ? 0 : 1,
                    deltaTime * 1000.0f,
                    rrWorldToView,
                    rrViewToClip,
                    renderWidth,
                    renderHeight);

            if (!success) {
                recordEvaluationFailure("DLSSD");
                return null;
            }

            temporalHistoryValid = true;
            consecutiveEvaluationFailures = 0;
            hasValidOutput = true;
            return images.processed();
        } catch (Exception e) {
            LOGGER.error("DLSSD evaluation failed", e);
            recordEvaluationFailure("DLSSD");
            return null;
        }
    }

    /**
     * Legacy entry point kept for compatibility with older callers. The Radiance
     * path uses {@link #processFrame(VCmdBuff, RtxFrameImages, float)}.
     */
    public VRef<VImage> processFrame(
            VCmdBuff cmd,
            VRef<VImage> noisyInput,
            VRef<VImage> motionVectors,
            VRef<VImage> depth,
            VRef<VImageView>[] gbufferViews,
            float deltaTime) {
        LOGGER.debug("Legacy DLSS processFrame called; returning input because Radiance resources are required");
        return noisyInput;
    }

    private VRef<VImage> processStandardDLSS(VCmdBuff cmd, RtxFrameImages images) {
        try {
            ImageBinding radiance = bind(images.radiance(), images.radianceView());
            ImageBinding depth = bind(images.linearDepth(), images.linearDepthView());
            ImageBinding motion = bind(images.motionVector(), images.motionVectorView());
            ImageBinding output = bind(images.processed(), images.processedView());

            boolean success = DLSSBridge.evaluateStandardDLSS(
                    cmd.buffer().address(),
                    radiance.viewHandle(),
                    radiance.imageHandle(),
                    radiance.format(),
                    depth.viewHandle(),
                    depth.imageHandle(),
                    depth.format(),
                    motion.viewHandle(),
                    motion.imageHandle(),
                    motion.format(),
                    output.viewHandle(),
                    output.imageHandle(),
                    output.format(),
                    JitterManager.getJitterX(),
                    JitterManager.getJitterY());

            if (!success) {
                recordEvaluationFailure("Standard DLSS");
                return null;
            }

            consecutiveEvaluationFailures = 0;
            hasValidOutput = true;
            return images.processed();
        } catch (Exception e) {
            LOGGER.error("Standard DLSS evaluation failed", e);
            recordEvaluationFailure("Standard DLSS");
            return null;
        }
    }

    private void updateMatrices() {
        CapturedRenderingState renderingState = CapturedRenderingState.INSTANCE;
        renderingState.getGbufferModelView().get(rrWorldToView);
        JitterManager.copyWithoutJitter(renderingState.getGbufferProjection(), rrProjection)
                .get(rrViewToClip);
    }

    private static ImageBinding bind(VRef<VImage> image, VRef<VImageView> view) {
        if (image == null || view == null) {
            throw new IllegalStateException("DLSS image contract is incomplete");
        }
        return new ImageBinding(image, view);
    }

    public VRef<VImage> getDepthImage() {
        return null;
    }

    public boolean isSupported() {
        return supported;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean hasValidOutput() {
        return initialized && supported && hasValidOutput;
    }

    public boolean isRayReconstructionEnabled() {
        return initialized && !usingStandardDLSS;
    }

    public boolean isUsingStandardDLSS() {
        return usingStandardDLSS;
    }

    public void resetTemporalState() {
        temporalHistoryValid = false;
        hasValidOutput = false;
        LOGGER.debug("DLSS temporal history reset");
    }

    public DLSSDParameterValidator.ValidationResult validateParameters() {
        if (!initialized) {
            LOGGER.warn("Cannot validate DLSS parameters - processor is not initialized");
            return null;
        }

        DLSSDParameterValidator.DLSSDParams params = new DLSSDParameterValidator.DLSSDParams();
        params.renderWidth = renderWidth;
        params.renderHeight = renderHeight;
        params.outputWidth = outputWidth;
        params.outputHeight = outputHeight;
        params.qualityMode = DLSSConfig.load().getQualityPreset().getNgxValue();
        params.roughnessMode = ROUGHNESS_MODE_PACKED;
        params.depthType = DEPTH_TYPE_LINEAR;
        params.jitterX = JitterManager.getJitterX();
        params.jitterY = JitterManager.getJitterY();
        params.reset = temporalHistoryValid ? 0 : 1;
        params.deltaTimeMs = 16.667f;
        params.colorFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
        params.depthFormat = VK_FORMAT_R16_SFLOAT;
        params.motionVectorsFormat = VK_FORMAT_R16G16_SFLOAT;
        params.outputFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
        params.diffuseAlbedoFormat = VK_FORMAT_R8G8B8A8_UNORM;
        params.specularAlbedoFormat = VK_FORMAT_R8G8B8A8_UNORM;
        params.normalsFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
        params.featureHandle = featureHandle;
        return DLSSDParameterValidator.getInstance().validateAll(params);
    }

    public void cleanup() {
        if (featureHandle != 0) {
            try {
                DLSSBridge.releaseDLSSDFeature(featureHandle);
            } catch (Exception e) {
                LOGGER.warn("Failed to release DLSSD feature", e);
            }
            featureHandle = 0;
        }
        if (usingStandardDLSS) {
            try {
                DLSSBridge.destroyStandardDLSS(context.device.address());
            } catch (Exception e) {
                LOGGER.warn("Failed to destroy standard DLSS", e);
            }
        }
        try {
            DLSSBridge.shutdownNGX();
        } catch (Exception e) {
            LOGGER.warn("Failed to shut down NGX", e);
        }

        initialized = false;
        usingStandardDLSS = false;
        temporalHistoryValid = false;
        hasValidOutput = false;
        consecutiveEvaluationFailures = 0;
    }

    private void recordEvaluationFailure(String pathName) {
        temporalHistoryValid = false;
        hasValidOutput = false;
        consecutiveEvaluationFailures++;

        if (consecutiveEvaluationFailures >= MAX_EVALUATION_FAILURES) {
            initialized = false;
            initFailures = MAX_INIT_FAILURES;
            JitterManager.setDLSSActive(false);
            LOGGER.error("{} evaluation failed {} consecutive times; disabling DLSS",
                    pathName, consecutiveEvaluationFailures);
        } else {
            LOGGER.warn("{} evaluation failed ({}/{})",
                    pathName, consecutiveEvaluationFailures, MAX_EVALUATION_FAILURES);
        }
    }
}

package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.config.DLSSConfig;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

        List<VRef<?>> views = new ArrayList<>();
        try {
            VRef<VImageView> radianceView = createView(views, images.radiance());
            VRef<VImageView> depthView = createView(views, images.linearDepth());
            VRef<VImageView> motionView = createView(views, images.motionVector());
            VRef<VImageView> outputView = createView(views, images.processed());
            VRef<VImageView> diffuseView = createView(views, images.diffuseAlbedoMetallic());
            VRef<VImageView> specularView = createView(views, images.specularAlbedo());
            VRef<VImageView> normalRoughnessView = createView(views, images.normalRoughness());
            VRef<VImageView> specularHitDepthView = createView(views, images.specularHitDepth());

            updateMatrices();

            boolean success = DLSSBridge.evaluateDLSSD(
                    cmd.buffer().address(),
                    featureHandle,
                    radianceView.get().view,
                    images.radiance().get().image(),
                    images.radiance().get().format,
                    depthView.get().view,
                    images.linearDepth().get().image(),
                    images.linearDepth().get().format,
                    motionView.get().view,
                    images.motionVector().get().image(),
                    images.motionVector().get().format,
                    outputView.get().view,
                    images.processed().get().image(),
                    images.processed().get().format,
                    diffuseView.get().view,
                    images.diffuseAlbedoMetallic().get().image(),
                    images.diffuseAlbedoMetallic().get().format,
                    specularView.get().view,
                    images.specularAlbedo().get().image(),
                    images.specularAlbedo().get().format,
                    normalRoughnessView.get().view,
                    images.normalRoughness().get().image(),
                    images.normalRoughness().get().format,
                    0,
                    0,
                    0,
                    specularHitDepthView.get().view,
                    images.specularHitDepth().get().image(),
                    images.specularHitDepth().get().format,
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
        } finally {
            closeAll(views);
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
        List<VRef<?>> views = new ArrayList<>();
        try {
            VRef<VImageView> radianceView = createView(views, images.radiance());
            VRef<VImageView> depthView = createView(views, images.linearDepth());
            VRef<VImageView> motionView = createView(views, images.motionVector());
            VRef<VImageView> outputView = createView(views, images.processed());

            boolean success = DLSSBridge.evaluateStandardDLSS(
                    cmd.buffer().address(),
                    radianceView.get().view,
                    images.radiance().get().image(),
                    images.radiance().get().format,
                    depthView.get().view,
                    images.linearDepth().get().image(),
                    images.linearDepth().get().format,
                    motionView.get().view,
                    images.motionVector().get().image(),
                    images.motionVector().get().format,
                    outputView.get().view,
                    images.processed().get().image(),
                    images.processed().get().format,
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
        } finally {
            closeAll(views);
        }
    }

    private void updateMatrices() {
        CapturedRenderingState renderingState = CapturedRenderingState.INSTANCE;
        renderingState.getGbufferModelView().get(rrWorldToView);
        JitterManager.copyWithoutJitter(renderingState.getGbufferProjection(), rrProjection)
                .get(rrViewToClip);
    }

    private VRef<VImageView> createView(List<VRef<?>> views, VRef<VImage> image) {
        VRef<VImageView> view = VImageView.create(context, image);
        views.add(view);
        return view;
    }

    private static void closeAll(List<VRef<?>> refs) {
        for (VRef<?> ref : refs) {
            if (ref != null) {
                ref.close();
            }
        }
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

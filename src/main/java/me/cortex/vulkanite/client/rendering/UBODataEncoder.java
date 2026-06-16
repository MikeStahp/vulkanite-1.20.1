package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.mixin.iris.MixinCelestialUniforms;
import me.cortex.vulkanite.mixin.iris.MixinCommonUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.render.Camera;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;

/**
 * Encodes uniform buffer data for ray tracing shaders.
 * Thread-local storage is used to avoid per-frame allocations.
 *
 * NVIDIA DLSS JITTER HANDLING:
 * - Jitter values are passed to DLSS via InJitterOffsetX/Y parameters
 * - Motion vectors represent pure geometric motion WITHOUT jitter.
 * - DLSS handles jitter compensation internally using InJitterOffsetX/Y parameters.
 * - The prevViewProj matrix passed to shaders is UNJITTERED for correct motion vector calculation.
 * - The ray tracing shader uses unjittered projection for corner vectors
 * to avoid visible jitter/shaking in the output
 *
 * CRITICAL: prevViewProj stores the UNJITTERED view-projection matrix.
 * Motion vectors are calculated as: curUnjitteredPos - prevUnjitteredPos
 * This ensures motion vectors represent pure geometric motion without jitter effects.
 * DLSS receives jitter offsets separately and handles sub-pixel sampling internally.
 *
 * DLSS QUALITY MODE SCALING:
 * - When DLSS is enabled with a quality preset, render resolution is scaled
 *   (e.g., Quality mode = 66.67% of output resolution)
 * - Jitter calculations use the SCALED render resolution from JitterManager
 * - Corner vectors are computed for the render resolution, not output resolution
 * - The ResolutionScaleManager provides consistent scaled dimensions across all components
 */
public final class UBODataEncoder {

    // Thread-local reusable objects to avoid per-frame allocations
    private static final ThreadLocal<Vector3f> TL_VECTOR = ThreadLocal.withInitial(Vector3f::new);
    private static final ThreadLocal<Matrix4f> TL_INV_PROJ = ThreadLocal.withInitial(Matrix4f::new);
    private static final ThreadLocal<Matrix4f> TL_INV_PROJ_UNJITTERED = ThreadLocal.withInitial(Matrix4f::new);
    private static final ThreadLocal<Matrix4f> TL_INV_VIEW = ThreadLocal.withInitial(Matrix4f::new);
    private static final ThreadLocal<Matrix4f> TL_TEMP_VIEW = ThreadLocal.withInitial(Matrix4f::new);

    private static final ThreadLocal<Matrix4f> TL_PREV_VIEW_PROJ = ThreadLocal.withInitial(Matrix4f::new);
    private static final ThreadLocal<Matrix4f> TL_CUR_VIEW_PROJ = ThreadLocal.withInitial(Matrix4f::new);
    private static final ThreadLocal<Boolean> TL_HAS_HISTORY = ThreadLocal.withInitial(() -> false);

    private UBODataEncoder() {
    } // Prevent instantiation

    /**
     * Encodes camera matrices, celestial positions, and other uniform data into the
     * buffer.
     *
     * @param bb                The byte buffer to write to (must have at least 256
     *                          bytes)
     * @param camera            The current camera
     * @param celestialUniforms Celestial uniforms accessor
     */
    public static void encode(ByteBuffer bb, Camera camera, MixinCelestialUniforms celestialUniforms) {
        Vector3f tmpv3 = TL_VECTOR.get();
        Matrix4f invProjMatrix = TL_INV_PROJ.get();
        Matrix4f invProjUnjittered = TL_INV_PROJ_UNJITTERED.get();
        Matrix4f invViewMatrix = TL_INV_VIEW.get();
        Matrix4f tempView = TL_TEMP_VIEW.get();
        Matrix4f curViewProj = TL_CUR_VIEW_PROJ.get();
        Matrix4f prevViewProj = TL_PREV_VIEW_PROJ.get();
        boolean hasHistory = TL_HAS_HISTORY.get();

        // NOTE: prevViewProj is a ThreadLocal that persists between frames.
        // It contains the UNJITTERED matrix from the PREVIOUS frame, which is needed
        // for correct motion vector calculation. The update to prevViewProj happens at
        // the END of this method, after we've encoded the current frame's data.

        // Get jitter values FIRST - needed for unjittering the projection
        float curJitterX = JitterManager.getJitterX();
        float curJitterY = JitterManager.getJitterY();
        int renderWidth = JitterManager.getCurrentRenderWidth();
        int renderHeight = JitterManager.getCurrentRenderHeight();
       
        // RESOLUTION SCALE FIX: Get output dimensions for corner vector scaling.
        // The projection matrix from Iris is computed for OUTPUT resolution (e.g., 1920x1080),
        // but ray tracing operates at RENDER resolution (e.g., 1280x720 for Quality mode).
        // Corner vectors must be scaled to match the render resolution.
        ResolutionScaleManager scaleManager = ResolutionScaleManager.getInstance();
        int outputWidth = scaleManager.getOutputWidth();
        int outputHeight = scaleManager.getOutputHeight();
       
        // Compute an unjittered projection for RT ray corners and DLSS guide buffers.
        // JitterManager owns the sign/resolution math; shaders only consume the
        // resulting matrices and the explicit pixel-space jitter value.
        Matrix4f gbufferProjection = CapturedRenderingState.INSTANCE.getGbufferProjection();
        Matrix4f unjitteredProjection = JitterManager.copyWithoutJitter(gbufferProjection, invProjUnjittered);
        invProjUnjittered.invert(invProjMatrix);

        // Compute view matrix: transforms from ABSOLUTE world space to view space.
        // GbufferModelView transforms from camera-relative world space to view space.
        // Adding translate(-cameraPos) converts from absolute world space to camera-relative first.
        // NOTE: prevViewProj MUST work in absolute world space because camera-relative
        // coordinates change each frame as the camera moves. The SHADER is responsible
        // for converting camera-relative worldPos to absolute before reprojecting.
        tempView.set(CapturedRenderingState.INSTANCE.getGbufferModelView())
            .translate(camera.getPos().toVector3f().negate());

        // Calculate ViewProj for DLSS motion-vector reprojection.
        // Motion vectors must stay geometric; NGX receives jitter separately.
        curViewProj.set(unjitteredProjection)
                .mul(tempView);

        // Build the current view-projection once from the Java-owned clean matrix.
        Matrix4f unjitteredCurViewProj = new Matrix4f(curViewProj);

        // CRITICAL FIX: prevViewProj stores the UNJITTERED view-projection matrix.
        // Motion vectors represent pure geometric motion without jitter effects.
        // DLSS receives jitter offsets separately and handles sub-pixel sampling internally.
        //
        // For the first frame (no history), use the current frame's unjittered matrix as a starting point.
        if (!hasHistory) {
        	prevViewProj.set(unjitteredCurViewProj);
        	TL_HAS_HISTORY.set(true);
        }

        tempView.invert(invViewMatrix);

 // NDC COORDINATES FOR CORNER VECTORS
// Use full NDC range [-1, 1] - no scaling needed.
//
// Why this works:
// 1. The projection matrix from Iris is computed for output resolution with NDC [-1, 1]
// 2. The inverse projection transforms NDC [-1, 1] to view-space frustum corners
// 3. The shader interpolates [0, 1] across these corners based on render-resolution pixel coordinates
// 4. This naturally produces correct rays - each render pixel maps to the appropriate portion of the full frustum
// 5. The G-buffer is at render resolution, and the ray tracing launch size matches, so UV coordinates align perfectly
//
// The previous scaling to [-0.667, 0.667] was INCORRECT and caused projection errors.
float ndcMinX = -1.0f;
float ndcMaxX = 1.0f;
float ndcMinY = -1.0f;
float ndcMaxY = 1.0f;

// Encode inverse projection corner vectors (offsets 0-48)
// Use UNJITTERED corners with full NDC range [-1, 1].
// The shader uses these to compute ray directions from pixel positions; jitter
// should not be projected onto the final screen-space RT image.
Vector3f corner0 = invProjMatrix.transformProject(ndcMinX, ndcMinY, 0, 1, new Vector3f());
Vector3f corner1 = invProjMatrix.transformProject(ndcMaxX, ndcMinY, 0, 1, new Vector3f());
Vector3f corner2 = invProjMatrix.transformProject(ndcMinX, ndcMaxY, 0, 1, new Vector3f());
Vector3f corner3 = invProjMatrix.transformProject(ndcMaxX, ndcMaxY, 0, 1, new Vector3f());

// Log corner vector info every 300 frames
int frameCounterDiag = SystemTimeUniforms.COUNTER.getAsInt();
if (frameCounterDiag % 300 == 0) {
System.out.println("[UBODataEncoder] Frame " + frameCounterDiag
+ ": Using full NDC range [-1, 1]"
+ ", render=" + renderWidth + "x" + renderHeight
+ ", output=" + outputWidth + "x" + outputHeight
+ ", corners: c0=" + corner0 + ", c1=" + corner1);
System.out.println("[JITTER FRAME DIAG] NDC range: [-1, 1] x [-1, 1] (full range)");
System.out.println("[JITTER FRAME DIAG] Corner vectors (unjittered, full NDC):");
System.out.println("[JITTER FRAME DIAG] c0 (minX,minY): (" + corner0.x + ", " + corner0.y + ", " + corner0.z + ")");
System.out.println("[JITTER FRAME DIAG] c1 (maxX,minY): (" + corner1.x + ", " + corner1.y + ", " + corner1.z + ")");
System.out.println("[JITTER FRAME DIAG] c2 (minX,maxY): (" + corner2.x + ", " + corner2.y + ", " + corner2.z + ")");
System.out.println("[JITTER FRAME DIAG] c3 (maxX,maxY): (" + corner3.x + ", " + corner3.y + ", " + corner3.z + ")");
System.out.println("[JITTER FRAME DIAG] Jitter: cur=(" + curJitterX + ", " + curJitterY + ")");
}

// Write corner vectors to UBO
 corner0.get(bb);
 corner1.get(4 * Float.BYTES, bb);
 corner2.get(8 * Float.BYTES, bb);
 corner3.get(12 * Float.BYTES, bb);

        // Encode inverse view matrix (offset 64)
        invViewMatrix.get(Float.BYTES * 16, bb);

        // Encode celestial positions (offsets 128, 144)
        celestialUniforms.invokeGetSunPosition().get(Float.BYTES * 32, bb);
        celestialUniforms.invokeGetMoonPosition().get(Float.BYTES * 36, bb);

        // Encode frame counter (offset 160)
        bb.putInt(Float.BYTES * 40, SystemTimeUniforms.COUNTER.getAsInt());

        // Encode flags (offset 164)
        bb.putInt(Float.BYTES * 41, MixinCommonUniforms.invokeIsEyeInWater() & 3);

        // Encode padding to reach 16-byte alignment (offset 168)
        bb.putLong(Float.BYTES * 42, 0L);

        // Encode previous World-to-Clip matrix (offset 176)
        prevViewProj.get(Float.BYTES * 44, bb);

        // Encode Jitter Data: curX, curY, prevX, prevY (offset 240)
        // These values are for DLSS internal use only - ray tracing does NOT use them
        // for primary ray direction (G-buffer is unjittered)
        // NOTE: curJitterX/Y are already declared above for unjittering calculation
        float prevJitterX = JitterManager.getPrevJitterX();
        float prevJitterY = JitterManager.getPrevJitterY();
        bb.putFloat(Float.BYTES * 60, curJitterX);
        bb.putFloat(Float.BYTES * 61, curJitterY);
        bb.putFloat(Float.BYTES * 62, prevJitterX);
        bb.putFloat(Float.BYTES * 63, prevJitterY);

        // Encode current unjittered World-to-Clip matrix (offset 256).
        unjitteredCurViewProj.get(Float.BYTES * 64, bb);

        // DIAGNOSTIC: Log jitter values every 300 frames to reduce spam
        // Note: scaleManager is already declared above for corner vector scaling
        int frameCounterLog = SystemTimeUniforms.COUNTER.getAsInt();
        if (frameCounterLog % 300 == 0) {
        	System.out.println("[UBODataEncoder] Frame " + frameCounterLog +
        	": jitter=(" + curJitterX + ", " + curJitterY + ")" +
        	", renderRes=" + renderWidth + "x" + renderHeight +
        	", outputRes=" + outputWidth + "x" + outputHeight +
        	", scale=" + scaleManager.getScale() + ", dlssEnabled=" + scaleManager.isDLSSEnabled());
        }

        bb.rewind();

        // CRITICAL: Store the UNJITTERED current frame's view-projection matrix.
        // This becomes the "previous frame's matrix" for motion vector calculation.
        // Motion vectors represent pure geometric motion; DLSS handles jitter compensation internally.
        prevViewProj.set(unjitteredCurViewProj);
    }

    public static void resetTemporalHistory() {
        TL_PREV_VIEW_PROJ.get().identity();
        TL_HAS_HISTORY.set(false);
    }
}

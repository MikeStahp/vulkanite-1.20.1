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
 * - Motion vectors do NOT include jitter - they represent pure geometric motion
 * - DLSS handles jitter compensation internally using the provided jitter offsets
 * - The ray tracing shader uses unjittered projection for corner vectors
 *   to avoid visible jitter/shaking in the output
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
        // It contains the JITTERED matrix from the PREVIOUS frame, which is needed
        // for correct temporal reprojection. The update to prevViewProj happens at
        // the END of this method, after we've encoded the current frame's data.

        // Get jitter values FIRST - needed for unjittering the projection
        float curJitterX = JitterManager.getJitterX();
        float curJitterY = JitterManager.getJitterY();
        int renderWidth = JitterManager.getCurrentRenderWidth();
        int renderHeight = JitterManager.getCurrentRenderHeight();

        // Compute inverse projection (this is the jittered projection for current frame rendering)
        CapturedRenderingState.INSTANCE.getGbufferProjection().invert(invProjMatrix);

        // CRITICAL FIX: Compute UNJITTERED inverse projection for corner vectors.
        // The projection matrix from Iris is JITTERED (via MixinGameRenderer.applyJitter).
        // The ray tracing shader does NOT apply jitter to primary rays (jitteredP = rayP),
        // so the corner vectors must be UNJITTERED to avoid visible jitter/shaking.
        // DLSS receives jitter offsets separately and handles sub-pixel sampling internally.
        invProjUnjittered.set(CapturedRenderingState.INSTANCE.getGbufferProjection());
        if (renderWidth > 0 && renderHeight > 0 && (curJitterX != 0 || curJitterY != 0)) {
            float ndcJitterX = (curJitterX * 2.0f) / renderWidth;
            float ndcJitterY = (curJitterY * 2.0f) / renderHeight;
            invProjUnjittered.m20(invProjUnjittered.m20() - ndcJitterX);
            invProjUnjittered.m21(invProjUnjittered.m21() - ndcJitterY);
        }
        invProjUnjittered.invert();

        // Compute view matrix: transforms from ABSOLUTE world space to view space.
        // GbufferModelView transforms from camera-relative world space to view space.
        // Adding translate(-cameraPos) converts from absolute world space to camera-relative first.
        // NOTE: prevViewProj MUST work in absolute world space because camera-relative
        // coordinates change each frame as the camera moves. The SHADER is responsible
        // for converting camera-relative worldPos to absolute before reprojecting.
        tempView.set(CapturedRenderingState.INSTANCE.getGbufferModelView())
            .translate(camera.getPos().toVector3f().negate());

        // Calculate ViewProj for current frame rendering (JITTERED).
        // CapturedRenderingState.INSTANCE.getGbufferProjection() contains the jittered projection.
        curViewProj.set(CapturedRenderingState.INSTANCE.getGbufferProjection())
        	.mul(tempView);
       
        // NVIDIA DLSS REQUIREMENT: Motion vectors must NOT include jitter.
        // The prevViewProj matrix stored here is used for motion vector calculation.
        // Since motion vectors represent pure geometric motion (no jitter),
        // we need to store the UNJITTERED view-projection matrix.
        //
        // Jitter values are passed separately to DLSS via InJitterOffsetX/Y parameters.
        // DLSS handles jitter compensation internally - we do NOT subtract jitter from motion vectors.
        //
        // Compute unjittered view-projection for motion vector calculation
        Matrix4f unjitteredProj = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection());
        if (renderWidth > 0 && renderHeight > 0 && (curJitterX != 0 || curJitterY != 0)) {
        	float ndcJitterX = (curJitterX * 2.0f) / renderWidth;
        	float ndcJitterY = (curJitterY * 2.0f) / renderHeight;
        	unjitteredProj.m20(unjitteredProj.m20() - ndcJitterX);
        	unjitteredProj.m21(unjitteredProj.m21() - ndcJitterY);
        }
        Matrix4f unjitteredViewProj = new Matrix4f(unjitteredProj).mul(tempView);
       
 if (!hasHistory) {
 	prevViewProj.set(unjitteredViewProj);
 	TL_HAS_HISTORY.set(true);
 }

        tempView.invert(invViewMatrix);

 // Encode inverse projection corner vectors (offsets 0-48)
 // DIAGNOSTIC: Log both jittered and unjittered corner vectors to verify the issue.
 // When camera is stationary, corner vectors should be STABLE (not change every frame).
 // If they change with jitter, that's the root cause of camera shake.
 Vector3f corner0_jittered = invProjMatrix.transformProject(-1, -1, 0, 1, tmpv3);
 Vector3f corner1_jittered = invProjMatrix.transformProject(+1, -1, 0, 1, tmpv3);
 Vector3f corner2_jittered = invProjMatrix.transformProject(-1, +1, 0, 1, tmpv3);
 Vector3f corner3_jittered = invProjMatrix.transformProject(+1, +1, 0, 1, tmpv3);

 // Compute unjittered corners for comparison
 Vector3f corner0_unjittered = invProjUnjittered.transformProject(-1, -1, 0, 1, new Vector3f());
 Vector3f corner1_unjittered = invProjUnjittered.transformProject(+1, -1, 0, 1, new Vector3f());
 Vector3f corner2_unjittered = invProjUnjittered.transformProject(-1, +1, 0, 1, new Vector3f());
 Vector3f corner3_unjittered = invProjUnjittered.transformProject(+1, +1, 0, 1, new Vector3f());

 // Log corner vector differences every 60 frames to verify the issue
 int frameCounterDiag = SystemTimeUniforms.COUNTER.getAsInt();
 if (frameCounterDiag % 60 == 0) {
     float jitteredLen0 = corner0_jittered.length();
     float unjitteredLen0 = corner0_unjittered.length();
     float diff0 = Math.abs(jitteredLen0 - unjitteredLen0);
     System.out.println("[UBODataEncoder DIAGNOSTIC] Frame " + frameCounterDiag);
     System.out.println("  Jitter: (" + curJitterX + ", " + curJitterY + ")");
     System.out.println("  Corner0 JITTERED:   len=" + jitteredLen0 + ", vec=" + corner0_jittered);
     System.out.println("  Corner0 UNJITTERED: len=" + unjitteredLen0 + ", vec=" + corner0_unjittered);
     System.out.println("  Difference in length: " + diff0 + " (should be ~0 if jitter is handled correctly)");
     System.out.println("  If difference > 0.001, corner vectors are changing with jitter = CAUSE OF SHAKE");
 }

        // Use UNJITTERED corners - jitter is already in projection matrix, DLSS handles compensation
        corner0_unjittered.get(bb);
        corner1_unjittered.get(4 * Float.BYTES, bb);
        corner2_unjittered.get(8 * Float.BYTES, bb);
        corner3_unjittered.get(12 * Float.BYTES, bb);

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

        // DIAGNOSTIC: Log jitter values every 300 frames to reduce spam
        int frameCounterLog = SystemTimeUniforms.COUNTER.getAsInt();
        if (frameCounterLog % 300 == 0) {
            System.out.println("[UBODataEncoder] Frame " + frameCounterLog +
                    ": jitter=(" + curJitterX + ", " + curJitterY + ")" +
                    ", renderRes=" + renderWidth + "x" + renderHeight);
        }

        bb.rewind();
       
 // Prepare for next frame: Store the UNJITTERED view-projection matrix.
 // NVIDIA DLSS REQUIREMENT: Motion vectors must NOT include jitter.
 // By storing the unjittered matrix, motion vectors will represent
 // pure geometric motion without jitter effects.
 // Jitter is passed separately to DLSS via InJitterOffsetX/Y parameters.
 prevViewProj.set(unjitteredViewProj);
    }

    public static void resetTemporalHistory() {
        TL_PREV_VIEW_PROJ.get().identity();
        TL_HAS_HISTORY.set(false);
    }
}

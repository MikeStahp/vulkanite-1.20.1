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
 * IMPORTANT: Jitter values are provided for DLSS internal use only.
 * The ray tracing shader does NOT apply jitter to primary rays,
 * as the G-buffer is rendered by Iris without jitter.
 */
public final class UBODataEncoder {

    // Thread-local reusable objects to avoid per-frame allocations
    private static final ThreadLocal<Vector3f> TL_VECTOR = ThreadLocal.withInitial(Vector3f::new);
    private static final ThreadLocal<Matrix4f> TL_INV_PROJ = ThreadLocal.withInitial(Matrix4f::new);
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
        Matrix4f invViewMatrix = TL_INV_VIEW.get();
        Matrix4f tempView = TL_TEMP_VIEW.get();
        Matrix4f curViewProj = TL_CUR_VIEW_PROJ.get();
        Matrix4f prevViewProj = TL_PREV_VIEW_PROJ.get();
        boolean hasHistory = TL_HAS_HISTORY.get();

        // NOTE: prevViewProj is a ThreadLocal that persists between frames.
        // It contains the matrix from the PREVIOUS frame, which is what we need for motion vectors.
        // The update to prevViewProj happens at the END of this method, after we've encoded
        // the current frame's data, preparing it for the NEXT frame.

        // Compute inverse projection
        CapturedRenderingState.INSTANCE.getGbufferProjection().invert(invProjMatrix);

        // Compute view matrix: transforms from ABSOLUTE world space to view space.
        // GbufferModelView transforms from camera-relative world space to view space.
        // Adding translate(-cameraPos) converts from absolute world space to camera-relative first.
        // NOTE: prevViewProj MUST work in absolute world space because camera-relative
        // coordinates change each frame as the camera moves. The SHADER is responsible
        // for converting camera-relative worldPos to absolute before reprojecting.
        tempView.set(CapturedRenderingState.INSTANCE.getGbufferModelView())
            .translate(camera.getPos().toVector3f().negate());
    
        // Calculate ViewProj for motion vectors.
        // NOTE: Jitter IS applied to the projection matrix for DLSS support.
        // CapturedRenderingState.INSTANCE.getGbufferProjection() contains the jittered projection.
        // The shader compensates for this jitter to produce pure geometric motion vectors.
        curViewProj.set(CapturedRenderingState.INSTANCE.getGbufferProjection())
            .mul(tempView);

        if (!hasHistory) {
            prevViewProj.set(curViewProj);
            TL_HAS_HISTORY.set(true);
        }

        tempView.invert(invViewMatrix);

        // Encode inverse projection corner vectors (offsets 0-48)
        invProjMatrix.transformProject(-1, -1, 0, 1, tmpv3).get(bb);
        invProjMatrix.transformProject(+1, -1, 0, 1, tmpv3).get(4 * Float.BYTES, bb);
        invProjMatrix.transformProject(-1, +1, 0, 1, tmpv3).get(8 * Float.BYTES, bb);
        invProjMatrix.transformProject(+1, +1, 0, 1, tmpv3).get(12 * Float.BYTES, bb);

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
        float curJitterX = JitterManager.getJitterX();
        float curJitterY = JitterManager.getJitterY();
        float prevJitterX = JitterManager.getPrevJitterX();
        float prevJitterY = JitterManager.getPrevJitterY();
        bb.putFloat(Float.BYTES * 60, curJitterX);
        bb.putFloat(Float.BYTES * 61, curJitterY);
        bb.putFloat(Float.BYTES * 62, prevJitterX);
        bb.putFloat(Float.BYTES * 63, prevJitterY);
    
        // DIAGNOSTIC: Log jitter values every 60 frames to validate motion vector compensation
        int frameCounter = SystemTimeUniforms.COUNTER.getAsInt();
        if (frameCounter % 60 == 0) {
            System.out.println("[UBODataEncoder DIAGNOSTIC] Frame " + frameCounter +
                ": curJitter=(" + curJitterX + ", " + curJitterY + ")" +
                ", prevJitter=(" + prevJitterX + ", " + prevJitterY + ")" +
                ", delta=(" + (curJitterX - prevJitterX) + ", " + (curJitterY - prevJitterY) + ")");
        }
    
        bb.rewind();
    
        // Prepare for next frame
        prevViewProj.set(curViewProj);
    }

    public static void resetTemporalHistory() {
        TL_PREV_VIEW_PROJ.get().identity();
        TL_HAS_HISTORY.set(false);
    }
}

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
 */
public final class UBODataEncoder {

    // Thread-local reusable objects to avoid per-frame allocations
    private static final ThreadLocal<Vector3f> TL_VECTOR = ThreadLocal.withInitial(Vector3f::new);
    private static final ThreadLocal<Matrix4f> TL_INV_PROJ = ThreadLocal.withInitial(Matrix4f::new);
    private static final ThreadLocal<Matrix4f> TL_INV_VIEW = ThreadLocal.withInitial(Matrix4f::new);
    private static final ThreadLocal<Matrix4f> TL_TEMP_VIEW = ThreadLocal.withInitial(Matrix4f::new);

    private UBODataEncoder() {
    } // Prevent instantiation

    /**
     * Encodes camera matrices, celestial positions, and other uniform data into the
     * buffer.
     *
     * @param bb                The byte buffer to write to (must have at least 168
     *                          bytes)
     * @param camera            The current camera
     * @param celestialUniforms Celestial uniforms accessor
     */
    public static void encode(ByteBuffer bb, Camera camera, MixinCelestialUniforms celestialUniforms) {
        Vector3f tmpv3 = TL_VECTOR.get();
        Matrix4f invProjMatrix = TL_INV_PROJ.get();
        Matrix4f invViewMatrix = TL_INV_VIEW.get();
        Matrix4f tempView = TL_TEMP_VIEW.get();

        // Compute inverse projection
        CapturedRenderingState.INSTANCE.getGbufferProjection().invert(invProjMatrix);

        // Compute inverse view (reuse tempView to avoid allocation)
        tempView.set(CapturedRenderingState.INSTANCE.getGbufferModelView())
                .translate(camera.getPos().toVector3f().negate())
                .invert(invViewMatrix);

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

        bb.rewind();
    }
}

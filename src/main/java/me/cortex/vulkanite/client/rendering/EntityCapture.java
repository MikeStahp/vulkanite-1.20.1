package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import me.cortex.vulkanite.mixin.minecraft.ParticleManagerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures entity renderers into a stable RTX vertex format.
 *
 * <p>Radiance does not upload vanilla's render-layer-dependent vertex formats
 * directly. It routes every quad through a canonical PBR consumer and keeps the
 * entity transform and material texture alongside the geometry. Vulkanite uses
 * the same model, with Vulkan descriptor indices replacing Radiance's native
 * texture IDs.</p>
 */
public final class EntityCapture implements AutoCloseable {
    public static final int VERTEX_STRIDE = 64;
    public static final int MAX_TEXTURES = 256;

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityCapture.class);
    private final TextureRegistry textures = new TextureRegistry();

    public Frame capture(float tickDelta, ClientWorld world) {
        return capture(tickDelta, world, Integer.MAX_VALUE);
    }

    public Frame capture(float tickDelta, ClientWorld world, int maxEntities) {
        return capture(tickDelta, world, null, maxEntities, 0, false);
    }

    public Frame capture(float tickDelta, ClientWorld world, Camera camera, int maxEntities,
            int maxParticles, boolean captureParticles) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (world == null || client.worldRenderer == null) {
            return null;
        }

        LevelRendererAccessor renderer = (LevelRendererAccessor) client.worldRenderer;
        textures.beginFrame(client);
        List<EntityRenderData> entities = new ArrayList<>();

        if (maxEntities > 0) {
            for (var entity : world.getEntities()) {
                if (entities.size() >= maxEntities) {
                    break;
                }
                CanonicalProvider provider = new CanonicalProvider(textures);
                try {
                    renderer.invokeRenderEntity(entity, 0.0, 0.0, 0.0, tickDelta,
                            new MatrixStack(), provider);
                    List<Geometry> geometries = provider.finish();
                    if (!geometries.isEmpty()) {
                        entities.add(new EntityRenderData(
                                MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()),
                                MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()),
                                MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ()),
                                geometries,
                                true));
                    }
                } catch (Exception e) {
                    LOGGER.debug("Skipping RTX entity capture for {}", entity.getType(), e);
                }
            }
        }

        if (captureParticles && camera != null && maxParticles > 0) {
            captureParticles(client, textures, entities, camera, tickDelta, maxParticles);
        }

        if (entities.isEmpty()) {
            return null;
        }
        return new Frame(entities, textures.retainImages());
    }

    public record Geometry(ByteBuffer vertices, int vertexCount, Identifier textureId) {
        public int quadCount() {
            return vertexCount / 4;
        }
    }

    public record EntityRenderData(double x, double y, double z, List<Geometry> geometries, boolean cacheable) {
    }

    @Override
    public void close() {
        textures.close();
    }

    public static final class Frame implements AutoCloseable {
        private final List<EntityRenderData> entities;
        private final List<VRef<VGImage>> textures;

        private Frame(List<EntityRenderData> entities, List<VRef<VGImage>> textures) {
            this.entities = List.copyOf(entities);
            this.textures = List.copyOf(textures);
        }

        public List<EntityRenderData> entities() {
            return entities;
        }

        public List<VRef<VGImage>> textureRefs() {
            return textures;
        }

        @Override
        public void close() {
            for (VRef<VGImage> texture : textures) {
                texture.close();
            }
        }
    }

    private static final class TextureRegistry implements AutoCloseable {
        private final Map<Identifier, Integer> indices = new LinkedHashMap<>();
        private final List<VRef<VGImage>> images = new ArrayList<>();

        private MinecraftClient client;

        private void beginFrame(MinecraftClient client) {
            this.client = client;
        }

        TextureBinding resolve(RenderLayer layer) {
            Identifier id = MissingSprite.getMissingSpriteId();
            if (layer instanceof RenderLayer.MultiPhase multiPhase) {
                id = multiPhase.phases.texture.getId().orElse(id);
            }

            return resolveTexture(id);
        }

        TextureBinding resolveTexture(Identifier id) {
            Integer existing = indices.get(id);
            if (existing != null) {
                return new TextureBinding(existing, id);
            }
            if (images.size() >= MAX_TEXTURES) {
                return resolveMissingTexture();
            }

            AbstractTexture texture = client.getTextureManager().getTexture(id);
            VRef<VGImage> shared = ((IVGImage) texture).getVGImage();
            try {
                if (shared == null) {
                    if (!id.equals(MissingSprite.getMissingSpriteId())) {
                        return resolveMissingTexture();
                    }
                    return null;
                }
                shared.get().image();

                int index = images.size();
                indices.put(id, index);
                images.add(shared);
                shared = null;
                return new TextureBinding(index, id);
            } catch (NullPointerException e) {
                if (!id.equals(MissingSprite.getMissingSpriteId())) {
                    return resolveMissingTexture();
                }
                return null;
            } finally {
                if (shared != null) {
                    shared.close();
                }
            }
        }

        private TextureBinding resolveMissingTexture() {
            Integer existing = indices.get(MissingSprite.getMissingSpriteId());
            return existing != null
                    ? new TextureBinding(existing, MissingSprite.getMissingSpriteId())
                    : resolveLayerTexture(MissingSprite.getMissingSpriteId());
        }

        private TextureBinding resolveLayerTexture(Identifier id) {
            AbstractTexture texture = client.getTextureManager().getTexture(id);
            VRef<VGImage> shared = ((IVGImage) texture).getVGImage();
            try {
                if (shared == null || images.size() >= MAX_TEXTURES) {
                    return null;
                }
                shared.get().image();
                int index = images.size();
                indices.put(id, index);
                images.add(shared);
                shared = null;
                return new TextureBinding(index, id);
            } catch (NullPointerException e) {
                return null;
            } finally {
                if (shared != null) {
                    shared.close();
                }
            }
        }

        List<VRef<VGImage>> retainImages() {
            List<VRef<VGImage>> refs = new ArrayList<>(images.size());
            for (VRef<VGImage> image : images) {
                refs.add(image.addRef());
            }
            return refs;
        }

        @Override
        public void close() {
            for (VRef<VGImage> image : images) {
                image.close();
            }
            images.clear();
        }
    }

    private static void captureParticles(MinecraftClient client, TextureRegistry textures,
            List<EntityRenderData> entities, Camera camera, float tickDelta, int maxParticles) {
        if (client.particleManager == null) {
            return;
        }

        Map<ParticleTextureSheet, java.util.Queue<Particle>> particles =
                ((ParticleManagerAccessor) client.particleManager).getParticles();
        if (particles == null || particles.isEmpty()) {
            return;
        }

        List<Geometry> geometries = new ArrayList<>();
        int captured = 0;
        for (var entry : particles.entrySet()) {
            if (captured >= maxParticles) {
                break;
            }

            Identifier atlasId = atlasForParticleSheet(entry.getKey());
            if (atlasId == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }

            TextureBinding texture = textures.resolveTexture(atlasId);
            if (texture == null) {
                continue;
            }

            CanonicalVertexConsumer consumer = new CanonicalVertexConsumer(texture.index(), texture.id(), 2);
            for (Particle particle : entry.getValue()) {
                if (captured >= maxParticles) {
                    break;
                }
                if (particle == null || !particle.isAlive()) {
                    continue;
                }
                try {
                    particle.buildGeometry(consumer, camera, tickDelta);
                    captured++;
                } catch (Exception e) {
                    LOGGER.debug("Skipping RTX particle capture for {}", particle, e);
                }
            }

            Geometry geometry = consumer.finish();
            if (geometry != null) {
                geometries.add(geometry);
            }
        }

        if (!geometries.isEmpty()) {
            Vec3d cameraPos = camera.getPos();
            entities.add(new EntityRenderData(cameraPos.x, cameraPos.y, cameraPos.z, geometries, false));
        }
    }

    private static Identifier atlasForParticleSheet(ParticleTextureSheet sheet) {
        if (sheet == ParticleTextureSheet.TERRAIN_SHEET) {
            return SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE;
        }
        if (sheet == ParticleTextureSheet.PARTICLE_SHEET_OPAQUE
                || sheet == ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT
                || sheet == ParticleTextureSheet.PARTICLE_SHEET_LIT) {
            return SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE;
        }
        return null;
    }

    private static final class CanonicalProvider implements VertexConsumerProvider {
        private final TextureRegistry textures;
        private final Map<RenderLayer, CanonicalVertexConsumer> consumers = new LinkedHashMap<>();

        private CanonicalProvider(TextureRegistry textures) {
            this.textures = textures;
        }

        @Override
        public VertexConsumer getBuffer(RenderLayer layer) {
            if (layer.getDrawMode() != net.minecraft.client.render.VertexFormat.DrawMode.QUADS) {
                return DiscardingVertexConsumer.INSTANCE;
            }
            CanonicalVertexConsumer existing = consumers.get(layer);
            if (existing != null) {
                return existing;
            }

            TextureBinding texture = textures.resolve(layer);
            if (texture == null) {
                return DiscardingVertexConsumer.INSTANCE;
            }

            CanonicalVertexConsumer created = new CanonicalVertexConsumer(texture.index(), texture.id(), alphaMode(layer));
            consumers.put(layer, created);
            return created;
        }

        List<Geometry> finish() {
            List<Geometry> geometries = new ArrayList<>();
            for (CanonicalVertexConsumer consumer : consumers.values()) {
                Geometry geometry = consumer.finish();
                if (geometry != null) {
                    geometries.add(geometry);
                }
            }
            return geometries;
        }

        private static int alphaMode(RenderLayer layer) {
            String name = layer.toString().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("translucent") || name.contains("see_through")) {
                return 2;
            }
            if (name.contains("cutout") || name.contains("text")) {
                return 1;
            }
            return 0;
        }
    }

    private record TextureBinding(int index, Identifier id) {
    }

    private static final class CanonicalVertexConsumer implements VertexConsumer {
        private ByteBuffer buffer = ByteBuffer.allocateDirect(VERTEX_STRIDE * 256)
                .order(ByteOrder.nativeOrder());
        private final int textureIndex;
        private final Identifier textureId;
        private final int alphaMode;

        private float x;
        private float y;
        private float z;
        private float normalX;
        private float normalY = 1.0f;
        private float normalZ;
        private float u;
        private float v;
        private int red = 255;
        private int green = 255;
        private int blue = 255;
        private int alpha = 255;
        private int lightU;
        private int lightV;
        private boolean fixedColor;
        private int vertexCount;

        private CanonicalVertexConsumer(int textureIndex, Identifier textureId, int alphaMode) {
            this.textureIndex = textureIndex;
            this.textureId = textureId;
            this.alphaMode = alphaMode;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.x = (float) x;
            this.y = (float) y;
            this.z = (float) z;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            if (!fixedColor) {
                this.red = red;
                this.green = green;
                this.blue = blue;
                this.alpha = alpha;
            }
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            this.lightU = u;
            this.lightV = v;
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            this.normalX = x;
            this.normalY = y;
            this.normalZ = z;
            return this;
        }

        @Override
        public void next() {
            ensureCapacity(VERTEX_STRIDE);
            int base = buffer.position();
            buffer.putFloat(base, x);
            buffer.putFloat(base + 4, y);
            buffer.putFloat(base + 8, z);
            buffer.putFloat(base + 12, 0.0f);
            buffer.putFloat(base + 16, normalX);
            buffer.putFloat(base + 20, normalY);
            buffer.putFloat(base + 24, normalZ);
            buffer.putFloat(base + 28, 0.0f);
            buffer.putFloat(base + 32, u);
            buffer.putFloat(base + 36, v);
            buffer.putInt(base + 40, packColor(red, green, blue, alpha));
            buffer.putInt(base + 44, textureIndex);
            buffer.putInt(base + 48, lightU);
            buffer.putInt(base + 52, lightV);
            buffer.putInt(base + 56, alphaMode);
            buffer.putInt(base + 60, 0);
            buffer.position(base + VERTEX_STRIDE);
            vertexCount++;
            resetVertex();
        }

        @Override
        public void fixedColor(int red, int green, int blue, int alpha) {
            fixedColor = true;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
        }

        @Override
        public void unfixColor() {
            fixedColor = false;
            red = green = blue = alpha = 255;
        }

        private Geometry finish() {
            int completeVertices = vertexCount - vertexCount % 4;
            if (completeVertices == 0) {
                return null;
            }
            ByteBuffer result = buffer.duplicate().order(ByteOrder.nativeOrder());
            result.position(0);
            result.limit(completeVertices * VERTEX_STRIDE);
            return new Geometry(result.slice().order(ByteOrder.nativeOrder()), completeVertices, textureId);
        }

        private void ensureCapacity(int bytes) {
            if (buffer.remaining() >= bytes) {
                return;
            }
            int capacity = Math.max(buffer.capacity() * 2, buffer.position() + bytes);
            ByteBuffer replacement = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
            buffer.flip();
            replacement.put(buffer);
            buffer = replacement;
        }

        private void resetVertex() {
            x = y = z = 0.0f;
            normalX = normalZ = 0.0f;
            normalY = 1.0f;
            u = v = 0.0f;
            lightU = lightV = 0;
            if (!fixedColor) {
                red = green = blue = alpha = 255;
            }
        }

        private static int packColor(int red, int green, int blue, int alpha) {
            return (red & 0xFF)
                    | ((green & 0xFF) << 8)
                    | ((blue & 0xFF) << 16)
                    | ((alpha & 0xFF) << 24);
        }
    }

    private enum DiscardingVertexConsumer implements VertexConsumer {
        INSTANCE;

        @Override public VertexConsumer vertex(double x, double y, double z) { return this; }
        @Override public VertexConsumer color(int red, int green, int blue, int alpha) { return this; }
        @Override public VertexConsumer texture(float u, float v) { return this; }
        @Override public VertexConsumer overlay(int u, int v) { return this; }
        @Override public VertexConsumer light(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void next() { }
        @Override public void fixedColor(int red, int green, int blue, int alpha) { }
        @Override public void unfixColor() { }
    }
}

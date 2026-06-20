package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.mixin.minecraft.ParticleAccessor;
import net.caffeinemc.mods.sodium.api.vertex.attributes.CommonVertexAttribute;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatDescription;
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
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    private static final int DISTANCE_CULL_DISABLED_RADIUS = 256;
    private static final int CAPTURE_SUMMARY_INTERVAL_FRAMES = 120;
    private static final int DEFAULT_PARTICLE_CLASS_CAPTURE_CAP = 48;
    private static final List<ParticleTextureSheet> ORDERED_PARTICLE_SHEETS = List.of(
            ParticleTextureSheet.TERRAIN_SHEET,
            ParticleTextureSheet.PARTICLE_SHEET_OPAQUE,
            ParticleTextureSheet.PARTICLE_SHEET_LIT,
            ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT);
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityCapture.class);
    private final TextureRegistry textures = new TextureRegistry();
    private int captureFrameCounter;

    public Frame capture(float tickDelta, ClientWorld world, Camera camera, int maxEntities,
            int maxParticles, boolean captureParticles, int maxEntityDistance) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (world == null || client.worldRenderer == null) {
            return null;
        }

        LevelRendererAccessor renderer = (LevelRendererAccessor) client.worldRenderer;
        textures.beginFrame(client);
        List<EntityRenderData> entities = new ArrayList<>();
        CaptureStats stats = new CaptureStats();

        if (maxEntities > 0) {
            List<Entity> candidates = collectEntityCandidates(world, camera, maxEntityDistance);
            stats.entityCandidates = candidates.size();
            for (Entity entity : candidates) {
                if (entities.size() >= maxEntities) {
                    break;
                }
                CanonicalProvider provider = new CanonicalProvider(textures, stats);
                try {
                    double x = MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX());
                    double y = MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY());
                    double z = MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ());
                    stats.entitiesRendered++;
                    renderer.invokeRenderEntity(entity, x, y, z, tickDelta,
                            new MatrixStack(), provider);
                    List<Geometry> geometries = provider.finish();
                    if (!geometries.isEmpty()) {
                        stats.entitiesCaptured++;
                        for (Geometry geometry : geometries) {
                            stats.entityGeometries++;
                            stats.entityQuads += geometry.quadCount();
                        }
                        entities.add(new EntityRenderData(x, y, z, geometries, true));
                    } else {
                        stats.entitiesWithoutGeometry++;
                    }
                } catch (Exception e) {
                    stats.entityErrors++;
                    if (stats.entityErrors <= 2) {
                        LOGGER.debug("Skipping RTX entity capture for {}", entity.getType(), e);
                    }
                }
            }
        }

        if (captureParticles && camera != null && maxParticles > 0) {
            captureParticles(client, textures, entities, camera, tickDelta, maxParticles,
                    maxEntityDistance, stats);
        }

        logCaptureSummary(stats, entities);

        if (entities.isEmpty()) {
            return null;
        }
        return new Frame(entities, textures.retainImages());
    }

    private static List<Entity> collectEntityCandidates(ClientWorld world, Camera camera, int maxEntityDistance) {
        Vec3d cameraPos = camera == null ? null : camera.getPos();
        double maxDistanceSquared = maxDistanceSquared(maxEntityDistance);
        List<Entity> candidates = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            if (cameraPos == null || distanceSquared(entity, cameraPos) <= maxDistanceSquared) {
                candidates.add(entity);
            }
        }
        if (cameraPos != null) {
            candidates.sort(Comparator.comparingDouble(entity -> distanceSquared(entity, cameraPos)));
        }
        return candidates;
    }

    private static double maxDistanceSquared(int maxEntityDistance) {
        return maxEntityDistance >= DISTANCE_CULL_DISABLED_RADIUS
                ? Double.POSITIVE_INFINITY
                : maxEntityDistance * (double) maxEntityDistance;
    }

    private static double distanceSquared(Entity entity, Vec3d position) {
        double dx = entity.getX() - position.x;
        double dy = entity.getY() - position.y;
        double dz = entity.getZ() - position.z;
        return dx * dx + dy * dy + dz * dz;
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
        private int frameMaxTextureIndex = -1;

        private void beginFrame(MinecraftClient client) {
            this.client = client;
            this.frameMaxTextureIndex = -1;
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
                return bind(existing, id);
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
                return bind(index, id);
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
                    ? bind(existing, MissingSprite.getMissingSpriteId())
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
                return bind(index, id);
            } catch (NullPointerException e) {
                return null;
            } finally {
                if (shared != null) {
                    shared.close();
                }
            }
        }

        List<VRef<VGImage>> retainImages() {
            int count = Math.min(images.size(), frameMaxTextureIndex + 1);
            List<VRef<VGImage>> refs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                refs.add(images.get(i).addRef());
            }
            return refs;
        }

        private TextureBinding bind(int index, Identifier id) {
            frameMaxTextureIndex = Math.max(frameMaxTextureIndex, index);
            return new TextureBinding(index, id);
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
            List<EntityRenderData> entities, Camera camera, float tickDelta, int maxParticles,
            int maxParticleDistance, CaptureStats stats) {
        if (client.particleManager == null) {
            return;
        }

        Map<ParticleTextureSheet, java.util.Queue<Particle>> particles =
                ((ParticleManagerAccessor) client.particleManager).getParticles();
        if (particles == null || particles.isEmpty()) {
            return;
        }

        Vec3d cameraPos = camera.getPos();
        double maxDistanceSquared = maxDistanceSquared(maxParticleDistance);
        List<ParticleCandidate> candidates = collectParticleCandidates(
                particles, textures, cameraPos, maxDistanceSquared);
        if (candidates.isEmpty()) {
            return;
        }

        candidates.sort(Comparator.comparingDouble(ParticleCandidate::distanceSquared));

        int captured = 0;
        int perClassLimit = Math.max(8, Math.min(DEFAULT_PARTICLE_CLASS_CAPTURE_CAP, Math.max(1, maxParticles / 2)));
        Map<Class<?>, Integer> capturedByClass = new HashMap<>();
        Map<TextureBinding, CanonicalVertexConsumer> consumers = new LinkedHashMap<>();

        for (ParticleCandidate candidate : candidates) {
            if (captured >= maxParticles) {
                break;
            }

            Particle particle = candidate.particle();
            Class<?> particleClass = particle.getClass();
            int classCount = capturedByClass.getOrDefault(particleClass, 0);
            if (classCount >= perClassLimit) {
                continue;
            }

            CanonicalVertexConsumer consumer = consumers.computeIfAbsent(candidate.texture(),
                    texture -> new CanonicalVertexConsumer(texture.index(), texture.id(), 2, 4));
            try {
                stats.particlesAttempted++;
                particle.buildGeometry(consumer, camera, tickDelta);
                captured++;
                capturedByClass.put(particleClass, classCount + 1);
                stats.particlesCaptured++;
            } catch (Exception e) {
                stats.particleErrors++;
                if (stats.particleErrors <= 2) {
                    LOGGER.trace("Skipping RTX particle capture for {}", particle, e);
                }
            }
        }

        List<Geometry> geometries = new ArrayList<>(consumers.size());
        for (CanonicalVertexConsumer consumer : consumers.values()) {
            Geometry geometry = consumer.finish();
            if (geometry != null) {
                stats.particleGeometries++;
                stats.particleQuads += geometry.quadCount();
                geometries.add(geometry);
            }
        }

        if (!geometries.isEmpty()) {
            entities.add(new EntityRenderData(cameraPos.x, cameraPos.y, cameraPos.z, geometries, false));
        }
    }

    private static List<ParticleCandidate> collectParticleCandidates(
            Map<ParticleTextureSheet, java.util.Queue<Particle>> particles,
            TextureRegistry textures,
            Vec3d cameraPos,
            double maxDistanceSquared) {
        List<ParticleCandidate> candidates = new ArrayList<>();
        for (ParticleTextureSheet sheet : orderedParticleSheets(particles)) {
            java.util.Queue<Particle> queue = particles.get(sheet);
            Identifier atlasId = atlasForParticleSheet(sheet);
            if (atlasId == null || queue == null || queue.isEmpty()) {
                continue;
            }

            TextureBinding texture = textures.resolveTexture(atlasId);
            if (texture == null) {
                continue;
            }

            for (Particle particle : queue) {
                if (particle == null || !particle.isAlive()) {
                    continue;
                }
                double distanceSquared = particleDistanceSquared(particle, cameraPos);
                if (distanceSquared <= maxDistanceSquared) {
                    candidates.add(new ParticleCandidate(particle, texture, distanceSquared));
                }
            }
        }
        return candidates;
    }

    private static List<ParticleTextureSheet> orderedParticleSheets(
            Map<ParticleTextureSheet, java.util.Queue<Particle>> particles) {
        List<ParticleTextureSheet> ordered = new ArrayList<>(particles.size());
        for (ParticleTextureSheet sheet : ORDERED_PARTICLE_SHEETS) {
            if (particles.containsKey(sheet)) {
                ordered.add(sheet);
            }
        }
        for (ParticleTextureSheet sheet : particles.keySet()) {
            if (!ordered.contains(sheet)) {
                ordered.add(sheet);
            }
        }
        return ordered;
    }

    private static double particleDistanceSquared(Particle particle, Vec3d cameraPos) {
        ParticleAccessor accessor = (ParticleAccessor) particle;
        double dx = accessor.vulkanite$getX() - cameraPos.x;
        double dy = accessor.vulkanite$getY() - cameraPos.y;
        double dz = accessor.vulkanite$getZ() - cameraPos.z;
        return dx * dx + dy * dy + dz * dz;
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

    private record ParticleCandidate(Particle particle, TextureBinding texture, double distanceSquared) {
    }

    private static final class CanonicalProvider implements VertexConsumerProvider {
        private final TextureRegistry textures;
        private final CaptureStats stats;
        private final Map<RenderLayer, CanonicalVertexConsumer> consumers = new LinkedHashMap<>();

        private CanonicalProvider(TextureRegistry textures, CaptureStats stats) {
            this.textures = textures;
            this.stats = stats;
        }

        @Override
        public VertexConsumer getBuffer(RenderLayer layer) {
            int verticesPerPrimitive = verticesPerPrimitive(layer.getDrawMode());
            if (verticesPerPrimitive == 0) {
                stats.unsupportedLayers++;
                return DiscardingVertexConsumer.INSTANCE;
            }
            CanonicalVertexConsumer existing = consumers.get(layer);
            if (existing != null) {
                return existing;
            }

            TextureBinding texture = textures.resolve(layer);
            if (texture == null) {
                stats.textureMisses++;
                return DiscardingVertexConsumer.INSTANCE;
            }

            CanonicalVertexConsumer created = new CanonicalVertexConsumer(
                    texture.index(), texture.id(), alphaMode(layer), verticesPerPrimitive);
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

        private static int verticesPerPrimitive(VertexFormat.DrawMode mode) {
            if (mode == VertexFormat.DrawMode.QUADS) {
                return 4;
            }
            if (mode == VertexFormat.DrawMode.TRIANGLES) {
                return 3;
            }
            return 0;
        }
    }

    private record TextureBinding(int index, Identifier id) {
    }

    private static final class CanonicalVertexConsumer implements VertexConsumer, VertexBufferWriter {
        private ByteBuffer buffer = ByteBuffer.allocateDirect(VERTEX_STRIDE * 256)
                .order(ByteOrder.nativeOrder());
        private final int textureIndex;
        private final Identifier textureId;
        private final int alphaMode;
        private final int verticesPerPrimitive;

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

        private CanonicalVertexConsumer(int textureIndex, Identifier textureId, int alphaMode, int verticesPerPrimitive) {
            this.textureIndex = textureIndex;
            this.textureId = textureId;
            this.alphaMode = alphaMode;
            this.verticesPerPrimitive = verticesPerPrimitive;
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

        @Override
        public void push(MemoryStack stack, long ptr, int count, VertexFormatDescription format) {
            int stride = format.stride();
            int positionOffset = offset(format, CommonVertexAttribute.POSITION);
            if (positionOffset < 0) {
                return;
            }
            int textureOffset = offset(format, CommonVertexAttribute.TEXTURE);
            int colorOffset = offset(format, CommonVertexAttribute.COLOR);
            int lightOffset = offset(format, CommonVertexAttribute.LIGHT);
            int normalOffset = offset(format, CommonVertexAttribute.NORMAL);

            for (int i = 0; i < count; i++) {
                long vertex = ptr + (long) i * stride;
                x = MemoryUtil.memGetFloat(vertex + positionOffset);
                y = MemoryUtil.memGetFloat(vertex + positionOffset + 4);
                z = MemoryUtil.memGetFloat(vertex + positionOffset + 8);

                if (textureOffset >= 0) {
                    u = MemoryUtil.memGetFloat(vertex + textureOffset);
                    v = MemoryUtil.memGetFloat(vertex + textureOffset + 4);
                }
                if (colorOffset >= 0 && !fixedColor) {
                    int color = MemoryUtil.memGetInt(vertex + colorOffset);
                    red = color & 0xFF;
                    green = (color >>> 8) & 0xFF;
                    blue = (color >>> 16) & 0xFF;
                    alpha = (color >>> 24) & 0xFF;
                }
                if (lightOffset >= 0) {
                    int light = MemoryUtil.memGetInt(vertex + lightOffset);
                    lightU = light & 0xFFFF;
                    lightV = (light >>> 16) & 0xFFFF;
                }
                if (normalOffset >= 0) {
                    normalX = unpackNormal(MemoryUtil.memGetByte(vertex + normalOffset));
                    normalY = unpackNormal(MemoryUtil.memGetByte(vertex + normalOffset + 1));
                    normalZ = unpackNormal(MemoryUtil.memGetByte(vertex + normalOffset + 2));
                }
                next();
            }
        }

        private Geometry finish() {
            if (verticesPerPrimitive == 3) {
                return finishTrianglesAsQuads();
            }
            int completeVertices = vertexCount - vertexCount % 4;
            if (completeVertices == 0) {
                return null;
            }
            ByteBuffer result = buffer.duplicate().order(ByteOrder.nativeOrder());
            result.position(0);
            result.limit(completeVertices * VERTEX_STRIDE);
            return new Geometry(result.slice().order(ByteOrder.nativeOrder()), completeVertices, textureId);
        }

        private Geometry finishTrianglesAsQuads() {
            int triangleCount = vertexCount / 3;
            if (triangleCount == 0) {
                return null;
            }
            int quadVertices = triangleCount * 4;
            ByteBuffer result = ByteBuffer.allocateDirect(quadVertices * VERTEX_STRIDE)
                    .order(ByteOrder.nativeOrder());
            long src = MemoryUtil.memAddress(buffer);
            long dst = MemoryUtil.memAddress(result);
            for (int triangle = 0; triangle < triangleCount; triangle++) {
                long triangleSrc = src + (long) triangle * 3L * VERTEX_STRIDE;
                long quadDst = dst + (long) triangle * 4L * VERTEX_STRIDE;
                MemoryUtil.memCopy(triangleSrc, quadDst, 3L * VERTEX_STRIDE);
                MemoryUtil.memCopy(triangleSrc + 2L * VERTEX_STRIDE, quadDst + 3L * VERTEX_STRIDE, VERTEX_STRIDE);
            }
            result.position(0);
            result.limit(quadVertices * VERTEX_STRIDE);
            return new Geometry(result.slice().order(ByteOrder.nativeOrder()), quadVertices, textureId);
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

        private static int offset(VertexFormatDescription format, CommonVertexAttribute attribute) {
            return format.containsElement(attribute) ? format.getElementOffset(attribute) : -1;
        }

        private static float unpackNormal(byte value) {
            return Math.max(-1.0f, value / 127.0f);
        }
    }

    private enum DiscardingVertexConsumer implements VertexConsumer, VertexBufferWriter {
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
        @Override public void push(MemoryStack stack, long ptr, int count, VertexFormatDescription format) { }
    }

    private void logCaptureSummary(CaptureStats stats, List<EntityRenderData> entities) {
        int frame = ++captureFrameCounter;
        if (frame % CAPTURE_SUMMARY_INTERVAL_FRAMES != 0 && entities.isEmpty() && !stats.hasFailures()) {
            return;
        }
        LOGGER.info("[Vulkanite] RTX capture: candidates={}, rendered={}, entityInstances={}, entityGeometries={}, entityQuads={}, particleAttempts={}, particleInstances={}, particleGeometries={}, particleQuads={}, unsupportedLayers={}, textureMisses={}, emptyEntities={}, entityErrors={}, particleErrors={}",
                stats.entityCandidates,
                stats.entitiesRendered,
                stats.entitiesCaptured,
                stats.entityGeometries,
                stats.entityQuads,
                stats.particlesAttempted,
                stats.particlesCaptured,
                stats.particleGeometries,
                stats.particleQuads,
                stats.unsupportedLayers,
                stats.textureMisses,
                stats.entitiesWithoutGeometry,
                stats.entityErrors,
                stats.particleErrors);
    }

    private static final class CaptureStats {
        private int entityCandidates;
        private int entitiesRendered;
        private int entitiesCaptured;
        private int entitiesWithoutGeometry;
        private int entityGeometries;
        private int entityQuads;
        private int entityErrors;
        private int unsupportedLayers;
        private int textureMisses;
        private int particlesAttempted;
        private int particlesCaptured;
        private int particleGeometries;
        private int particleQuads;
        private int particleErrors;

        private boolean hasFailures() {
            return entityErrors > 0 || particleErrors > 0;
        }
    }
}

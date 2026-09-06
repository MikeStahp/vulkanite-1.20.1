package me.cortex.vulkanite.lib.shader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;

public class ShaderCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderCompiler.class);
    private static final String CACHE_ENABLED_PROPERTY = "vulkanite.shaderCache";
    private static final String DEBUG_INFO_PROPERTY = "vulkanite.shaderDebugInfo";
    private static final boolean CACHE_ENABLED =
            Boolean.parseBoolean(System.getProperty(CACHE_ENABLED_PROPERTY, "true"));
    private static final boolean GENERATE_DEBUG_INFO = Boolean.getBoolean(DEBUG_INFO_PROPERTY);
    private static final boolean FAST_PIPELINE_COMPILE = Boolean.getBoolean("vulkanite.fastPipelineCompile");
    private static final ShaderBinaryCache.Optimization OPTIMIZATION = FAST_PIPELINE_COMPILE
            ? ShaderBinaryCache.Optimization.ZERO
            : ShaderBinaryCache.Optimization.PERFORMANCE;
    private static final ShaderBinaryCache BINARY_CACHE = new ShaderBinaryCache(ShaderBinaryCache.defaultDirectory());
    private static final AtomicBoolean CACHE_READ_WARNING_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean CACHE_WRITE_WARNING_REPORTED = new AtomicBoolean();

    private static final class CompilerHolder {
        private static final long COMPILER = createCompiler();
    }

    private static long createCompiler() {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) {
            throw new RuntimeException("Failed to create shader compiler");
        }
        return compiler;
    }

    private static int vulkanStageToShadercKind(int stage) {
        switch (stage) {
            case VK_SHADER_STAGE_VERTEX_BIT:
                return shaderc_vertex_shader;
            case VK_SHADER_STAGE_FRAGMENT_BIT:
                return shaderc_fragment_shader;
            case VK_SHADER_STAGE_RAYGEN_BIT_KHR:
                return shaderc_raygen_shader;
            case VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR:
                return shaderc_closesthit_shader;
            case VK_SHADER_STAGE_MISS_BIT_KHR:
                return shaderc_miss_shader;
            case VK_SHADER_STAGE_ANY_HIT_BIT_KHR:
                return shaderc_anyhit_shader;
            case VK_SHADER_STAGE_INTERSECTION_BIT_KHR:
                return shaderc_intersection_shader;
            case VK_SHADER_STAGE_COMPUTE_BIT:
                return shaderc_compute_shader;
            default:
                throw new IllegalArgumentException("Stage: " + stage);
        }
    }

    public static ByteBuffer compileShader(String filename, String source, int vulkanStage) {
        String cacheKey = ShaderBinaryCache.keyFor(source, vulkanStage, GENERATE_DEBUG_INFO, OPTIMIZATION);
        if (CACHE_ENABLED) {
            Optional<byte[]> cached = readCachedShader(cacheKey);
            if (cached.isPresent()) {
                LOGGER.debug("Shader cache hit: file={}, stage={}, optimization={}, key={}",
                        filename, vulkanStage, OPTIMIZATION, cacheKey.substring(0, 12));
                return directBuffer(cached.get());
            }
        }

        long startedAt = System.nanoTime();
        ByteBuffer compiled = compileUncached(filename, source, vulkanStage);
        LOGGER.debug("Shader compiled: file={}, stage={}, optimization={}, elapsedMs={}, key={}",
                filename,
                vulkanStage,
                OPTIMIZATION,
                (System.nanoTime() - startedAt) / 1_000_000L,
                cacheKey.substring(0, 12));
        if (CACHE_ENABLED) {
            writeCachedShader(cacheKey, compiled);
        }
        return compiled;
    }

    private static ByteBuffer compileUncached(String filename, String source, int vulkanStage) {
        long options = shaderc_compile_options_initialize();
        if (options == 0) {
            throw new RuntimeException("Failed to create shader compiler options");
        }
        long result = 0;
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_4);
            if (GENERATE_DEBUG_INFO) {
                shaderc_compile_options_set_generate_debug_info(options);
            }
            shaderc_compile_options_set_optimization_level(options, FAST_PIPELINE_COMPILE
                    ? shaderc_optimization_level_zero
                    : shaderc_optimization_level_performance);

            result = shaderc_compile_into_spv(CompilerHolder.COMPILER, source,
                    vulkanStageToShadercKind(vulkanStage), filename,
                    "main", options);

            if (result == 0) {
                throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V");
            }

            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V:\n "
                        + shaderc_result_get_error_message(result));
            }

            ByteBuffer code = shaderc_result_get_bytes(result);
            ByteBuffer ret = ByteBuffer.allocateDirect(code.remaining());
            ret.put(code);
            ret.flip();
            return ret;
        } finally {
            if (result != 0) {
                shaderc_result_release(result);
            }
            shaderc_compile_options_release(options);
        }
    }

    private static Optional<byte[]> readCachedShader(String cacheKey) {
        try {
            return BINARY_CACHE.read(cacheKey);
        } catch (Exception e) {
            if (CACHE_READ_WARNING_REPORTED.compareAndSet(false, true)) {
                LOGGER.warn("Shader cache reads failed; shaders will be compiled normally", e);
            }
            return Optional.empty();
        }
    }

    private static void writeCachedShader(String cacheKey, ByteBuffer compiled) {
        ByteBuffer view = compiled.asReadOnlyBuffer();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        try {
            BINARY_CACHE.write(cacheKey, bytes);
        } catch (Exception e) {
            if (CACHE_WRITE_WARNING_REPORTED.compareAndSet(false, true)) {
                LOGGER.warn("Shader cache writes failed; this launch will continue without persistence", e);
            }
        }
    }

    private static ByteBuffer directBuffer(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}

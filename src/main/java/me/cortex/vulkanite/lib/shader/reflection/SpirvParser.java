package me.cortex.vulkanite.lib.shader.reflection;

import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection.Binding;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection.Set;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.spvc.Spvc.*;
import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.vulkan.VK10.*;

public class SpirvParser {
    private SpirvParser() {
        // Private constructor to prevent instantiation
    }

    /**
     * Parses SPIR-V bytecode and returns the reflection data
     * @param spirv The SPIR-V bytecode to parse
     * @return ArrayList of Sets containing the parsed reflection data
     */
    public static ArrayList<Set> parse(ByteBuffer spirv) {
        ArrayList<Set> sets = new ArrayList<>();
        
        try (var stack = stackPush()) {
            // Create context
            var ptr = stack.mallocPointer(1);
            var ptr2 = stack.mallocPointer(1);
            _CHECK_(spvc_context_create(ptr));
            long context = ptr.get(0);

            // Parse the spir-v
            _CHECK_(spvc_context_parse_spirv(context, spirv.asIntBuffer(), spirv.remaining() >> 2, ptr));
            long ir = ptr.get(0);

            // Hand it off to a compiler instance and give it ownership of the IR.
            _CHECK_(spvc_context_create_compiler(context, SPVC_BACKEND_NONE, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP,
                    ptr));
            long compiler = ptr.get(0);

            // Create resources from spir-v
            _CHECK_(spvc_compiler_create_shader_resources(compiler, ptr));
            long resources = ptr.get(0);

            // Get reflection data
            for (var type : ResourceType.values()) {
                int vkDescType = type.toVkDescriptorType();

                _CHECK_(spvc_resources_get_resource_list_for_type(resources, type.id, ptr, ptr2));
                var reflectedResources = SpvcReflectedResource.create(ptr.get(0), (int) ptr2.get(0));
                for (var reflect : reflectedResources) {
                    if (vkDescType != -1) {
                        int binding = spvc_compiler_get_decoration(compiler, reflect.id(), SpvDecorationBinding);
                        int set = spvc_compiler_get_decoration(compiler, reflect.id(), SpvDecorationDescriptorSet);
                        var spvcType = spvc_compiler_get_type_handle(compiler, reflect.type_id());
                        int arrayNDims = spvc_type_get_num_array_dimensions(spvcType);
                        int arraySize = 0;
                        String name = spvc_compiler_get_name(compiler, reflect.id());
                        if (arrayNDims > 0) {
                            arraySize = 1;
                            for (int i = 0; i < arrayNDims; i++) {
                                arraySize *= spvc_type_get_array_dimension(spvcType, i);
                            }
                        }
                        boolean isRuntimeSized = false;
                        if (arraySize == 0 && arrayNDims > 0) {
                            isRuntimeSized = true;
                            arraySize = 1;
                        }
                        var descriptor = new Binding(name, binding, vkDescType, arraySize, isRuntimeSized);
                        while (sets.size() <= set) {
                            sets.add(new Set(new ArrayList<>()));
                        }
                        sets.get(set).bindings().add(descriptor);
                    }
                }
            }

            spvc_context_destroy(context);
        }
        
        return sets;
    }

    private static void _CHECK_(int status) {
        if (status != 0) {
            throw new IllegalStateException("Got status: " + status);
        }
    }
}
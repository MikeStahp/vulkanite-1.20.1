package me.cortex.vulkanite.lib.shader.reflection;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.descriptors.DescriptorSetLayoutBuilder;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

public class ShaderReflection {
    public record Binding(String name, int binding, int descriptorType, int arraySize, boolean runtimeSized) {
    }

    public record Set(ArrayList<Binding> bindings) {
        public Set(ArrayList<Binding> bindings) {
            // Sort by binding
            this.bindings = bindings;
            this.bindings().sort((a, b) -> Integer.compare(a.binding, b.binding));
        }

        public Set(Binding... bindings) {
            this(new ArrayList<Binding>(List.of(bindings)));
        }
        
        public ArrayList<Binding> bindings() {
            return bindings;
        }

        public Binding getBindingAt(int binding) {
            for (var b : bindings()) {
                if (b.binding == binding) {
                    return b;
                }
            }
            return null;
        }

        public boolean hasUnsizedArrays() {
            for (var binding : bindings()) {
                if (binding.runtimeSized) {
                    return true;
                }
            }
            return false;
        }

        public boolean validate(Set expected) {
            for (var binding : bindings()) {
                var expectedBinding = expected.getBindingAt(binding.binding);
                if (expectedBinding == null) {
                    return false;
                }
                if (expectedBinding.descriptorType != binding.descriptorType) {
                    return false;
                }
                if (expectedBinding.runtimeSized != binding.runtimeSized) {
                    return false;
                }
                if (!expectedBinding.runtimeSized && expectedBinding.arraySize != binding.arraySize) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Set {\n");
            for (var binding : bindings()) {
                sb.append("  Binding ").append(binding.binding)
                        .append(": ").append(binding.name)
                        .append(" (type=").append(binding.descriptorType)
                        .append(", arraySize=").append(binding.arraySize)
                        .append(", runtimeSized=").append(binding.runtimeSized).append(")\n");
            }
            sb.append("}");
            return sb.toString();
        }
    }

    private ArrayList<Set> sets = new ArrayList<>();

    public List<Binding> getBindings(int set) {
        return sets.get(set).bindings();
    }

    public Set getSet(int set) {
        return sets.get(set);
    }

    public int getNSets() {
        return sets.size();
    }

    public ShaderReflection() {
        // Empty
    }

    public ShaderReflection(ByteBuffer spirv) {
        this.sets = SpirvParser.parse(spirv);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int set = 0; set < sets.size(); set++) {
            sb.append("Set ").append(set).append(":\n");
            for (var binding : sets.get(set).bindings()) {
                sb.append("  - ").append(binding.binding).append(" : ").append(binding.name).append("; arraySize = ")
                        .append(binding.arraySize).append("; runtimeSized = ").append(binding.runtimeSized)
                        .append("\n");
            }
        }
        return sb.toString();
    }

    public static ShaderReflection mergeStages(ShaderReflection... stages) {
        ShaderReflection out = new ShaderReflection();
        int maxSets = 0;
        for (var stage : stages) {
            maxSets = Math.max(maxSets, stage.getNSets());
        }
        for (int set = 0; set < maxSets; set++) {
            var bindings = new ArrayList<Binding>();
            for (var stage : stages) {
                if (stage.getNSets() > set) {
                    var stageBindings = stage.getBindings(set);
                    for (var binding : stageBindings) {
                        boolean alreadyExists = false;
                        for (var b : bindings) {
                            if (b.binding == binding.binding) {
                                // Check for conflicts
                                if (b.descriptorType != binding.descriptorType) {
                                    throw new IllegalStateException("Conflicting descriptor types for binding "
                                            + binding.binding + " in set " + set);
                                }
                                if (b.runtimeSized != binding.runtimeSized) {
                                    throw new IllegalStateException("Conflicting runtime sized for binding "
                                            + binding.binding + " in set " + set);
                                }
                                if (!b.runtimeSized && b.arraySize != binding.arraySize) {
                                    throw new IllegalStateException("Conflicting array sizes for binding "
                                            + binding.binding + " in set " + set);
                                }
                                // We don't check for name conflicts, but still warn
                                if (!b.name.isEmpty() && !binding.name.isEmpty()
                                        && b.name.compareTo(binding.name) != 0) {
                                    System.err.println("Warning: Conflicting names for binding " + binding.binding
                                            + " : " + b.name + " and " + binding.name);
                                }
                                alreadyExists = true;
                            }
                        }
                        if (!alreadyExists) {
                            bindings.add(binding);
                        }
                    }
                }
            }
            out.sets.add(new Set(bindings));
        }
        return out;
    }

    public List<VRef<VDescriptorSetLayout>> buildSetLayouts(VContext context) {
        // TODO: Pick a better number, somehow
        return buildSetLayouts(context, 65536);
    }

    private List<VRef<VDescriptorSetLayout>> layouts = new ArrayList<>();

    public List<VRef<VDescriptorSetLayout>> buildSetLayouts(VContext context, int runtimeSizedArrayMaxSize) {
        freeLayouts();
        layouts = new ArrayList<>();
        for (var set : sets) {
            int flags = 0;
            if (set.hasUnsizedArrays()) {
                flags |= VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT;
            }
            var builder = new DescriptorSetLayoutBuilder(flags);
            for (var binding : set.bindings()) {
                if (binding.arraySize > 0) {
                    if (binding.runtimeSized) {
                        builder.binding(binding.binding, binding.descriptorType, runtimeSizedArrayMaxSize,
                                VK_SHADER_STAGE_ALL);
                        builder.setBindingFlags(binding.binding,
                                VK_DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT_BIT
                                        | VK_DESCRIPTOR_BINDING_UPDATE_UNUSED_WHILE_PENDING_BIT
                                        | VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT);
                    } else {
                        builder.binding(binding.binding, binding.descriptorType, binding.arraySize,
                                VK_SHADER_STAGE_ALL);
                        builder.setBindingFlags(binding.binding, VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT);
                    }
                } else {
                    builder.binding(binding.binding, binding.descriptorType, VK_SHADER_STAGE_ALL);
                }
            }
            layouts.add(builder.build(context));
        }
        return layouts;
    }

    public final List<VRef<VDescriptorSetLayout>> getLayouts() {
        return layouts;
    }

    public void freeLayouts() {
        layouts.clear();
    }

    private static void _CHECK_(int status) {
        if (status != 0) {
            throw new IllegalStateException("Got status: " + status);
        }
    }
}

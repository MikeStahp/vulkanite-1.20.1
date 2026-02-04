package me.cortex.vulkanite.acceleration;

//TLAS manager, ingests blas build requests and manages builds and syncs the tlas

import me.cortex.vulkanite.acceleration.blas.BLASBuildResult;
import me.cortex.vulkanite.acceleration.tlas.TLASSectionManager;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.*;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Pair;
import org.joml.Matrix4x3f;
import org.lwjgl.vulkan.*;

import java.util.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages the Top-Level Acceleration Structure (TLAS) for ray tracing.
 * Coordinates section updates, entity geometry, and TLAS building.
 */
public class AccelerationTLASManager {
    private final EntityBlasBuilder entityBlasBuilder;
    private final TLASSectionManager buildDataManager;
    private final VContext context;
    private final int queue;
    private List<Pair<RenderLayer, BufferBuilder.BuiltBuffer>> entityData;

    public AccelerationTLASManager(VContext context, int queue) {
        this.context = context;
        this.queue = queue;
        this.buildDataManager = new TLASSectionManager(context);
        this.buildDataManager.resizeBindlessSet(0);
        this.entityBlasBuilder = new EntityBlasBuilder(context);
    }

    /**
     * Updates sections with new BLAS build results.
     */
    public void updateSections(List<BLASBuildResult> results) {
        for (var result : results) {
            buildDataManager.update(result);
        }
    }

    public void setEntityData(List<Pair<RenderLayer, BufferBuilder.BuiltBuffer>> data) {
        this.entityData = data;
    }

    public void removeSection(RenderSection section) {
        buildDataManager.remove(section);
    }

    /**
     * Builds the TLAS for the current frame.
     * 
     * @param cmd Command buffer to record build commands into
     * @return Reference to the built acceleration structure
     */
    public VRef<VAccelerationStructure> buildTLAS(VCmdBuff cmd) {
        RenderSystem.assertOnRenderThread();

        try (var stack = stackPush()) {
            VkAccelerationStructureGeometryKHR geometry = VkAccelerationStructureGeometryKHR.calloc(stack);

            // Process entity geometry
            if (entityData != null) {
                var entityBuild = entityBlasBuilder.buildBlas(entityData, cmd);

                for (var entityBatch : entityBuild) {
                    if (entityBatch.offsets().isEmpty()) {
                        continue;
                    }

                    var entityASI = VkAccelerationStructureInstanceKHR.calloc(stack)
                            .mask(~0)
                            .instanceShaderBindingTableRecordOffset(1);
                    entityASI.transform().matrix(new Matrix4x3f().getTransposed(stack.mallocFloat(12)));

                    buildDataManager.addEphemeralInstance(cmd, entityASI, entityBatch.structure(),
                            entityBatch.geometry(), entityBatch.offsets());
                    entityBatch.geometry().close();
                    entityBatch.structure().close();
                }
            }

            // Get instance buffer (also builds/updates the geometry desc set)
            var rets = buildDataManager.getInstanceBuffer();
            var instanceBuffer = rets.getLeft();
            int numInstances = rets.getRight();

            // Let the cmd buffer manage the lifetime of the holders & desc set entries
            for (var holderRef : buildDataManager.activeSections.values()) {
                cmd.moveRefGeneric(holderRef.addRefGeneric());
            }

            geometry.sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                    .flags(0);

            geometry.geometry()
                    .instances()
                    .sType$Default()
                    .arrayOfPointers(false);

            geometry.geometry()
                    .instances()
                    .data()
                    .deviceAddress(instanceBuffer.get().deviceAddress());

            // TLAS always rebuild & PREFER_FAST_TRACE according to Nvidia
            var buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack)
                    .sType$Default()
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .pGeometries(VkAccelerationStructureGeometryKHR.create(geometry.address(), 1))
                    .geometryCount(1);

            VkAccelerationStructureBuildSizesInfoKHR buildSizesInfo = VkAccelerationStructureBuildSizesInfoKHR
                    .calloc(stack)
                    .sType$Default();

            int[] instanceCounts = new int[] { numInstances };
            vkGetAccelerationStructureBuildSizesKHR(
                    context.device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo.get(0),
                    stack.ints(instanceCounts),
                    buildSizesInfo);

            var tlas = context.memory.createAcceleration(buildSizesInfo.accelerationStructureSize(),
                    256,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);

            var scratchBuffer = context.memory.createBuffer(buildSizesInfo.buildScratchSize(),
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 256, 0);
            scratchBuffer.get().setDebugUtilsObjectName("TLAS Scratch Buffer");

            buildInfo.dstAccelerationStructure(tlas.get().structure)
                    .scratchData(VkDeviceOrHostAddressKHR.calloc(stack)
                            .deviceAddress(scratchBuffer.get().deviceAddress()));

            var buildRanges = VkAccelerationStructureBuildRangeInfoKHR.calloc(instanceCounts.length, stack);
            for (int count : instanceCounts) {
                buildRanges.get().primitiveCount(count);
            }
            buildRanges.rewind();

            cmd.encodeMemoryBarrier();

            vkCmdBuildAccelerationStructuresKHR(cmd.buffer(),
                    buildInfo,
                    stack.pointers(buildRanges));
            cmd.addBufferRef(instanceBuffer);
            cmd.addBufferRef(scratchBuffer);
            instanceBuffer.close();
            scratchBuffer.close();

            cmd.encodeMemoryBarrier();

            return tlas;
        }
    }

    public VRef<VDescriptorSet> getGeometrySet() {
        return buildDataManager.geometryBufferDescSet.addRef();
    }

    public VRef<VDescriptorSetLayout> getGeometryLayout() {
        return buildDataManager.getGeometryLayout();
    }
}

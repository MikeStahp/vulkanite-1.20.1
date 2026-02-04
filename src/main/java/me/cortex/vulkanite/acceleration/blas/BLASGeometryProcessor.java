package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.SharedQuadVkIndexBuffer;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Processes geometry data for BLAS construction.
 * Handles vertex decoding, geometry setup, and acceleration structure creation.
 */
public class BLASGeometryProcessor {
    private static final long VERTEX_STRIDE = 4 * 3;

    private final VContext context;
    private final BLASBuildWorker.BLASBuildContext buildCtx;
    private final VCmdBuff uploadBuildCmd;

    public BLASGeometryProcessor(VContext context,
            BLASBuildWorker.BLASBuildContext buildCtx,
            VCmdBuff uploadBuildCmd) {
        this.context = context;
        this.buildCtx = buildCtx;
        this.uploadBuildCmd = uploadBuildCmd;
    }

    public void processJob(BLASBuildJob job, int jobIndex,
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfos,
            PointerBuffer buildRanges,
            LongBuffer pAccelerationStructures,
            List<VRef<VAccelerationStructure>> accelerationStructures) {
        var stack = buildCtx.stack;
        var brs = VkAccelerationStructureBuildRangeInfoKHR.calloc(job.geometries().size(), stack);
        var geometryInfos = VkAccelerationStructureGeometryKHR.calloc(job.geometries().size(), stack);
        var maxPrims = stack.callocInt(job.geometries().size());
        buildRanges.put(brs);

        for (int geoIdx = 0; geoIdx < job.geometries().size(); geoIdx++) {
            processGeometry(job, geoIdx, geometryInfos, brs, maxPrims);
        }

        geometryInfos.rewind();
        maxPrims.rewind();

        var bi = buildInfos.get()
                .sType$Default()
                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                        | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR)
                .pGeometries(geometryInfos)
                .geometryCount(job.geometries().size());

        VkAccelerationStructureBuildSizesInfoKHR buildSizesInfo = VkAccelerationStructureBuildSizesInfoKHR
                .calloc(stack)
                .sType$Default();

        vkGetAccelerationStructureBuildSizesKHR(
                context.device,
                VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                bi,
                maxPrims,
                buildSizesInfo);

        var backingBuffer = buildCtx.initialASBufferAllocator.allocate(buildSizesInfo.accelerationStructureSize());
        var structure = context.memory.createAcceleration(backingBuffer.buffer(), backingBuffer.offset(),
                backingBuffer.size(), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);

        var scratch = buildCtx.scratchAllocator.allocate(buildSizesInfo.buildScratchSize());
        uploadBuildCmd.addBufferRef(scratch.buffer());
        bi.scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(scratch.deviceAddress()));
        bi.dstAccelerationStructure(structure.get().structure);

        pAccelerationStructures.put(structure.get().structure);
        accelerationStructures.add(structure);
    }

    private void processGeometry(BLASBuildJob job, int geoIdx,
            VkAccelerationStructureGeometryKHR.Buffer geometryInfos,
            VkAccelerationStructureBuildRangeInfoKHR.Buffer brs,
            java.nio.IntBuffer maxPrims) {
        var stack = buildCtx.stack;
        var geometry = job.geometries().get(geoIdx);
        var geometryInfo = geometryInfos.get().sType$Default();
        var br = brs.get();

        long buildBufferSize = geometry.quadCount() * 4L * VERTEX_STRIDE;
        var buildBuffer = buildCtx.buildBufferAllocator.allocate(buildBufferSize);

        uploadBuildCmd.encodeBufferBarrier(buildBuffer.buffer(), buildBuffer.offset(), buildBuffer.size(),
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        // Decode vertex data via compute shader
        var pushConstant = new long[3];
        var geometryInputBuffer = job.data().geometryBuffer();
        var geometryInputBufferOffset = job.data().bufferOffsets().get(geoIdx);
        pushConstant[0] = geometry.quadCount() * 4L;
        pushConstant[1] = geometryInputBuffer.get().deviceAddress() + geometryInputBufferOffset;
        pushConstant[2] = buildBuffer.deviceAddress();

        if (pushConstant[1] == 0) {
            throw new IllegalStateException("Geometry input buffer address is 0");
        }
        if (pushConstant[2] == 0) {
            throw new IllegalStateException("Build buffer address is 0");
        }

        uploadBuildCmd.pushConstants(0, pushConstant);
        uploadBuildCmd.encodeBufferBarrier(geometryInputBuffer, 0, VK_WHOLE_SIZE, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        uploadBuildCmd.dispatch((geometry.quadCount() * 4 + 255) / 256, 1, 1);

        uploadBuildCmd.encodeBufferBarrier(buildBuffer.buffer(), buildBuffer.offset(), buildBuffer.size(),
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);

        // Setup index buffer
        var indexBuffer = SharedQuadVkIndexBuffer.getIndexBuffer(context, uploadBuildCmd,
                Integer.max(geometry.quadCount(), 30000));
        VkDeviceOrHostAddressConstKHR indexData = indexBuffer.get().deviceAddressConst();
        int indexType = SharedQuadVkIndexBuffer.TYPE;

        uploadBuildCmd.addBufferRef(indexBuffer);

        // Setup vertex data
        VkDeviceOrHostAddressConstKHR vertexData = VkDeviceOrHostAddressConstKHR.calloc(stack)
                .deviceAddress(buildBuffer.deviceAddress());
        int vertexFormat = VK_FORMAT_R32G32B32_SFLOAT;

        geometryInfo.geometry(VkAccelerationStructureGeometryDataKHR.calloc(stack)
                .triangles(VkAccelerationStructureGeometryTrianglesDataKHR.calloc(stack)
                        .sType$Default()
                        .vertexData(vertexData)
                        .vertexFormat(vertexFormat)
                        .vertexStride(VERTEX_STRIDE)
                        .maxVertex(geometry.quadCount() * 4)
                        .indexData(indexData)
                        .indexType(indexType)))
                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(geometry.geometryFlags());

        maxPrims.put(geometry.quadCount() * 2);
        br.primitiveCount(geometry.quadCount() * 2);
    }
}

package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.SharedQuadVkIndexBuffer;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.AccelerationStructurePool;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Processes geometry data for BLAS construction.
 * Handles vertex decoding, geometry setup, and acceleration structure creation.
 */
public class BLASGeometryProcessor {
        private static final Logger LOGGER = LoggerFactory.getLogger(BLASGeometryProcessor.class);
        private static final long VERTEX_STRIDE = 4 * 3;

        private final VContext context;
        private final BLASBuildWorker.BLASBuildContext buildCtx;
        private final VCmdBuff uploadBuildCmd;
        private final AccelerationStructurePool accelerationStructurePool;
        private final boolean compactBlas;
        private final List<BuildInputBarrier> buildInputBarriers = new ArrayList<>();
        private final long[] decodePushConstants = new long[3];

        public BLASGeometryProcessor(VContext context,
                        BLASBuildWorker.BLASBuildContext buildCtx,
                        VCmdBuff uploadBuildCmd,
                        AccelerationStructurePool accelerationStructurePool,
                        boolean compactBlas) {
                this.context = context;
                this.buildCtx = buildCtx;
                this.uploadBuildCmd = uploadBuildCmd;
                this.accelerationStructurePool = accelerationStructurePool;
                this.compactBlas = compactBlas;
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
                        processGeometry(job, geoIdx, geometryInfos, brs, maxPrims, jobIndex);
                }

                geometryInfos.rewind();
                maxPrims.rewind();

                var bi = buildInfos.get()
                                .sType$Default()
                                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                                .flags(buildFlags())
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

                VRef<VAccelerationStructure> structure;
                if (compactBlas) {
                        var backingBuffer = buildCtx.initialASBufferAllocator
                                        .allocate(buildSizesInfo.accelerationStructureSize());
                        structure = context.memory.createAcceleration(backingBuffer.buffer(), backingBuffer.offset(),
                                        backingBuffer.size(), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                } else {
                        structure = accelerationStructurePool.createAcceleration(
                                        buildSizesInfo.accelerationStructureSize(),
                                        VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                }

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
                        java.nio.IntBuffer maxPrims, int jobIndex) {
                var stack = buildCtx.stack;
                var geometry = job.geometries().get(geoIdx);
                var geometryInfo = geometryInfos.get().sType$Default();
                var br = brs.get();

                // Validate geometry data
                if (geometry.quadCount() <= 0) {
                        LOGGER.error("[Job {}][Geo {}] Invalid quadCount: {}", jobIndex, geoIdx, geometry.quadCount());
                        throw new IllegalStateException("Invalid quadCount: " + geometry.quadCount());
                }

                var geometryInputBuffer = job.data().geometryBuffer();
                var geometryInputBufferOffset = job.data().bufferOffsets().get(geoIdx);
                long inputAddress = geometryInputBuffer.get().deviceAddress() + geometryInputBufferOffset;

                if (inputAddress == 0) {
                        LOGGER.error("[Job {}][Geo {}] NULL input buffer address!", jobIndex, geoIdx);
                        throw new IllegalStateException("NULL input buffer address");
                }

                // Sanity check: input address should be aligned
                // Valid GPU buffer addresses must be aligned to 4 bytes
                // Removed minimum address check (0x1000) as valid GPU addresses can vary
                if ((inputAddress & 0x3) != 0) {
                    LOGGER.error("[Job {}][Geo {}] Input buffer address is not 4-byte aligned: 0x{}",
                            jobIndex, geoIdx, Long.toHexString(inputAddress));
                    throw new IllegalStateException(
                            "Input buffer address not aligned: 0x" + Long.toHexString(inputAddress));
                }

                long buildBufferSize = geometry.quadCount() * 4L * VERTEX_STRIDE;
                var buildBuffer = buildCtx.buildBufferAllocator.allocate(buildBufferSize);

                if (buildBuffer.deviceAddress() == 0) {
                        LOGGER.error("[Job {}][Geo {}] NULL build buffer device address!", jobIndex, geoIdx);
                        throw new IllegalStateException("NULL build buffer device address");
                }

                // Decode vertex data via compute shader
                decodePushConstants[0] = geometry.quadCount() * 4L;
                decodePushConstants[1] = inputAddress;
                decodePushConstants[2] = buildBuffer.deviceAddress();
        
                LOGGER.trace("[Job {}][Geo {}] Dispatching compute shader: vertices={}, inputAddr=0x{}, outputAddr=0x{}",
                    jobIndex, geoIdx, decodePushConstants[0], Long.toHexString(decodePushConstants[1]), Long.toHexString(decodePushConstants[2]));

                // Barrier: ensure input geometry buffer is visible to compute shader
                uploadBuildCmd.encodeBufferBarrier(geometryInputBuffer, 0, VK_WHOLE_SIZE,
                                VK_PIPELINE_STAGE_TRANSFER_BIT,
                                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

                // Use VK_SHADER_STAGE_COMPUTE_BIT for more specific push constant range
                uploadBuildCmd.pushConstants(0, decodePushConstants, VK_SHADER_STAGE_COMPUTE_BIT);
                uploadBuildCmd.dispatch((geometry.quadCount() * 4 + 255) / 256, 1, 1);

                buildInputBarriers.add(new BuildInputBarrier(
                                buildBuffer.buffer().addRef(),
                                buildBuffer.offset(),
                                buildBuffer.size()));

                // Setup index buffer
                var indexBuffer = SharedQuadVkIndexBuffer.getIndexBuffer(context, uploadBuildCmd,
                                Integer.max(geometry.quadCount(), 30000));
                VkDeviceOrHostAddressConstKHR indexData = indexBuffer.get().deviceAddressConst();
                int indexType = SharedQuadVkIndexBuffer.TYPE;

                uploadBuildCmd.addBufferRef(indexBuffer);
                indexBuffer.close();

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
                                .maxVertex(geometry.quadCount() * 4 - 1)
                                .indexData(indexData)
                                .indexType(indexType)))
                        .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                        .flags(geometry.geometryFlags());
        
                LOGGER.trace("[Job {}][Geo {}] AS geometry setup: vertices={}, primitives={}",
                        jobIndex, geoIdx, geometry.quadCount() * 4, geometry.quadCount() * 2);
        
                maxPrims.put(geometry.quadCount() * 2);
                br.primitiveCount(geometry.quadCount() * 2);
        }

        public void flushBuildInputBarriers(MemoryStack stack) {
                if (buildInputBarriers.isEmpty()) {
                        return;
                }

                var barriers = VkBufferMemoryBarrier.calloc(buildInputBarriers.size(), stack);
                for (int i = 0; i < buildInputBarriers.size(); i++) {
                        BuildInputBarrier pending = buildInputBarriers.get(i);
                        barriers.get(i)
                                        .sType$Default()
                                        .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                                        .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR)
                                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                                        .buffer(pending.buffer().get().buffer())
                                        .offset(pending.offset())
                                        .size(pending.size());
                        uploadBuildCmd.addBufferRef(pending.buffer());
                        pending.buffer().close();
                }

                vkCmdPipelineBarrier(
                                uploadBuildCmd.buffer(),
                                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                                0,
                                null,
                                barriers,
                                null);
                buildInputBarriers.clear();
        }

        private record BuildInputBarrier(VRef<me.cortex.vulkanite.lib.memory.VBuffer> buffer, long offset, long size) {
        }

        private int buildFlags() {
                return BLASBuildPolicy.staticTerrainBuildFlags(compactBlas);
        }
}

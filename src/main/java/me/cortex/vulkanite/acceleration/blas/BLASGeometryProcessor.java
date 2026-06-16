package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.SharedQuadVkIndexBuffer;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
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
                        processGeometry(job, geoIdx, geometryInfos, brs, maxPrims, jobIndex);
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

                var backingBuffer = buildCtx.initialASBufferAllocator
                                .allocate(buildSizesInfo.accelerationStructureSize());
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

                // Barrier: ensure build buffer is ready for compute shader write
                uploadBuildCmd.encodeBufferBarrier(buildBuffer.buffer(), buildBuffer.offset(), buildBuffer.size(),
                                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

                if (buildBuffer.deviceAddress() == 0) {
                        LOGGER.error("[Job {}][Geo {}] NULL build buffer device address!", jobIndex, geoIdx);
                        throw new IllegalStateException("NULL build buffer device address");
                }

                // Decode vertex data via compute shader
                var pushConstant = new long[3];
                pushConstant[0] = geometry.quadCount() * 4L;
                pushConstant[1] = inputAddress;
                pushConstant[2] = buildBuffer.deviceAddress();
        
                LOGGER.debug("[Job {}][Geo {}] Dispatching compute shader: vertices={}, inputAddr=0x{}, outputAddr=0x{}",
                    jobIndex, geoIdx, pushConstant[0], Long.toHexString(pushConstant[1]), Long.toHexString(pushConstant[2]));

                // Barrier: ensure input geometry buffer is visible to compute shader
                uploadBuildCmd.encodeBufferBarrier(geometryInputBuffer, 0, VK_WHOLE_SIZE,
                                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

                // Use VK_SHADER_STAGE_COMPUTE_BIT for more specific push constant range
                uploadBuildCmd.pushConstants(0, pushConstant, VK_SHADER_STAGE_COMPUTE_BIT);
                uploadBuildCmd.dispatch((geometry.quadCount() * 4 + 255) / 256, 1, 1);

                // CRITICAL: Barrier from COMPUTE_SHADER_BIT to ACCELERATION_STRUCTURE_BUILD_BIT
                // The compute shader writes the build buffer; AS build must wait for it
                uploadBuildCmd.encodeBufferBarrier(buildBuffer.buffer(), buildBuffer.offset(), buildBuffer.size(),
                                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);

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
                                .maxVertex(geometry.quadCount() * 4)
                                .indexData(indexData)
                                .indexType(indexType)))
                        .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                        .flags(geometry.geometryFlags());
        
                LOGGER.debug("[Job {}][Geo {}] AS geometry setup: vertices={}, primitives={}",
                        jobIndex, geoIdx, geometry.quadCount() * 4, geometry.quadCount() * 2);
        
                maxPrims.put(geometry.quadCount() * 2);
                br.primitiveCount(geometry.quadCount() * 2);
        }
}

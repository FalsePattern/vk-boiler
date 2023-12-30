package com.github.knokko.boiler.builder.queue;

import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.util.Set;

import static org.lwjgl.vulkan.KHRVideoDecodeQueue.VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeQueue.VK_QUEUE_VIDEO_DECODE_BIT_KHR;
import static org.lwjgl.vulkan.KHRVideoEncodeQueue.VK_KHR_VIDEO_ENCODE_QUEUE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoEncodeQueue.VK_QUEUE_VIDEO_ENCODE_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;

public class MinimalQueueFamilyMapper implements QueueFamilyMapper {

    @Override
    public QueueFamilyMapping mapQueueFamilies(
            VkQueueFamilyProperties.Buffer queueFamilies,
            Set<String> deviceExtensions,
            boolean[] presentSupport
    ) {
        float[] priorities = { 1f };

        boolean shouldTryEncode = deviceExtensions.contains(VK_KHR_VIDEO_ENCODE_QUEUE_EXTENSION_NAME);
        boolean shouldTryDecode = deviceExtensions.contains(VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME);
        int encodeIndex = -1;
        int decodeIndex = -1;
        for (int familyIndex = 0; familyIndex < queueFamilies.limit(); familyIndex++) {
            int queueFlags = queueFamilies.get(familyIndex).queueFlags();
            boolean hasEncode = shouldTryEncode && (queueFlags & VK_QUEUE_VIDEO_ENCODE_BIT_KHR) != 0;
            boolean hasDecode = shouldTryDecode && (queueFlags & VK_QUEUE_VIDEO_DECODE_BIT_KHR) != 0;
            if (hasEncode && hasDecode) {
                encodeIndex = familyIndex;
                decodeIndex = familyIndex;
                break;
            }

            if (encodeIndex == -1 && hasEncode) encodeIndex = familyIndex;
            if (decodeIndex == -1 && hasDecode) decodeIndex = familyIndex;
        }

        var videoEncode = encodeIndex != -1 ? new QueueFamilyAllocation(encodeIndex, priorities) : null;
        var videoDecode = decodeIndex != -1 ? new QueueFamilyAllocation(decodeIndex, priorities) : null;

        int graphicsIndex = -1;
        int computeIndex = -1;
        int presentIndex = -1;

        for (int familyIndex = 0; familyIndex < queueFamilies.limit(); familyIndex++) {
            int queueFlags = queueFamilies.get(familyIndex).queueFlags();
            boolean hasGraphics = (queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0;
            boolean hasCompute = (queueFlags & VK_QUEUE_COMPUTE_BIT) != 0;
            boolean hasPresent = presentSupport[familyIndex];

            if (hasGraphics && hasCompute && hasPresent) {
                var allocation = new QueueFamilyAllocation(familyIndex, priorities);
                return new QueueFamilyMapping(allocation, allocation, allocation, videoEncode, videoDecode, familyIndex);
            }

            if (graphicsIndex == -1 && hasGraphics) graphicsIndex = familyIndex;
            if (computeIndex == -1 && hasCompute) computeIndex = familyIndex;
            if (hasGraphics && hasCompute) {
                graphicsIndex = familyIndex;
                computeIndex = familyIndex;
            }

            if (presentIndex == -1 && hasPresent) presentIndex = familyIndex;
            if ((hasGraphics || hasCompute) && hasPresent) presentIndex = familyIndex;
        }


        return new QueueFamilyMapping(
                new QueueFamilyAllocation(graphicsIndex, priorities),
                new QueueFamilyAllocation(computeIndex, priorities),
                new QueueFamilyAllocation(graphicsIndex, priorities),
                videoEncode, videoDecode, presentIndex
        );
    }
}

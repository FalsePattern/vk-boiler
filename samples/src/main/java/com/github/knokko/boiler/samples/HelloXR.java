package com.github.knokko.boiler.samples;

import com.github.knokko.boiler.builder.BoilerBuilder;
import com.github.knokko.boiler.builder.instance.ValidationFeatures;
import com.github.knokko.boiler.builder.xr.BoilerXrBuilder;
import com.github.knokko.boiler.commands.CommandRecorder;
import com.github.knokko.boiler.images.VmaImage;
import com.github.knokko.boiler.pipelines.GraphicsPipelineBuilder;
import com.github.knokko.boiler.pipelines.ShaderInfo;
import com.github.knokko.boiler.sync.WaitSemaphore;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.openxr.*;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;

import static com.github.knokko.boiler.exceptions.VulkanFailureException.assertVkSuccess;
import static com.github.knokko.boiler.util.CollectionHelper.createSet;
import static com.github.knokko.boiler.xr.OpenXrFailureException.assertXrSuccess;
import static java.lang.Thread.sleep;
import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memFloatBuffer;
import static org.lwjgl.system.MemoryUtil.memIntBuffer;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.KHRCreateRenderpass2.VK_KHR_CREATE_RENDERPASS_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDepthStencilResolve.VK_KHR_DEPTH_STENCIL_RESOLVE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDynamicRendering.*;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.vkGetPhysicalDeviceFeatures2KHR;
import static org.lwjgl.vulkan.KHRMaintenance2.VK_KHR_MAINTENANCE_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRMultiview.VK_KHR_MULTIVIEW_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK13.*;

public class HelloXR {

    @SuppressWarnings("resource")
    public static void main(String[] args) throws InterruptedException {
        var boiler = new BoilerBuilder(
                VK_API_VERSION_1_0, "HelloXR", 1
        )
                .validation(new ValidationFeatures(false, false, false, true, true))
                .requiredVkInstanceExtensions(createSet(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME))
                .requiredDeviceExtensions(createSet(
                        VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME,
                        VK_KHR_MULTIVIEW_EXTENSION_NAME,
                        VK_KHR_DEPTH_STENCIL_RESOLVE_EXTENSION_NAME,
                        VK_KHR_CREATE_RENDERPASS_2_EXTENSION_NAME,
                        VK_KHR_MAINTENANCE_2_EXTENSION_NAME
                ))
                .printDeviceRejectionInfo()
                .extraDeviceRequirements((physicalDevice, windowSurface, stack) -> {
                    var dynamicRendering = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack);
                    dynamicRendering.sType$Default();

                    var multiview = VkPhysicalDeviceMultiviewFeaturesKHR.calloc(stack);
                    multiview.sType$Default();

                    var features2 = VkPhysicalDeviceFeatures2KHR.calloc(stack);
                    features2.sType$Default();
                    features2.pNext(dynamicRendering);
                    features2.pNext(multiview);

                    vkGetPhysicalDeviceFeatures2KHR(physicalDevice, features2);
                    return dynamicRendering.dynamicRendering() && multiview.multiview();
                })
                .beforeDeviceCreation((ciDevice, physicalDevice, stack) -> {
                    var dynamicRendering = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack);
                    dynamicRendering.sType$Default();
                    dynamicRendering.dynamicRendering(true);

                    var multiview = VkPhysicalDeviceMultiviewFeaturesKHR.calloc(stack);
                    multiview.sType$Default();
                    multiview.multiview(true);

                    ciDevice.pNext(dynamicRendering);
                    ciDevice.pNext(multiview);
                })
                .xr(new BoilerXrBuilder())
                .build();

        var session = boiler.xr().createSession(0, null);

        long swapchainFormat;
        int depthFormat;

        XrSwapchain swapchain;
        XrSpace renderSpace;
        int width, height;
        try (var stack = stackPush()) {
            swapchainFormat = boiler.xr().chooseSwapchainFormat(
                    stack, session,
                    VK_FORMAT_R8G8B8_SRGB, VK_FORMAT_B8G8R8_SRGB,
                    VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_B8G8R8A8_SRGB
            );
            depthFormat = boiler.images.chooseDepthStencilFormat(
                    stack, VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT
            );

            var views = boiler.xr().getViewConfigurationViews(stack, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, true);
            if (views.capacity() != 2) throw new UnsupportedOperationException("Expected 2 views, but got " + views.capacity());

            width = views.recommendedImageRectWidth();
            height = views.recommendedImageRectHeight();

            var ciSwapchain = XrSwapchainCreateInfo.calloc(stack);
            ciSwapchain.type$Default();
            ciSwapchain.createFlags(0);
            ciSwapchain.usageFlags(XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT);
            ciSwapchain.format(swapchainFormat);
            ciSwapchain.sampleCount(1);
            ciSwapchain.width(width);
            ciSwapchain.height(height);
            ciSwapchain.sampleCount(1);
            ciSwapchain.faceCount(1);
            ciSwapchain.arraySize(2);
            ciSwapchain.mipCount(1);

            var pSwapchain = stack.callocPointer(1);
            assertXrSuccess(xrCreateSwapchain(
                    session, ciSwapchain, pSwapchain
            ), "CreateSwapchain", null);
            swapchain = new XrSwapchain(pSwapchain.get(0), session);
            renderSpace = boiler.xr().createReferenceSpace(stack, session, XR_REFERENCE_SPACE_TYPE_STAGE);
        }

        long[] swapchainImages = boiler.xr().getSwapchainImages(swapchain);
        long[] swapchainImageViews = new long[swapchainImages.length];
        VmaImage depthImage;
        try (var stack = stackPush()) {
            for (int index = 0; index < swapchainImages.length; index++) {
                swapchainImageViews[index] = boiler.images.createView(
                        stack, swapchainImages[index], (int) swapchainFormat,
                        VK_IMAGE_ASPECT_COLOR_BIT, 1, 2, "SwapchainView"
                );
            }
            depthImage = boiler.images.create(
                    stack, width, height, depthFormat, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT, VK_SAMPLE_COUNT_1_BIT, 1, 2, true, "DepthImage"
            );
        }

        var commandPool = boiler.commands.createPool(
                VK_COMMAND_POOL_CREATE_TRANSIENT_BIT, boiler.queueFamilies().graphics().index(), "Drawing"
        );
        var commandBuffer = boiler.commands.createPrimaryBuffers(commandPool, 1, "Drawing")[0];
        var fence = boiler.sync.createFences(false, 1, "Drawing")[0];

        int vertexSize = (3 + 3) * 4;
        var vertexBuffer = boiler.buffers.createMapped(
                4 * vertexSize, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, "VertexBuffer"
        );
        var hostVertexBuffer = memFloatBuffer(vertexBuffer.hostAddress(), 4 * 6);
        hostVertexBuffer.put(-1f).put(0f).put(-1f); // vertex1.position = (-1, 0, -1)
        hostVertexBuffer.put(1f).put(0f).put(0f); // vertex1.color = red
        hostVertexBuffer.put(1f).put(0f).put(-1f); // vertex2.position = (1, 0, -1)
        hostVertexBuffer.put(0f).put(1f).put(0f); // vertex2.color = green
        hostVertexBuffer.put(0f).put(0f).put(1f); // vertex3.position = (0, 0, 1)
        hostVertexBuffer.put(0f).put(0f).put(1f); // vertex3.color = blue
        hostVertexBuffer.put(0f).put(1f).put(0f); // vertex4.position = (0, 1, 0)
        hostVertexBuffer.put(0.5f).put(0.5f).put(0.5f); // vertex4.color = grey

        var indexBuffer = boiler.buffers.createMapped(
                4 * 3 * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, "IndexBuffer"
        );
        var hostIndexBuffer = memIntBuffer(indexBuffer.hostAddress(), 4 * 3);
        hostIndexBuffer.put(0).put(1).put(2); // bottom triangle
        hostIndexBuffer.put(0).put(1).put(3);
        hostIndexBuffer.put(1).put(2).put(3);
        hostIndexBuffer.put(2).put(0).put(3);

        var matrixBuffer = boiler.buffers.createMapped(
                5 * 64, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, "MatrixBuffer"
        );

        long descriptorSetLayout;
        long descriptorPool;
        long descriptorSet;
        long pipelineLayout;
        long graphicsPipeline;
        try (var stack = stackPush()) {

            var layoutBindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
            var matricesBinding = layoutBindings.get(0);
            matricesBinding.binding(0);
            matricesBinding.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            matricesBinding.descriptorCount(1);
            matricesBinding.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            matricesBinding.pImmutableSamplers(null);

            descriptorSetLayout = boiler.descriptors.createLayout(stack, layoutBindings, "MatricesLayout");

            var poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            poolSizes.descriptorCount(1);

            var ciDescriptorPool = VkDescriptorPoolCreateInfo.calloc(stack);
            ciDescriptorPool.sType$Default();
            ciDescriptorPool.flags(0);
            ciDescriptorPool.maxSets(1);
            ciDescriptorPool.pPoolSizes(poolSizes);

            var pDescriptorPool = stack.callocLong(1);
            assertVkSuccess(vkCreateDescriptorPool(
                    boiler.vkDevice(), ciDescriptorPool, null, pDescriptorPool
            ), "CreateDescriptorPool", "MatricesPool");
            descriptorPool = pDescriptorPool.get(0);
            descriptorSet = boiler.descriptors.allocate(
                    stack, 1, descriptorPool, "MatricesSet", descriptorSetLayout
            )[0];

            var descriptorWrites = VkWriteDescriptorSet.calloc(1, stack);
            var matrixWrite = descriptorWrites.get(0);
            matrixWrite.sType$Default();
            matrixWrite.dstSet(descriptorSet);
            matrixWrite.dstBinding(0);
            matrixWrite.dstArrayElement(0);
            matrixWrite.descriptorCount(1);
            matrixWrite.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            matrixWrite.pBufferInfo(boiler.descriptors.bufferInfo(stack, matrixBuffer.asBuffer()));

            vkUpdateDescriptorSets(boiler.vkDevice(), descriptorWrites, null);

            var pushConstants = VkPushConstantRange.calloc(1, stack);
            pushConstants.stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            pushConstants.offset(0);
            pushConstants.size(8);

            pipelineLayout = boiler.pipelines.createLayout(
                    stack, pushConstants, "SimplePipelineLayout", descriptorSetLayout
            );
            var vertexShader = boiler.pipelines.createShaderModule(
                    stack, "com/github/knokko/boiler/samples/graphics/xr.vert.spv", "VertexShader"
            );
            var fragmentShader = boiler.pipelines.createShaderModule(
                    stack, "com/github/knokko/boiler/samples/graphics/xr.frag.spv", "FragmentShader"
            );

            var vertexBindings = VkVertexInputBindingDescription.calloc(1, stack);
            vertexBindings.binding(0);
            vertexBindings.stride(vertexSize);
            vertexBindings.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

            var vertexAttributes = VkVertexInputAttributeDescription.calloc(2, stack);
            var positionAttribute = vertexAttributes.get(0);
            positionAttribute.location(0);
            positionAttribute.binding(0);
            positionAttribute.format(VK_FORMAT_R32G32B32_SFLOAT);
            positionAttribute.offset(0);
            var colorAttribute = vertexAttributes.get(1);
            colorAttribute.location(1);
            colorAttribute.binding(0);
            colorAttribute.format(VK_FORMAT_R32G32B32_SFLOAT);
            colorAttribute.offset(12);

            var ciVertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack);
            ciVertexInput.sType$Default();
            ciVertexInput.pVertexBindingDescriptions(vertexBindings);
            ciVertexInput.pVertexAttributeDescriptions(vertexAttributes);

            var viewportSize = VkViewport.calloc(1, stack);
            viewportSize.x(0f);
            viewportSize.y(0f);
            viewportSize.width(width);
            viewportSize.height(height);
            viewportSize.minDepth(0f);
            viewportSize.maxDepth(1f);

            var scissorSize = VkRect2D.calloc(1, stack);
            scissorSize.offset().set(0, 0);
            scissorSize.extent().set(width, height);

            var ciViewport = VkPipelineViewportStateCreateInfo.calloc(stack);
            ciViewport.sType$Default();
            ciViewport.viewportCount(1);
            ciViewport.pViewports(viewportSize);
            ciViewport.scissorCount(1);
            ciViewport.pScissors(scissorSize);

            var dynamicRendering = VkPipelineRenderingCreateInfoKHR.calloc(stack);
            dynamicRendering.sType$Default();
            dynamicRendering.viewMask(3); // TODO Configure?
            dynamicRendering.colorAttachmentCount(1);
            dynamicRendering.pColorAttachmentFormats(stack.ints((int) swapchainFormat));
            dynamicRendering.depthAttachmentFormat(depthFormat);
            dynamicRendering.stencilAttachmentFormat(VK_FORMAT_UNDEFINED);

            var pipelineBuilder = new GraphicsPipelineBuilder(boiler, stack);
            pipelineBuilder.shaderStages(
                    new ShaderInfo(VK_SHADER_STAGE_VERTEX_BIT, vertexShader, null),
                    new ShaderInfo(VK_SHADER_STAGE_FRAGMENT_BIT, fragmentShader, null)
            );
            pipelineBuilder.ciPipeline.pVertexInputState(ciVertexInput);
            pipelineBuilder.simpleInputAssembly();
            pipelineBuilder.ciPipeline.pViewportState(ciViewport);
            pipelineBuilder.simpleRasterization(VK_CULL_MODE_NONE); // TODO Cull back
            pipelineBuilder.noMultisampling();
            pipelineBuilder.simpleDepthStencil(VK_COMPARE_OP_LESS_OR_EQUAL);
            pipelineBuilder.ciPipeline.layout(pipelineLayout);
            pipelineBuilder.noColorBlending(1);
            pipelineBuilder.ciPipeline.pNext(dynamicRendering);

            graphicsPipeline = pipelineBuilder.build("SimplePipeline");

            vkDestroyShaderModule(boiler.vkDevice(), vertexShader, null);
            vkDestroyShaderModule(boiler.vkDevice(), fragmentShader, null);
        }

        XrActionSet actionSet;
        XrAction handPoseAction, handClickAction;
        long pathLeftHand, pathRightHand;
        XrSpace leftHandSpace, rightHandSpace;
        long interactionProfile;
        try (var stack = stackPush()) {
            var ciActionSet = XrActionSetCreateInfo.calloc(stack);
            ciActionSet.type$Default();
            ciActionSet.actionSetName(stack.UTF8("hands"));
            ciActionSet.localizedActionSetName(stack.UTF8("Hand poses and 'click' information"));
            ciActionSet.priority(1);

            var pActionSet = stack.callocPointer(1);
            assertXrSuccess(xrCreateActionSet(
                    boiler.xr().instance, ciActionSet, pActionSet
            ), "CreateActionSet", null);
            actionSet = new XrActionSet(pActionSet.get(0), boiler.xr().instance);

            var pLeftPath = stack.callocLong(1);
            assertXrSuccess(xrStringToPath(
                    boiler.xr().instance, stack.UTF8("/user/hand/left"), pLeftPath
            ), "StringToPath", "left hand");
            pathLeftHand = pLeftPath.get(0);

            var pRightPath = stack.callocLong(1);
            assertXrSuccess(xrStringToPath(
                    boiler.xr().instance, stack.UTF8("/user/hand/right"), pRightPath
            ), "StringToPath", "right hand");
            pathRightHand = pRightPath.get(0);

            var ciPoseAction = XrActionCreateInfo.calloc(stack);
            ciPoseAction.type$Default();
            ciPoseAction.actionName(stack.UTF8("hand_poses"));
            ciPoseAction.actionType(XR_ACTION_TYPE_POSE_INPUT);
            ciPoseAction.countSubactionPaths(2);
            //noinspection SuspiciousNameCombination
            ciPoseAction.subactionPaths(stack.longs(pathLeftHand, pathRightHand));
            ciPoseAction.localizedActionName(stack.UTF8("Hand poses"));

            var pPoseAction = stack.callocPointer(1);
            assertXrSuccess(xrCreateAction(
                    actionSet, ciPoseAction, pPoseAction
            ), "CreateAction", "hand poses");
            handPoseAction = new XrAction(pPoseAction.get(0), actionSet);

            var ciClickAction = XrActionCreateInfo.calloc(stack);
            ciClickAction.type$Default();
            ciClickAction.actionName(stack.UTF8("hands_click"));
            ciClickAction.actionType(XR_ACTION_TYPE_BOOLEAN_INPUT);
            ciClickAction.countSubactionPaths(2);
            //noinspection SuspiciousNameCombination
            ciClickAction.subactionPaths(stack.longs(pathLeftHand, pathRightHand));
            ciClickAction.localizedActionName(stack.UTF8("Holding hands"));

            var pClickAction = stack.callocPointer(1);
            assertXrSuccess(xrCreateAction(
                    actionSet, ciClickAction, pClickAction
            ), "CreateAction", "hand clicks");
            handClickAction = new XrAction(pClickAction.get(0), actionSet);

            var pInteractionProfile = stack.callocLong(1);
            assertXrSuccess(xrStringToPath(
                    boiler.xr().instance, stack.UTF8("/interaction_profiles/khr/simple_controller"), pInteractionProfile
            ), "StringToPath", "Khronos simple controller interaction profile");
            interactionProfile = pInteractionProfile.get(0);

            var suggestedBindings = XrActionSuggestedBinding.calloc(4, stack);

            var pLeftPosePath = stack.callocLong(1);
            assertXrSuccess(xrStringToPath(
                    boiler.xr().instance, stack.UTF8("/user/hand/left/input/grip/pose"), pLeftPosePath
            ), "StringToPath", "left pose");
            suggestedBindings.get(0).action(handPoseAction);
            suggestedBindings.get(0).binding(pLeftPosePath.get(0));

            var pRightPosePath = stack.callocLong(1);
            assertXrSuccess(xrStringToPath(
                    boiler.xr().instance, stack.UTF8("/user/hand/right/input/grip/pose"), pRightPosePath
            ), "StringToPath", "rightpose");
            suggestedBindings.get(1).action(handPoseAction);
            suggestedBindings.get(1).binding(pRightPosePath.get(0));

            var pLeftClickPath = stack.callocLong(1);
            assertXrSuccess(xrStringToPath(
                    boiler.xr().instance, stack.UTF8("/user/hand/left/input/select/click"), pLeftClickPath
            ), "StringToPath", "left click");
            suggestedBindings.get(2).action(handClickAction);
            suggestedBindings.get(2).binding(pLeftClickPath.get(0));

            var pRightClickPath = stack.callocLong(1);
            assertXrSuccess(xrStringToPath(
                    boiler.xr().instance, stack.UTF8("/user/hand/right/input/select/click"), pRightClickPath
            ), "StringToPath", "right click");
            suggestedBindings.get(3).action(handClickAction);
            suggestedBindings.get(3).binding(pRightClickPath.get(0));

            var suggestedInteractionBindings = XrInteractionProfileSuggestedBinding.calloc(stack);
            suggestedInteractionBindings.type$Default();
            suggestedInteractionBindings.interactionProfile(interactionProfile);
            suggestedInteractionBindings.suggestedBindings(suggestedBindings);

            assertXrSuccess(xrSuggestInteractionProfileBindings(
                    boiler.xr().instance, suggestedInteractionBindings
            ), "SuggestInteractionProfileBindings", null);

            var ciLeftSpace = XrActionSpaceCreateInfo.calloc(stack);
            ciLeftSpace.type$Default();
            ciLeftSpace.action(handPoseAction);
            ciLeftSpace.subactionPath(pathLeftHand);
            boiler.xr().setIdentity(ciLeftSpace.poseInActionSpace());

            var pLeftSpace = stack.callocPointer(1);
            assertXrSuccess(xrCreateActionSpace(
                    session, ciLeftSpace, pLeftSpace
            ), "CreateActionSpace", "left hand");
            leftHandSpace = new XrSpace(pLeftSpace.get(0), session);

            var ciRightSpace = XrActionSpaceCreateInfo.calloc(stack);
            ciRightSpace.type$Default();
            ciRightSpace.action(handPoseAction);
            ciRightSpace.subactionPath(pathRightHand);
            boiler.xr().setIdentity(ciRightSpace.poseInActionSpace());

            var pRightSpace = stack.callocPointer(1);
            assertXrSuccess(xrCreateActionSpace(
                    session, ciRightSpace, pRightSpace
            ), "CreateActionSpace", "right hand");
            rightHandSpace = new XrSpace(pRightSpace.get(0), session);

            var aiSession = XrSessionActionSetsAttachInfo.calloc(stack);
            aiSession.type$Default();
            aiSession.actionSets(stack.pointers(actionSet));

            assertXrSuccess(xrAttachSessionActionSets(
                    session, aiSession
            ), "AttachSessionActionSets", null);
        }

        int[] sessionState = { XR_SESSION_STATE_IDLE };
        boolean isSessionRunning = false;
        boolean didRequestExit = false;
        boolean wantsToStop = false;

        Matrix4f[] lastCameraMatrix = { new Matrix4f(), new Matrix4f() };

        // Exit after 10 seconds
        long endTime = System.currentTimeMillis() + 10_000;
        while (true) {
            if (System.currentTimeMillis() > endTime) wantsToStop = true;

            try (var stack = stackPush()) {
                boiler.xr().pollEvents(stack, null, eventData -> {
                    if (eventData.type() == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                        sessionState[0] = XrEventDataSessionStateChanged.nstate(eventData.address());
                        System.out.println("new session state is " + sessionState[0]);
                    }
                });

                if ((didRequestExit && !isSessionRunning)
                        || sessionState[0] == XR_SESSION_STATE_EXITING || sessionState[0] == XR_SESSION_STATE_LOSS_PENDING
                ) {
                    assertXrSuccess(vkQueueWaitIdle(
                            boiler.queueFamilies().graphics().queues().get(0).vkQueue()
                    ), "QueueWaitIdle", "End of last frame");
                    break;
                }

                if (sessionState[0] == XR_SESSION_STATE_STOPPING) {
                    assertXrSuccess(xrEndSession(session), "EndSession", null);
                    isSessionRunning = false;
                    continue;
                }

                if (sessionState[0] == XR_SESSION_STATE_IDLE) {
                    //noinspection BusyWait
                    sleep(500); // TODO Configure
                    continue;
                }

                if (isSessionRunning && !didRequestExit && wantsToStop) {
                    assertXrSuccess(xrRequestExitSession(session), "RequestExitSession", null);
                    didRequestExit = true;
                    continue;
                }

                if (sessionState[0] == XR_SESSION_STATE_READY && !isSessionRunning) {
                    var biSession = XrSessionBeginInfo.calloc(stack);
                    biSession.type$Default();
                    // TODO Make this configurable
                    biSession.primaryViewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO);

                    assertXrSuccess(xrBeginSession(
                            session, biSession
                    ), "BeginSession", null);
                    isSessionRunning = true;
                    continue;
                }

                if (sessionState[0] == XR_SESSION_STATE_SYNCHRONIZED || sessionState[0] == XR_SESSION_STATE_VISIBLE ||
                    sessionState[0] == XR_SESSION_STATE_FOCUSED || sessionState[0] == XR_SESSION_STATE_READY
                ) {
                    var frameState = XrFrameState.calloc(stack);
                    frameState.type$Default();

                    assertXrSuccess(xrWaitFrame(
                            session, null, frameState
                    ), "WaitFrame", null);
                    assertXrSuccess(xrBeginFrame(session, null), "BeginFrame", null);

                    PointerBuffer layers = null;
                    if (frameState.shouldRender()) {
                        var projectionViews = boiler.xr().createProjectionViews(
                                stack, session, renderSpace, 2, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, // TODO Configure
                                frameState.predictedDisplayTime(), (subImage, index) -> {
                                    subImage.swapchain(swapchain);
                                    subImage.imageRect().offset().set(0, 0);
                                    subImage.imageRect().extent().set(width, height);
                                    subImage.imageArrayIndex(index);
                                }
                        );

                        var layer = XrCompositionLayerProjection.calloc(stack);
                        layer.type$Default();
                        layer.layerFlags(0); // TODO Maybe alpha
                        layer.space(renderSpace);
                        if (projectionViews != null) {
                            layer.views(projectionViews);
                            layers = stack.pointers(layer);
                        }

                        IntBuffer pImageIndex = stack.callocInt(1);
                        assertXrSuccess(xrAcquireSwapchainImage(
                                swapchain, null, pImageIndex
                        ), "AcquireSwapchainImage", null);
                        int swapchainImageIndex = pImageIndex.get(0);

                        Matrix4f[] cameraMatrix = { lastCameraMatrix[0], lastCameraMatrix[1] };
                        if (projectionViews != null) {
                            for (int index = 0; index < cameraMatrix.length; index++) {
                                // If the position tracker is working, we should use it to create the camera matrix
                                XrCompositionLayerProjectionView projectionView = projectionViews.get(index);

                                Matrix4f projectionMatrix = boiler.xr().createProjectionMatrix(
                                        projectionView.fov(), 0.01f, 100f
                                );

                                Matrix4f viewMatrix = new Matrix4f();

                                XrVector3f position = projectionView.pose().position$();
                                XrQuaternionf orientation = projectionView.pose().orientation();

                                viewMatrix.translationRotateScaleInvert(
                                        position.x(), position.y(), position.z(),
                                        orientation.x(), orientation.y(), orientation.z(), orientation.w(),
                                        1, 1, 1
                                );

                                cameraMatrix[index] = projectionMatrix.mul(viewMatrix);
                            }
                        }

                        lastCameraMatrix = cameraMatrix;

                        var activeActionSets = XrActiveActionSet.calloc(1, stack);
                        activeActionSets.actionSet(actionSet);
                        activeActionSets.subactionPath(XR_NULL_PATH);

                        var syncInfo = XrActionsSyncInfo.calloc(stack);
                        syncInfo.type$Default();
                        syncInfo.countActiveActionSets(1);
                        syncInfo.activeActionSets(activeActionSets);

                        assertXrSuccess(xrSyncActions(
                                session, syncInfo
                        ), "SyncActions", null);

                        var leftLocation = XrSpaceLocation.calloc(stack);
                        leftLocation.type$Default();

                        var rightLocation = XrSpaceLocation.calloc(stack);
                        rightLocation.type$Default();

                        assertXrSuccess(xrLocateSpace(
                                leftHandSpace, renderSpace, frameState.predictedDisplayTime(), leftLocation
                        ), "LocateSpace", "left hand");
                        assertXrSuccess(xrLocateSpace(
                                rightHandSpace, renderSpace, frameState.predictedDisplayTime(), rightLocation
                        ), "LocateSpace", "right hand");

                        Vector3f leftPosition = null;
                        Vector3f rightPosition = null;

                        if ((leftLocation.locationFlags() & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0) {
                            var pos = leftLocation.pose().position$();
                            leftPosition = new Vector3f(pos.x(), pos.y(), pos.z());
                        }
                        if ((rightLocation.locationFlags() & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0) {
                            var pos = rightLocation.pose().position$();
                            rightPosition = new Vector3f(pos.x(), pos.y(), pos.z());
                        }

                        Quaternionf leftRotation = null;
                        Quaternionf rightRotation = null;
                        if ((leftLocation.locationFlags() & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0) {
                            var rot = leftLocation.pose().orientation();
                            leftRotation = new Quaternionf(rot.x(), rot.y(), rot.z(), rot.w());
                        }

                        if ((rightLocation.locationFlags() & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0) {
                            var rot = rightLocation.pose().orientation();
                            rightRotation = new Quaternionf(rot.x(), rot.y(), rot.z(), rot.w());
                        }

                        Matrix4f leftHandMatrix = null;
                        if (leftPosition != null) {
                            leftHandMatrix = new Matrix4f().translate(leftPosition);

                            if (leftRotation != null) {
                                leftHandMatrix.rotate(leftRotation);
                            }
                            leftHandMatrix.scale(0.1f);
                        }

                        Matrix4f rightHandMatrix = null;
                        if (rightPosition != null) {
                            rightHandMatrix = new Matrix4f().translate(rightPosition);

                            if (rightRotation != null) {
                                rightHandMatrix.rotate(rightRotation);
                            }
                            rightHandMatrix.scale(0.1f);
                        }

                        var wiSwapchain = XrSwapchainImageWaitInfo.calloc(stack);
                        wiSwapchain.type$Default();
                        wiSwapchain.timeout(1_000_000_000L); // TODO Configure

                        assertXrSuccess(xrWaitSwapchainImage(
                                swapchain, wiSwapchain
                        ), "WaitSwapchainImage", null);

                        var colorAttachments = VkRenderingAttachmentInfoKHR.calloc(1, stack);
                        colorAttachments.sType$Default();
                        colorAttachments.imageView(swapchainImageViews[swapchainImageIndex]);
                        colorAttachments.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                        colorAttachments.resolveMode(VK_RESOLVE_MODE_NONE);
                        colorAttachments.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
                        colorAttachments.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                        colorAttachments.clearValue().color().float32(stack.floats(1f, 0f, 0f, 1f));

                        var depthAttachment = VkRenderingAttachmentInfoKHR.calloc(stack);
                        depthAttachment.sType$Default();
                        depthAttachment.imageView(depthImage.vkImageView());
                        depthAttachment.imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                        depthAttachment.resolveMode(VK_RESOLVE_MODE_NONE);
                        depthAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
                        depthAttachment.storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
                        depthAttachment.clearValue().depthStencil().depth(1f);

                        var dynamicRenderingInfo = VkRenderingInfoKHR.calloc(stack);
                        dynamicRenderingInfo.sType$Default();
                        dynamicRenderingInfo.renderArea().offset().set(0, 0);
                        dynamicRenderingInfo.renderArea().extent().set(width, height);
                        dynamicRenderingInfo.layerCount(2);
                        dynamicRenderingInfo.viewMask(3); // TODO Configure?
                        dynamicRenderingInfo.pColorAttachments(colorAttachments);
                        dynamicRenderingInfo.pDepthAttachment(depthAttachment);

                        var commands = CommandRecorder.begin(commandBuffer, boiler, stack, "Drawing");
                        vkCmdBeginRenderingKHR(commandBuffer, dynamicRenderingInfo);

                        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);
                        vkCmdBindDescriptorSets(
                                commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                pipelineLayout, 0, stack.longs(descriptorSet), null
                        );
                        vkCmdBindVertexBuffers(
                                commandBuffer, 0,
                                stack.longs(vertexBuffer.vkBuffer()), stack.longs(0)
                        );
                        vkCmdBindIndexBuffer(commandBuffer, indexBuffer.vkBuffer(), 0, VK_INDEX_TYPE_UINT32);

                        var hostMatrixBuffer = memFloatBuffer(matrixBuffer.hostAddress(), 5 * 16);
                        cameraMatrix[0].get(0, hostMatrixBuffer);
                        cameraMatrix[1].get(16, hostMatrixBuffer);
                        new Matrix4f().get(32, hostMatrixBuffer);
                        if (leftHandMatrix != null) leftHandMatrix.get(48, hostMatrixBuffer);
                        if (rightHandMatrix != null) rightHandMatrix.get(64, hostMatrixBuffer);

                        var pushConstants = stack.callocInt(2);

                        pushConstants.put(0, 0);
                        pushConstants.put(1, 0);
                        vkCmdPushConstants(
                                commandBuffer, pipelineLayout,
                                VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_VERTEX_BIT, 0, pushConstants
                        );
                        vkCmdDrawIndexed(commandBuffer, 3, 1, 0, 0, 0);

                        var pClick = XrActionStateBoolean.calloc(stack);
                        pClick.type$Default();

                        var giClick = XrActionStateGetInfo.calloc(stack);
                        giClick.type$Default();
                        giClick.action(handClickAction);
                        giClick.subactionPath(pathLeftHand);
                        if (leftHandMatrix != null) {
                            assertXrSuccess(xrGetActionStateBoolean(
                                    session, giClick, pClick
                            ), "GetActionStateBoolean", "left click");

                            pushConstants.put(0, pClick.currentState() ? 0 : 1);
                            pushConstants.put(1, 1);
                            vkCmdPushConstants(
                                    commandBuffer, pipelineLayout,
                                    VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_VERTEX_BIT, 0, pushConstants
                            );
                            vkCmdDrawIndexed(commandBuffer, 12, 1, 0, 0, 0);
                        }
                        if (rightHandMatrix != null) {
                            giClick.subactionPath(pathRightHand);
                            assertXrSuccess(xrGetActionStateBoolean(
                                    session, giClick, pClick
                            ), "GetActionStateBoolean", "right click");

                            pushConstants.put(0, pClick.currentState() ? 0 : 1);
                            pushConstants.put(1, 2);
                            vkCmdPushConstants(
                                    commandBuffer, pipelineLayout,
                                    VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_VERTEX_BIT, 0, pushConstants
                            );
                            vkCmdDrawIndexed(commandBuffer, 12, 1, 0, 0, 0);
                        }

                        vkCmdEndRenderingKHR(commandBuffer);
                        commands.end();

                        boiler.queueFamilies().graphics().queues().get(0).submit(
                                commandBuffer, "Drawing", new WaitSemaphore[0], fence
                        );

                        assertVkSuccess(vkWaitForFences(
                                boiler.vkDevice(), stack.longs(fence), true, 1_000_000_000L // TODO Configure
                        ), "WaitForFences", "Drawing");
                        assertVkSuccess(vkResetFences(
                                boiler.vkDevice(), stack.longs(fence)
                        ), "ResetFences", "Drawing");
                        assertVkSuccess(vkResetCommandPool(
                                boiler.vkDevice(), commandPool, 0
                        ), "ResetCommandPool", "Drawing");
                        assertXrSuccess(xrReleaseSwapchainImage(
                                swapchain, null
                        ), "ReleaseSwapchainImage", null);
                    }

                    var frameEnd = XrFrameEndInfo.calloc(stack);
                    frameEnd.type$Default();
                    frameEnd.displayTime(frameState.predictedDisplayTime());
                    frameEnd.environmentBlendMode(XR_ENVIRONMENT_BLEND_MODE_OPAQUE); // TODO Configure
                    frameEnd.layerCount(layers != null ? layers.remaining() : 0);
                    frameEnd.layers(layers);

                    assertXrSuccess(xrEndFrame(session, frameEnd), "EndFrame", null);
                }
            }
        }

        vkDestroyFence(boiler.vkDevice(), fence, null);
        vkDestroyCommandPool(boiler.vkDevice(), commandPool, null);
        vkDestroyPipeline(boiler.vkDevice(), graphicsPipeline, null);
        vkDestroyPipelineLayout(boiler.vkDevice(), pipelineLayout, null);
        vkDestroyDescriptorPool(boiler.vkDevice(), descriptorPool, null);
        vkDestroyDescriptorSetLayout(boiler.vkDevice(), descriptorSetLayout, null);
        for (long imageView : swapchainImageViews) {
            vkDestroyImageView(boiler.vkDevice(), imageView, null);
        }

        vertexBuffer.destroy(boiler.vmaAllocator());
        indexBuffer.destroy(boiler.vmaAllocator());
        matrixBuffer.destroy(boiler.vmaAllocator());
        vkDestroyImageView(boiler.vkDevice(), depthImage.vkImageView(), null);
        vmaDestroyImage(boiler.vmaAllocator(), depthImage.vkImage(), depthImage.vmaAllocation());

        assertXrSuccess(xrDestroySpace(leftHandSpace), "DestroySpace", "left hand");
        assertXrSuccess(xrDestroySpace(rightHandSpace), "DestroySpace", "right hand");
        assertXrSuccess(xrDestroyAction(handPoseAction), "DestroyAction", "hand pose");
        assertXrSuccess(xrDestroyAction(handClickAction), "DestroyAction", "hand click");
        assertXrSuccess(xrDestroyActionSet(actionSet), "DestroyActionSet", null);

        assertXrSuccess(xrDestroySpace(renderSpace), "DestroySpace", "renderSpace");
        assertXrSuccess(xrDestroySwapchain(swapchain), "DestroySwapchain", null);

        assertXrSuccess(xrDestroySession(session), "DestroySession", null);

        boiler.destroyInitialObjects();
    }
}

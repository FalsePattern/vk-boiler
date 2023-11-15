package com.github.knokko.boiler.builder;

import com.github.knokko.boiler.builder.instance.ValidationFeatures;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.*;

import java.util.HashSet;
import java.util.Objects;

import static com.github.knokko.boiler.util.CollectionHelper.createSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTValidationFeatures.*;
import static org.lwjgl.vulkan.KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;
import static org.lwjgl.vulkan.VK10.VK_MAKE_VERSION;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;

public class TestBoilerBuilder {

    @Test
    public void testSimpleInstanceBuilder() {
        boolean[] pDidCallInstanceCreator = { false };
        var boiler = new BoilerBuilder(
                VK_API_VERSION_1_0,
                "TestSimpleVulkan1.0",
                VK_MAKE_VERSION(1, 0, 0)
        ).vkInstanceCreator((stack, ciInstance) -> {
            assertEquals(0L, ciInstance.pNext());

            var appInfo = Objects.requireNonNull(ciInstance.pApplicationInfo());
            assertEquals("TestSimpleVulkan1.0", appInfo.pApplicationNameString());
            assertEquals(VK_API_VERSION_1_0, appInfo.apiVersion());
            pDidCallInstanceCreator[0] = true;
            return BoilerBuilder.DEFAULT_VK_INSTANCE_CREATOR.vkCreateInstance(stack, ciInstance);
        }).build();

        assertTrue(pDidCallInstanceCreator[0]);
        assertThrows(UnsupportedOperationException.class, boiler::glfwWindow);

        boiler.destroyInitialObjects();
    }

    @Test
    @SuppressWarnings("resource")
    public void testComplexInstanceBuilder() {
        boolean[] pDidCallInstanceCreator = { false };
        boolean[] pDidCallDeviceCreator = { false };

        var boiler = new BoilerBuilder(
                VK_API_VERSION_1_3, "TestComplexVulkan1.2", VK_MAKE_VERSION(1, 1, 1)
        )
                .engine("TestEngine", VK_MAKE_VERSION(0, 8, 4))
                .featurePicker12((stack, supported, toEnable) -> {
                    // This feature has 100% coverage according to Vulkan hardware database
                    assertTrue(supported.imagelessFramebuffer());
                    toEnable.imagelessFramebuffer(true);
                })
                .featurePicker13((stack, supported, toEnable) -> {
                    // Dynamic rendering is required in VK1.3
                    assertTrue(supported.dynamicRendering());
                    toEnable.dynamicRendering(true);
                })
                .requiredVkInstanceExtensions(createSet(VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME))
                .desiredVkInstanceExtensions(createSet(VK_KHR_SURFACE_EXTENSION_NAME))
                .validation(new ValidationFeatures(
                        true, true, false, false, false
                ))
                .vkInstanceCreator((stack, ciInstance) -> {
                    pDidCallInstanceCreator[0] = true;

                    var validationFeatures = VkValidationFeaturesEXT.create(ciInstance.pNext());
                    assertEquals(VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT, validationFeatures.sType());

                    var validationFlags = Objects.requireNonNull(validationFeatures.pEnabledValidationFeatures());
                    assertEquals(2, validationFeatures.enabledValidationFeatureCount());
                    assertEquals(VK_VALIDATION_FEATURE_ENABLE_GPU_ASSISTED_EXT, validationFlags.get(0));
                    assertEquals(VK_VALIDATION_FEATURE_ENABLE_GPU_ASSISTED_RESERVE_BINDING_SLOT_EXT, validationFlags.get(1));

                    var appInfo = Objects.requireNonNull(ciInstance.pApplicationInfo());
                    assertEquals("TestComplexVulkan1.2", appInfo.pApplicationNameString());
                    assertEquals(VK_API_VERSION_1_3, appInfo.apiVersion());
                    assertEquals("TestEngine", appInfo.pEngineNameString());
                    assertEquals(VK_MAKE_VERSION(0, 8, 4), appInfo.engineVersion());

                    assertEquals(1, ciInstance.enabledLayerCount());
                    assertEquals(
                            "VK_LAYER_KHRONOS_validation",
                            memUTF8(Objects.requireNonNull(ciInstance.ppEnabledLayerNames()).get(0))
                    );

                    var pExtensions = Objects.requireNonNull(ciInstance.ppEnabledExtensionNames());
                    var actualExtensions = new HashSet<String>(ciInstance.enabledExtensionCount());
                    for (int index = 0; index < pExtensions.limit(); index++) {
                        actualExtensions.add(memUTF8(pExtensions.get(index)));
                    }

                    var expectedExtensions = createSet(
                            VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME,
                            VK_EXT_DEBUG_UTILS_EXTENSION_NAME,
                            VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME,
                            VK_KHR_SURFACE_EXTENSION_NAME
                    );

                    // Portability enumeration is optional, and depends on the current graphics driver
                    if (ciInstance.flags() == VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR) {
                        expectedExtensions.add(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
                    }

                    assertEquals(expectedExtensions, actualExtensions);

                    return BoilerBuilder.DEFAULT_VK_INSTANCE_CREATOR.vkCreateInstance(stack, ciInstance);
                })
                .vkDeviceCreator((stack, physicalDevice, deviceExtensions, ciDevice) -> {
                    VkPhysicalDeviceVulkan11Features enabledFeatures11 = null;
                    VkPhysicalDeviceVulkan12Features enabledFeatures12 = null;
                    VkPhysicalDeviceVulkan13Features enabledFeatures13 = null;

                    var nextStruct = VkBaseInStructure.createSafe(ciDevice.pNext());
                    while (nextStruct != null) {
                        if (nextStruct.sType() == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES) {
                            enabledFeatures11 = VkPhysicalDeviceVulkan11Features.create(nextStruct.address());
                        }
                        if (nextStruct.sType() == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES) {
                            enabledFeatures12 = VkPhysicalDeviceVulkan12Features.create(nextStruct.address());
                        }
                        if (nextStruct.sType() == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES) {
                            enabledFeatures13 = VkPhysicalDeviceVulkan13Features.create(nextStruct.address());
                        }
                        nextStruct = nextStruct.pNext();
                    }

                    assertNull(enabledFeatures11);
                    assertNotNull(enabledFeatures12);
                    assertTrue(enabledFeatures12.imagelessFramebuffer());
                    assertNotNull(enabledFeatures13);
                    assertTrue(enabledFeatures13.dynamicRendering());

                    pDidCallDeviceCreator[0] = true;
                    return BoilerBuilder.DEFAULT_VK_DEVICE_CREATOR.vkCreateDevice(stack, physicalDevice, deviceExtensions, ciDevice);
                })
                .build();

        boiler.destroyInitialObjects();
        assertTrue(pDidCallInstanceCreator[0]);
        assertTrue(pDidCallDeviceCreator[0]);
    }
}
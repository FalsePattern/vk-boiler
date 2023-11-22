package com.github.knokko.boiler.builder.instance;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

@FunctionalInterface
public interface VkInstanceCreator {

    VkInstance vkCreateInstance(VkInstanceCreateInfo ciInstance, MemoryStack stack);
}

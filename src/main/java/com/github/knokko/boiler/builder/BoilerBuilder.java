package com.github.knokko.boiler.builder;

import com.github.knokko.boiler.builder.device.*;
import com.github.knokko.boiler.builder.instance.PreVkInstanceCreator;
import com.github.knokko.boiler.builder.instance.ValidationFeatures;
import com.github.knokko.boiler.builder.instance.VkInstanceCreator;
import com.github.knokko.boiler.builder.queue.MinimalQueueFamilyMapper;
import com.github.knokko.boiler.builder.queue.QueueFamilyMapper;
import com.github.knokko.boiler.builder.xr.BoilerXrBuilder;
import com.github.knokko.boiler.debug.ValidationException;
import com.github.knokko.boiler.exceptions.*;
import com.github.knokko.boiler.instance.BoilerInstance;
import com.github.knokko.boiler.surface.WindowSurface;
import com.github.knokko.boiler.swapchain.SwapchainSettings;
import com.github.knokko.boiler.util.CollectionHelper;
import com.github.knokko.boiler.xr.XrBoiler;
import org.lwjgl.vulkan.*;

import java.util.*;
import java.util.function.Consumer;

import static com.github.knokko.boiler.builder.BoilerSwapchainBuilder.createSurface;
import static com.github.knokko.boiler.exceptions.VulkanFailureException.assertVkSuccess;
import static com.github.knokko.boiler.util.CollectionHelper.decodeStringSet;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTSurfaceMaintenance1.VK_EXT_SURFACE_MAINTENANCE_1_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTSwapchainMaintenance1.VK_EXT_SWAPCHAIN_MAINTENANCE_1_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTValidationFeatures.*;
import static org.lwjgl.vulkan.KHRBindMemory2.VK_KHR_BIND_MEMORY_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDedicatedAllocation.VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRGetMemoryRequirements2.VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.vkGetPhysicalDeviceFeatures2KHR;
import static org.lwjgl.vulkan.KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

public class BoilerBuilder {

    public static final VkInstanceCreator DEFAULT_VK_INSTANCE_CREATOR = (ciInstance, stack) -> {
        var pInstance = stack.callocPointer(1);
        assertVkSuccess(vkCreateInstance(ciInstance, null, pInstance), "CreateInstance", "BoilerBuilder");
        return new VkInstance(pInstance.get(0), ciInstance);
    };

    public static final VkDeviceCreator DEFAULT_VK_DEVICE_CREATOR = (ciDevice, instanceExtensions, physicalDevice, stack) -> {
        var pDevice = stack.callocPointer(1);
        assertVkSuccess(vkCreateDevice(physicalDevice, ciDevice, null, pDevice), "CreateDevice", "BoilerBuilder");
        return new VkDevice(pDevice.get(0), physicalDevice, ciDevice);
    };

    final int apiVersion;
    final String applicationName;
    final int applicationVersion;

    long defaultTimeout = 1_000_000_000L;

    record GLFWWindowRegistration(long window, int width, int height, BoilerSwapchainBuilder swapchainBuilder) {}

    final List<GLFWWindowRegistration> windowsRegistrations = new ArrayList<>();
    long[] windows;
    boolean initGLFW = true;

    BoilerXrBuilder xrBuilder;

    String engineName = "VkBoiler";
    int engineVersion = VK_MAKE_VERSION(0, 1, 0);

    final Set<String> desiredVulkanLayers = new HashSet<>();
    final Set<String> requiredVulkanLayers = new HashSet<>();

    final Set<String> desiredVulkanInstanceExtensions = new HashSet<>();
    final Set<String> requiredVulkanInstanceExtensions = new HashSet<>();

    final Set<String> desiredVulkanDeviceExtensions = new HashSet<>();
    final Set<String> requiredVulkanDeviceExtensions = new HashSet<>();

    ValidationFeatures validationFeatures = null;
    boolean forbidValidationErrors = false;

    VkInstanceCreator vkInstanceCreator = DEFAULT_VK_INSTANCE_CREATOR;
    Collection<PreVkInstanceCreator> preInstanceCreators = new ArrayList<>();
    PhysicalDeviceSelector deviceSelector = new SimpleDeviceSelector(
            VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU,
            VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU
    );
    VkDeviceCreator vkDeviceCreator = DEFAULT_VK_DEVICE_CREATOR;
    Collection<PreVkDeviceCreator> preDeviceCreators = new ArrayList<>();

    Collection<FeaturePicker10> vkDeviceFeaturePicker10 = new ArrayList<>();
    Collection<FeaturePicker11> vkDeviceFeaturePicker11 = new ArrayList<>();
    Collection<FeaturePicker12> vkDeviceFeaturePicker12 = new ArrayList<>();
    Collection<FeaturePicker13> vkDeviceFeaturePicker13 = new ArrayList<>();

    Collection<RequiredFeatures10> vkRequiredFeatures10 = new ArrayList<>();
    Collection<RequiredFeatures11> vkRequiredFeatures11 = new ArrayList<>();
    Collection<RequiredFeatures12> vkRequiredFeatures12 = new ArrayList<>();
    Collection<RequiredFeatures13> vkRequiredFeatures13 = new ArrayList<>();

    Collection<ExtraDeviceRequirements> extraDeviceRequirements = new ArrayList<>();

    boolean printDeviceRejectionInfo = false;

    QueueFamilyMapper queueFamilyMapper = new MinimalQueueFamilyMapper();

    private boolean didBuild = false;

    public BoilerBuilder(int apiVersion, String applicationName, int applicationVersion) {
        if (VK_API_VERSION_PATCH(apiVersion) != 0) throw new IllegalArgumentException("Patch of API version must be 0");
        if (VK_API_VERSION_VARIANT(apiVersion) != 0) throw new IllegalArgumentException("Variant of API version must be 0");

        this.apiVersion = apiVersion;
        this.applicationName = applicationName;
        this.applicationVersion = applicationVersion;

        this.desiredVulkanInstanceExtensions.add(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
    }

    public BoilerBuilder defaultTimeout(long defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
        return this;
    }

    private boolean deprecatedWindowSafe = true;

    @Deprecated
    public BoilerBuilder window(long window, int width, int height, BoilerSwapchainBuilder swapchainBuilder) {
        if (!deprecatedWindowSafe)
            throw new IllegalStateException("Do not use the deprecated window method if you've already used the multiwindow one!");
        var reg = new GLFWWindowRegistration(window, width, height, swapchainBuilder);
        if (windowsRegistrations.isEmpty())
            windowsRegistrations.add(reg);
        else
            windowsRegistrations.set(0, reg);
        return this;
    }

    /**
     * If all of {@code window}, {@code width}, and {@code height} are 0, no window will be created.
     * @param window The GLFW window to use, or 0 to create a new one of the given size
     * @param width The width of the window content, in pixels
     * @param height The height of the window content, in pixels
     * @param swapchainBuilder Specifies the desired configuration of the swapchain to be created. Can be null
     *                         if no window is created.
     * @param indexOutput (output) The unique index of this window. Also used for indexing into swapchains in the built boiler.
     * @return this
     */
    public BoilerBuilder window(long window, int width, int height, BoilerSwapchainBuilder swapchainBuilder, Consumer<Integer> indexOutput) {
        deprecatedWindowSafe = false;
        var index = windowsRegistrations.size();
        indexOutput.accept(index);
        windowsRegistrations.add(new GLFWWindowRegistration(window , width, height, swapchainBuilder));
        return this;
    }

    public BoilerBuilder xr(BoilerXrBuilder xrBuilder) {
        this.xrBuilder = xrBuilder;
        return this;
    }

    public BoilerBuilder physicalDeviceSelector(PhysicalDeviceSelector selector) {
        this.deviceSelector = selector;
        return this;
    }

    /**
     * <p>
     *      Call this when you want the BoilerBuilder to create the window, but without initializing GLFW (e.g. when you
     *      want to initialize GLFW yourself).
     * </p>
     * <p>
     *     This method has no effect when the BoilerBuilder does <b>not</b> create a window (in that case, it won't
     *     initialize GLFW anyway).
     * </p>
     * @return this
     */
    public BoilerBuilder dontInitGLFW() {
        this.initGLFW = false;
        return this;
    }

    public BoilerBuilder engine(String engineName, int engineVersion) {
        this.engineName = engineName;
        this.engineVersion = engineVersion;
        return this;
    }

    public BoilerBuilder desiredVkLayers(Collection<String> desiredLayers) {
        desiredVulkanLayers.addAll(desiredLayers);
        return this;
    }

    public BoilerBuilder requiredVkLayers(Collection<String> requiredLayers) {
        requiredVulkanLayers.addAll(requiredLayers);
        return this;
    }

    public BoilerBuilder desiredVkInstanceExtensions(Collection<String> instanceExtensions) {
        desiredVulkanInstanceExtensions.addAll(instanceExtensions);
        return this;
    }

    public BoilerBuilder requiredVkInstanceExtensions(Collection<String> instanceExtensions) {
        requiredVulkanInstanceExtensions.addAll(instanceExtensions);
        return this;
    }

    public BoilerBuilder desiredVkDeviceExtensions(Collection<String> deviceExtensions) {
        desiredVulkanDeviceExtensions.addAll(deviceExtensions);
        return this;
    }

    public BoilerBuilder requiredDeviceExtensions(Collection<String> deviceExtensions) {
        requiredVulkanDeviceExtensions.addAll(deviceExtensions);
        return this;
    }

    public BoilerBuilder validation(ValidationFeatures validationFeatures) {
        this.validationFeatures = validationFeatures;
        return this;
    }

    public BoilerBuilder validation() {
        if (this.apiVersion == VK_API_VERSION_1_0) {
            return this.validation(new ValidationFeatures(
                    false, false, true, true, true
            ));
        } else {
            return this.validation(new ValidationFeatures(
                    true, true, false, true, true
            ));
        }
    }

    public BoilerBuilder forbidValidationErrors() {
        this.forbidValidationErrors = true;
        return this;
    }

    public BoilerBuilder apiDump() {
        return requiredVkLayers(CollectionHelper.createSet("VK_LAYER_LUNARG_api_dump"));
    }

    public BoilerBuilder vkInstanceCreator(VkInstanceCreator creator) {
        if (this.vkInstanceCreator != DEFAULT_VK_INSTANCE_CREATOR) {
            throw new IllegalStateException("Attempted to set multiple instance creators");
        }
        this.vkInstanceCreator = creator;
        return this;
    }

    public BoilerBuilder beforeInstanceCreation(PreVkInstanceCreator preCreator) {
        this.preInstanceCreators.add(preCreator);
        return this;
    }

    public BoilerBuilder vkDeviceCreator(VkDeviceCreator creator) {
        if (this.vkDeviceCreator != DEFAULT_VK_DEVICE_CREATOR) {
            throw new IllegalStateException("Attempted to set multiple device creators");
        }
        this.vkDeviceCreator = creator;
        return this;
    }

    public BoilerBuilder beforeDeviceCreation(PreVkDeviceCreator preCreator) {
        this.preDeviceCreators.add(preCreator);
        return this;
    }

    public BoilerBuilder queueFamilyMapper(QueueFamilyMapper mapper) {
        this.queueFamilyMapper = mapper;
        return this;
    }

    private void checkApiVersion(int required) {
        if (VK_API_VERSION_MAJOR(apiVersion) < VK_API_VERSION_MAJOR(required)) {
            throw new UnsupportedOperationException("API major version is too low for this feature");
        }
        if (VK_API_VERSION_MAJOR(apiVersion) == VK_API_VERSION_MAJOR(required) &&
            VK_API_VERSION_MINOR(apiVersion) < VK_API_VERSION_MINOR(required)) {
            throw new UnsupportedOperationException("API minor version is too low for this feature");
        }
    }

    public BoilerBuilder featurePicker10(FeaturePicker10 picker) {
        this.vkDeviceFeaturePicker10.add(picker);
        return this;
    }

    public BoilerBuilder featurePicker11(FeaturePicker11 picker) {
        checkApiVersion(VK_API_VERSION_1_1);
        this.vkDeviceFeaturePicker11.add(picker);
        return this;
    }

    public BoilerBuilder featurePicker12(FeaturePicker12 picker) {
        checkApiVersion(VK_API_VERSION_1_2);
        this.vkDeviceFeaturePicker12.add(picker);
        return this;
    }

    public BoilerBuilder featurePicker13(FeaturePicker13 picker) {
        checkApiVersion(VK_API_VERSION_1_3);
        this.vkDeviceFeaturePicker13.add(picker);
        return this;
    }

    public BoilerBuilder requiredFeatures10(RequiredFeatures10 requiredFeatures) {
        this.vkRequiredFeatures10.add(requiredFeatures);
        return this;
    }

    public BoilerBuilder requiredFeatures11(RequiredFeatures11 requiredFeatures) {
        checkApiVersion(VK_API_VERSION_1_1);
        this.vkRequiredFeatures11.add(requiredFeatures);
        return this;
    }

    public BoilerBuilder requiredFeatures12(RequiredFeatures12 requiredFeatures) {
        checkApiVersion(VK_API_VERSION_1_2);
        this.vkRequiredFeatures12.add(requiredFeatures);
        return this;
    }

    public BoilerBuilder requiredFeatures13(RequiredFeatures13 requiredFeatures) {
        checkApiVersion(VK_API_VERSION_1_3);
        this.vkRequiredFeatures13.add(requiredFeatures);
        return this;
    }

    public BoilerBuilder extraDeviceRequirements(ExtraDeviceRequirements requirements) {
        this.extraDeviceRequirements.add(requirements);
        return this;
    }

    public BoilerBuilder printDeviceRejectionInfo() {
        this.printDeviceRejectionInfo = true;
        return this;
    }

    public BoilerInstance build() throws GLFWFailureException, VulkanFailureException, MissingVulkanLayerException,
            MissingVulkanExtensionException, NoVkPhysicalDeviceException {
        if (didBuild) throw new IllegalStateException("This builder has been used already");
        didBuild = true;

        var windowCount = windowsRegistrations.size();
        windows = new long[windowCount];
        for (int i = 0; i < windowCount; i++) {
            var windowRegistration = windowsRegistrations.get(i);
            var window = windowRegistration.window();
            var windowWidth = windowRegistration.width();
            var windowHeight = windowRegistration.height();

            if (window == 0L && windowWidth != 0 && windowHeight != 0) {
                if (initGLFW && !glfwInit()) {
                    throw new GLFWFailureException("glfwInit() returned false");
                }
                initGLFW = false;
                glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
                window = glfwCreateWindow(windowWidth, windowHeight, applicationName, 0L, 0L);
                if (window == 0) {
                    try (var stack = stackPush()) {
                        var pError = stack.callocPointer(1);
                        int errorCode = glfwGetError(pError);
                        if (errorCode == GLFW_NO_ERROR) {
                            throw new GLFWFailureException("glfwCreateWindow() returned 0");
                        } else {
                            throw new GLFWFailureException("glfwCreateWindow() returned 0 and glfwGetError() returned " + errorCode + " because: " + memUTF8(pError.get()));
                        }
                    }
                }
            }
            windows[i] = window;
        }

        boolean[] pHasSwapchainMaintenance = { false };

        boolean hasNonEmptyWindow = false;
        for (int i = 0; i < windowCount; i++) {
            if (windows[i] != 0L) {
                hasNonEmptyWindow = true;
                break;
            }
        }

        if (hasNonEmptyWindow) {
            if (!glfwVulkanSupported()) throw new GLFWFailureException("glfwVulkanSupported() returned false");
            var glfwExtensions = glfwGetRequiredInstanceExtensions();
            if (glfwExtensions == null) throw new GLFWFailureException("glfwGetRequiredInstanceExtensions() returned null");
            for (int extensionIndex = 0; extensionIndex < glfwExtensions.limit(); extensionIndex++) {
                this.requiredVulkanInstanceExtensions.add(memUTF8(glfwExtensions.get(extensionIndex)));
            }
            this.requiredVulkanDeviceExtensions.add(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            this.desiredVulkanDeviceExtensions.add(VK_EXT_SWAPCHAIN_MAINTENANCE_1_EXTENSION_NAME);
            this.desiredVulkanInstanceExtensions.add(VK_EXT_SURFACE_MAINTENANCE_1_EXTENSION_NAME);
            this.desiredVulkanInstanceExtensions.add(VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME);
            if (apiVersion == VK_API_VERSION_1_0) {
                this.desiredVulkanInstanceExtensions.add(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
            }

            this.beforeDeviceCreation((ciDevice, instanceExtensions, physicalDevice, stack) -> {
                Set<String> deviceExtensions = decodeStringSet(ciDevice.ppEnabledExtensionNames());
                if (deviceExtensions.contains(VK_EXT_SWAPCHAIN_MAINTENANCE_1_EXTENSION_NAME)) {
                    var swapchainFeatures = VkPhysicalDeviceSwapchainMaintenance1FeaturesEXT.calloc(stack);
                    swapchainFeatures.sType$Default();

                    var features = VkPhysicalDeviceFeatures2.calloc(stack);
                    features.sType$Default();
                    features.pNext(swapchainFeatures);

                    if (apiVersion != VK_API_VERSION_1_0) {
                        vkGetPhysicalDeviceFeatures2(physicalDevice, features);
                    }
                    if (instanceExtensions.contains(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME)) {
                        vkGetPhysicalDeviceFeatures2KHR(physicalDevice, features);
                    }

                    if (swapchainFeatures.swapchainMaintenance1()) {
                        ciDevice.pNext(swapchainFeatures);
                        pHasSwapchainMaintenance[0] = true;
                    }
                }
            });
        }

        XrBoiler xr = null;

        if (xrBuilder != null) {
            xr = xrBuilder.build(
                    this, validationFeatures != null, apiVersion,
                    applicationName, applicationVersion, engineName, engineVersion
            );
        }

        // Nice for VMA
        if (apiVersion == VK_API_VERSION_1_0) {
            this.desiredVulkanInstanceExtensions.add(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
            this.desiredVulkanDeviceExtensions.add(VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME);
            this.desiredVulkanDeviceExtensions.add(VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME);
            this.desiredVulkanDeviceExtensions.add(VK_KHR_BIND_MEMORY_2_EXTENSION_NAME);
            this.desiredVulkanDeviceExtensions.add(VK_EXT_MEMORY_BUDGET_EXTENSION_NAME);
        }

        if (validationFeatures != null) {
            this.requiredVulkanInstanceExtensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            this.requiredVulkanInstanceExtensions.add(VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME);
            this.requiredVulkanLayers.add("VK_LAYER_KHRONOS_validation");
        }

        var instanceResult = BoilerInstanceBuilder.createInstance(this);
        var deviceResult = BoilerDeviceBuilder.createDevice(this, instanceResult);

        var windowSurfaces = new WindowSurface[windowCount];
        for (int i = 0; i < windowCount; i++) {
            var deviceSurface = deviceResult.windowSurfaces()[i];
            windowSurfaces[i] = deviceSurface != 0L ? createSurface(deviceResult.vkPhysicalDevice(), deviceSurface) : null;
        }

        var swapchainSettingsArr = new SwapchainSettings[windowCount];
        for (int i = 0; i < windowCount; i++) {
            swapchainSettingsArr[i] = windowSurfaces[i] != null ? windowsRegistrations.get(i).swapchainBuilder().chooseSwapchainSettings(windowSurfaces[i]) : null;
        }

        long validationErrorThrower = 0;
        if (forbidValidationErrors) {
            if (!instanceResult.enabledExtensions().contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
                throw new ValidationException("Debug utils extension is not enabled");
            }
            try (var stack = stackPush()) {
                var ciReporter = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
                ciReporter.sType$Default();
                ciReporter.flags(0);
                ciReporter.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT);
                ciReporter.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT);
                ciReporter.pfnUserCallback((severity, types, data, userData) -> {
                    String message = VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString();
                    if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                        throw new ValidationException(message);
                    } else System.out.println(message);
                    return VK_FALSE;
                });

                var pReporter = stack.callocLong(1);
                assertVkSuccess(vkCreateDebugUtilsMessengerEXT(
                        instanceResult.vkInstance(), ciReporter, null, pReporter
                ), "CreateDebugUtilsMessengerEXT", "Validation error thrower");
                validationErrorThrower = pReporter.get(0);
            }
        }

        var instance = new BoilerInstance(
                windows, windowSurfaces, swapchainSettingsArr, pHasSwapchainMaintenance[0], xr, defaultTimeout,
                instanceResult.vkInstance(), deviceResult.vkPhysicalDevice(), deviceResult.vkDevice(),
                instanceResult.enabledExtensions(), deviceResult.enabledExtensions(),
                deviceResult.queueFamilies(), deviceResult.vmaAllocator(), validationErrorThrower
        );
        if (xr != null) xr.boiler = instance;
        return instance;
    }
}

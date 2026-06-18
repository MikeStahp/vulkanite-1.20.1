#include <iostream>
#include <vulkan/vulkan.h>
#include <Windows.h>

// Function pointer types for the DLL
typedef int(*InitDLSSD_Func)(VkInstance, VkPhysicalDevice, VkDevice, int, int, int, int, int, int, int, int);
typedef void(*DestroyDLSSD_Func)(VkDevice);

int main() {
    std::cout << "[Test] Loading vulkanite_dlss_bridge.dll..." << std::endl;
    HMODULE hDll = LoadLibrary("vulkanite_dlss_bridge.dll");
    if (!hDll) {
        std::cerr << "[Test] Failed to load DLL! Error: " << GetLastError() << std::endl;
        return 1;
    }
    std::cout << "[Test] DLL Loaded successfully." << std::endl;

    InitDLSSD_Func initDLSSD = (InitDLSSD_Func)GetProcAddress(hDll, "initDLSSD");
    if (!initDLSSD) {
        std::cerr << "[Test] Failed to find initDLSSD function!" << std::endl;
        return 1;
    }
    std::cout << "[Test] Found initDLSSD function." << std::endl;

    // We can't actually initialize DLSS without a valid Vulkan Instance/Device
    // But we can check if the DLL loads and exports symbols correctly.
    // To go further, we would need to initialize a headless Vulkan instance here.
    
    std::cout << "[Test] Basic DLL validation passed. The issue is likely inside initDLSSD logic or Vulkan context." << std::endl;

    FreeLibrary(hDll);
    return 0;
}

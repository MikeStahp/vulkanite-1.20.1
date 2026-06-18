cmake_minimum_required(VERSION 3.15)
project(vulkanite_dlss_bridge)

# Specify C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

# Define the shared library
add_library(vulkanite_dlss_bridge SHARED dlss_wrapper.cpp)

# Include directories (Assumes the NVIDIA DLSS SDK headers are in 'Include' and 'inc')
target_include_directories(vulkanite_dlss_bridge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/Include ${CMAKE_CURRENT_SOURCE_DIR}/inc)

# Find Vulkan
find_package(Vulkan REQUIRED)
target_link_libraries(vulkanite_dlss_bridge PRIVATE Vulkan::Vulkan)

# Add nvsdk_ngx_s.lib from NVIDIA SDK
# Normally provided within the NVIDIA DLSS SDK "Lib/x64" directory
target_link_libraries(vulkanite_dlss_bridge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/Lib/x64/nvsdk_ngx_s.lib)

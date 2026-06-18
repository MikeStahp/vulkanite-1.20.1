cmake_minimum_required(VERSION 3.15)
project(vulkanite_dlss_bridge)

# Specify C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

# Define the shared library
add_library(vulkanite_dlss_bridge SHARED dlss_wrapper.cpp)

# NVIDIA NGX/DLSS SDK location.
# Prefer -DNGX_SDK_DIR=... or the DLSS_SDK_DIR environment variable. The
# dlss_sdk folder is intentionally ignored by Git so the proprietary SDK is not
# committed.
set(NGX_SDK_DIR "$ENV{DLSS_SDK_DIR}" CACHE PATH "Path to the NVIDIA NGX/DLSS SDK")
if(NOT NGX_SDK_DIR)
    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/dlss_sdk/Include/nvsdk_ngx_vk.h")
        set(NGX_SDK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/dlss_sdk" CACHE PATH "Path to the NVIDIA NGX/DLSS SDK" FORCE)
    elseif(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/Include/nvsdk_ngx_vk.h")
        set(NGX_SDK_DIR "${CMAKE_CURRENT_SOURCE_DIR}" CACHE PATH "Path to the NVIDIA NGX/DLSS SDK" FORCE)
    else()
        set(NGX_SDK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/dlss_sdk" CACHE PATH "Path to the NVIDIA NGX/DLSS SDK" FORCE)
    endif()
endif()

if(NOT EXISTS "${NGX_SDK_DIR}/Include/nvsdk_ngx_vk.h")
    message(FATAL_ERROR "NVIDIA NGX/DLSS SDK not found. Set DLSS_SDK_DIR or pass -DNGX_SDK_DIR=... to CMake.")
endif()

target_include_directories(vulkanite_dlss_bridge PRIVATE "${NGX_SDK_DIR}/Include")
if(EXISTS "${NGX_SDK_DIR}/inc")
    target_include_directories(vulkanite_dlss_bridge PRIVATE "${NGX_SDK_DIR}/inc")
endif()

# Find Vulkan
find_package(Vulkan REQUIRED)
target_link_libraries(vulkanite_dlss_bridge PRIVATE Vulkan::Vulkan)

# Add nvsdk_ngx_s.lib from the NVIDIA SDK.
set(NGX_STATIC_LIB "${NGX_SDK_DIR}/Lib/x64/nvsdk_ngx_s.lib")
if(NOT EXISTS "${NGX_STATIC_LIB}")
    message(FATAL_ERROR "nvsdk_ngx_s.lib not found under ${NGX_SDK_DIR}/Lib/x64.")
endif()
target_link_libraries(vulkanite_dlss_bridge PRIVATE "${NGX_STATIC_LIB}")

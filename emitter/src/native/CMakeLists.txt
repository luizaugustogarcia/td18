cmake_minimum_required(VERSION 3.24)
project(td18_native LANGUAGES CXX CUDA)

find_package(CUDAToolkit REQUIRED)

if(CMAKE_CUDA_COMPILER_VERSION VERSION_LESS 12.8)
    message(FATAL_ERROR "The RTX 5090 profile requires CUDA Toolkit 12.8 or newer")
endif()
set(TD18_CUDA_ARCHITECTURES "120-real;120-virtual"
    CACHE STRING "RTX 5090 native code and forward PTX architectures")
set(CMAKE_CUDA_ARCHITECTURES ${TD18_CUDA_ARCHITECTURES})
message(STATUS "CUDA architectures: ${CMAKE_CUDA_ARCHITECTURES}")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CUDA_STANDARD 17)
set(CMAKE_CUDA_STANDARD_REQUIRED ON)
set(CMAKE_CUDA_RUNTIME_LIBRARY Shared)

set(TD18_NATIVE_MAX_N 19)
set(TD18_NATIVE_MAX_DEPTH 10)

# JNI bridge for the TD(18) exact bounded search.

find_package(JNI)

if(JNI_FOUND)
    add_library(td18_jni SHARED
        jni/jni_sorter.cu
        backtracking/slot_manager.cu
        backtracking/sorting_search.cu
    )

    target_include_directories(td18_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${JNI_INCLUDE_DIRS}
    )
    target_link_libraries(td18_jni PRIVATE CUDA::cudart ${JNI_LIBRARIES})

    set_target_properties(td18_jni PROPERTIES
        POSITION_INDEPENDENT_CODE ON
        CUDA_SEPARABLE_COMPILATION ON
        CUDA_RESOLVE_DEVICE_SYMBOLS ON
    )

    target_compile_definitions(td18_jni PRIVATE MAX_N=${TD18_NATIVE_MAX_N} MAX_DEPTH=${TD18_NATIVE_MAX_DEPTH})

    target_compile_options(td18_jni PRIVATE
        $<$<COMPILE_LANGUAGE:CUDA>:-Xptxas=-v>
        $<$<CONFIG:Debug>:-G>
        $<$<CONFIG:RelWithDebInfo>:-lineinfo>
    )

    install(TARGETS td18_jni DESTINATION lib)
endif()

option(TD18_BUILD_NATIVE_TESTS "Build CUDA-dependent native regression tests (Linux linker)" OFF)
if(TD18_BUILD_NATIVE_TESTS)
    enable_testing()
    add_executable(td18_slot_memory_test
        tests/slot_memory_test.cpp
        backtracking/slot_manager.cu
        backtracking/sorting_search.cu
    )
    target_include_directories(td18_slot_memory_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(td18_slot_memory_test PRIVATE CUDA::cudart)
    target_compile_definitions(td18_slot_memory_test PRIVATE MAX_N=${TD18_NATIVE_MAX_N} MAX_DEPTH=${TD18_NATIVE_MAX_DEPTH})
    set_target_properties(td18_slot_memory_test PROPERTIES
        CUDA_SEPARABLE_COMPILATION ON
        CUDA_RESOLVE_DEVICE_SYMBOLS ON
    )
    target_link_options(td18_slot_memory_test PRIVATE
        "LINKER:--wrap=cudaMemGetInfo" "LINKER:--wrap=cudaMalloc")
    add_test(NAME slot_memory_preflight COMMAND td18_slot_memory_test)
endif()

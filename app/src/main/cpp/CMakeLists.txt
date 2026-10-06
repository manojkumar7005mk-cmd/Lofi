cmake_minimum_required(VERSION 3.22.1)
project(lofi LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(T ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# --- llama.cpp + mtmd (ggml linked statically into liblofi_llm.so only) ---
foreach(o BUILD_SHARED_LIBS GGML_OPENMP GGML_NATIVE LLAMA_CURL LLAMA_BUILD_TESTS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER LLAMA_BUILD_TOOLS)
  set(${o} OFF CACHE BOOL "" FORCE)
endforeach()
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)   # mtmd subproject links `common`
add_subdirectory(${T}/llama.cpp ${CMAKE_BINARY_DIR}/llama)
add_subdirectory(${T}/llama.cpp/tools/mtmd ${CMAKE_BINARY_DIR}/mtmd)
add_library(lofi_llm SHARED lofi_llm.cpp)
target_include_directories(lofi_llm PRIVATE ${T}/llama.cpp/include ${T}/llama.cpp/ggml/include ${T}/llama.cpp/tools/mtmd)
target_link_libraries(lofi_llm PRIVATE mtmd llama ggml android log)
target_link_options(lofi_llm PRIVATE -Wl,--exclude-libs,ALL)

# --- whisper.cpp built in an isolated project so its ggml never collides with llama.cpp's ---
include(ExternalProject)
set(WB ${CMAKE_BINARY_DIR}/whisper-build)
set(WLIBS ${WB}/src/libwhisper.a ${WB}/ggml/src/libggml.a ${WB}/ggml/src/libggml-cpu.a ${WB}/ggml/src/libggml-base.a) # layout is pin-dependent
ExternalProject_Add(whisper_ext
  SOURCE_DIR ${T}/whisper.cpp BINARY_DIR ${WB} INSTALL_COMMAND ""
  CMAKE_ARGS -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE} -DANDROID_ABI=${ANDROID_ABI} -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF
  BUILD_BYPRODUCTS ${WLIBS})
add_library(lofi_stt SHARED lofi_stt.cpp)
add_dependencies(lofi_stt whisper_ext)
target_include_directories(lofi_stt PRIVATE ${T}/whisper.cpp/include ${T}/whisper.cpp/ggml/include)
target_link_libraries(lofi_stt PRIVATE -Wl,--start-group ${WLIBS} -Wl,--end-group android log)
target_link_options(lofi_stt PRIVATE -Wl,--exclude-libs,ALL)

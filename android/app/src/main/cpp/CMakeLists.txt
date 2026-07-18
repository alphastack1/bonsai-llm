cmake_minimum_required(VERSION 3.22.1)
project(bonsai-llm)

# PrismML's llama.cpp fork lives in this directory.
# Clone it with: git clone --branch prism --depth 1 https://github.com/PrismML-Eng/llama.cpp.git
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

# The current upstream server always generates a tiny ui.cpp/ui.h pair, even
# when the embedded server UI is disabled. During a Windows -> Android cross
# build that generator must run on Windows, so use a wrapper that exposes the
# MSYS2 UCRT runtime DLLs to its host g++ process.
if(CMAKE_HOST_WIN32)
    set(HOST_CXX_COMPILER "${CMAKE_CURRENT_SOURCE_DIR}/host-g++.bat"
        CACHE FILEPATH "Host compiler wrapper for llama-ui-embed" FORCE)
endif()

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "PrismML's llama.cpp not found at ${LLAMA_CPP_DIR}.\n"
        "Run: git clone --branch prism --depth 1 https://github.com/PrismML-Eng/llama.cpp.git ${LLAMA_CPP_DIR}"
    )
endif()

# Build llama.cpp as a subdirectory
add_subdirectory(${LLAMA_CPP_DIR} llama.cpp)

# Android trick: executables in APK must be named lib*.so and placed in lib/<abi>/
# to be extracted by the package manager. We rename the llama-server executable.
if(TARGET llama-server)
    set_target_properties(llama-server PROPERTIES
        OUTPUT_NAME "llama_server"
        # Force the output into the same directory as shared libs
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
    )

    # Ensure the executable gets a .so suffix so Android extracts it
    set_target_properties(llama-server PROPERTIES SUFFIX ".so")
    set_target_properties(llama-server PROPERTIES PREFIX "lib")
endif()

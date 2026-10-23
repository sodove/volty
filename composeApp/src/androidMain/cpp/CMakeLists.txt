cmake_minimum_required(VERSION 3.22.1)
project(volty_rnnoise C)

# The model is generated C downloaded and checksum-verified by Gradle (see composeApp/build.gradle.kts,
# task fetchRnnoiseModel); it is too large to vendor.
if(NOT DEFINED RNNOISE_MODEL_DIR)
    message(FATAL_ERROR "RNNOISE_MODEL_DIR is not set: run the Gradle build, which fetches the model")
endif()

set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)
add_library(volty_rnnoise SHARED
    rnnoise_jni.c
    ${RNNOISE_DIR}/src/denoise.c
    ${RNNOISE_DIR}/src/rnn.c
    ${RNNOISE_DIR}/src/pitch.c
    ${RNNOISE_DIR}/src/kiss_fft.c
    ${RNNOISE_DIR}/src/celt_lpc.c
    ${RNNOISE_DIR}/src/nnet.c
    ${RNNOISE_DIR}/src/nnet_default.c
    ${RNNOISE_DIR}/src/parse_lpcnet_weights.c
    ${RNNOISE_DIR}/src/rnnoise_tables.c
    ${RNNOISE_MODEL_DIR}/src/rnnoise_data.c)
target_include_directories(volty_rnnoise PRIVATE ${RNNOISE_DIR}/include ${RNNOISE_DIR}/src ${RNNOISE_MODEL_DIR}/src)
# -O2 keeps the 10 ms frame well inside the audio callback; the generated model needs no warnings.
target_compile_options(volty_rnnoise PRIVATE -O2 -w)
# The model carries float copies of its int8 weights for debugging only; the int8 path is what runs.
# Without this the library is ~15 MB per ABI instead of ~4 MB.
target_compile_definitions(volty_rnnoise PRIVATE DISABLE_DEBUG_FLOAT)
target_link_libraries(volty_rnnoise m)

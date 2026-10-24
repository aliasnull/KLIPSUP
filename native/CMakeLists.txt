cmake_minimum_required(VERSION 3.22.1)
project("klipsup_engine" VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

add_library(
    klipsup_engine
    SHARED
    src/klipsup_engine.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_compile_options(klipsup_engine PRIVATE -O3 -ffast-math -Wall -Wextra)

target_link_libraries(
    klipsup_engine
    ${log-lib}
    ${jnigraphics-lib}
)

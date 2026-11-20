cmake_minimum_required(VERSION 3.22.1)
project("procpulse_native" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    procpulse_native
    SHARED
    procpulse_native.cpp
)

find_library(log-lib log)

target_link_libraries(
    procpulse_native
    ${log-lib}
)

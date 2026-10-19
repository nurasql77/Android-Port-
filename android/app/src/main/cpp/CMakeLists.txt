cmake_minimum_required(VERSION 3.22.1)
project(openbgi_android)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

# The actual OpenBGI source tree is intentionally not vendored here yet.
# Next step: add OpenBGI sources and an Android OS backend, then link them
# into this native target.

add_library(openbgi_android SHARED
    native_stub.c
)

target_link_libraries(openbgi_android android log)

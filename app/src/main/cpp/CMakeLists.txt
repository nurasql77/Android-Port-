cmake_minimum_required(VERSION 3.22.1)
project(openbgi_android C)

add_library(openbgi_android SHARED native_entry.c)

target_compile_features(openbgi_android PRIVATE c_std_99)

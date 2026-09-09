cmake_minimum_required(VERSION 3.22.1)
project(shahboun_runtime LANGUAGES C CXX)

add_library(shahboun_runtime SHARED shahboun_runtime.cpp)

target_compile_features(shahboun_runtime PRIVATE cxx_std_20)
target_compile_options(shahboun_runtime PRIVATE -Wall -Wextra -Werror=return-type -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(shahboun_runtime PRIVATE ${log-lib})

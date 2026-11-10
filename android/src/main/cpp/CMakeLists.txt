cmake_minimum_required(VERSION 3.22.1)
project(fcpjvm C)

add_library(fcpjvm SHARED linker_support.c)
find_library(log-lib log)
target_link_libraries(fcpjvm ${log-lib})

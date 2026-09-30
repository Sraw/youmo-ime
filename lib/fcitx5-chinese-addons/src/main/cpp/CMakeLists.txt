cmake_minimum_required(VERSION 3.18)

project(fcitx5-android-lib-fcitx5-chinese-addons VERSION ${VERSION_NAME})

# For reproducible build
add_link_options("LINKER:--hash-style=gnu,--build-id=none")

# prefab dependency
find_package(fcitx5 REQUIRED CONFIG)
get_target_property(FCITX5_CMAKE_MODULES fcitx5::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${FCITX5_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(ECM MODULE)
find_package(Fcitx5Core MODULE)
find_package(Fcitx5Module MODULE)

find_package(fcitx5-lua REQUIRED CONFIG)
get_target_property(FCITX5_LUA_CMAKE_MODULES fcitx5-lua::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${FCITX5_LUA_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(Fcitx5ModuleLuaAddonLoader MODULE)

# dummy target to export src/main/cpp/cmake
add_custom_target(cmake)

# prebuilt marisa-tire, OpenCC needs it
set(Marisa_DIR "${PREBUILT_DIR}/marisa/${ANDROID_ABI}/lib/cmake/Marisa")
find_package(Marisa)

# prebuilt opencc
set(OpenCC_DIR "${PREBUILT_DIR}/opencc/${ANDROID_ABI}/lib/cmake/opencc")
find_package(OpenCC)

# fcitx5-chinese-addons' own top level wants libime, for its pinyin and table input methods and
# pinyinhelper, all of which the app's engine replaces: only the rest is built, set up as it does
set(ADDONS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/fcitx5-chinese-addons")
set(CMAKE_MODULE_PATH ${ECM_MODULE_PATH} "${ADDONS_DIR}/cmake" ${CMAKE_MODULE_PATH})
include(GNUInstallDirs)
# write_basic_package_version_file, for fcitx5_export_module
include(CMakePackageConfigHelpers)
find_package(Fcitx5Module REQUIRED COMPONENTS Notifications)
find_package(Gettext REQUIRED)
include("${FCITX_INSTALL_CMAKECONFIG_DIR}/Fcitx5Utils/Fcitx5CompilerSettings.cmake")
set(ENABLE_OPENCC ON)
configure_file("${ADDONS_DIR}/config.h.in" "${CMAKE_CURRENT_BINARY_DIR}/config.h")
include_directories(${CMAKE_CURRENT_BINARY_DIR})
add_definitions(-DFCITX_GETTEXT_DOMAIN=\"fcitx5-chinese-addons\")
fcitx5_add_i18n_definition()
foreach(module chttrans punctuation fullwidth)
    add_subdirectory("${ADDONS_DIR}/modules/${module}" "modules/${module}")
endforeach()
add_subdirectory("${ADDONS_DIR}/tools" tools)
add_subdirectory("${ADDONS_DIR}/po" po)
# rename to include executable in apk
set_target_properties(scel2org5 PROPERTIES OUTPUT_NAME libscel2org5.so)

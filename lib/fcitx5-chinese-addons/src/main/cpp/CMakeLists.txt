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

# dummy target to export src/main/cpp/cmake
add_custom_target(cmake)

# prebuilt marisa-tire, OpenCC needs it
set(Marisa_DIR "${PREBUILT_DIR}/marisa/${ANDROID_ABI}/lib/cmake/Marisa")
find_package(Marisa REQUIRED CONFIG)

# prebuilt opencc, by CONFIG: never the pkg-config FindOpenCC.cmake in fcitx5-chinese-addons/cmake
set(OpenCC_DIR "${PREBUILT_DIR}/opencc/${ANDROID_ABI}/lib/cmake/opencc")
find_package(OpenCC REQUIRED CONFIG)

# fcitx5-chinese-addons/ holds only the vendored modules the app builds (chttrans, punctuation,
# fullwidth, tools/scel2org5) from upstream 5.1.13 (0d3fd040); its own top level wants libime, for
# the pinyin and table input methods the app's engine replaces, so its setup is redone here
set(ADDONS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/fcitx5-chinese-addons")
set(CMAKE_MODULE_PATH ${ECM_MODULE_PATH} ${CMAKE_MODULE_PATH})
include(GNUInstallDirs)
# write_basic_package_version_file, for fcitx5_export_module
include(CMakePackageConfigHelpers)
find_package(Gettext REQUIRED)
include("${FCITX_INSTALL_CMAKECONFIG_DIR}/Fcitx5Utils/Fcitx5CompilerSettings.cmake")
set(ENABLE_OPENCC ON)
configure_file("${ADDONS_DIR}/config.h.in" "${CMAKE_CURRENT_BINARY_DIR}/config.h")
include_directories(${CMAKE_CURRENT_BINARY_DIR})
add_definitions(-DFCITX_GETTEXT_DOMAIN=\"fcitx5-chinese-addons\")
fcitx5_add_i18n_definition()
# what its project() would give the modules: the .conf Version= and core version, and the po/ that
# fcitx5_translate_desktop_file takes Name[zh_CN] and the like from
set(PROJECT_VERSION 5.1.13)
set(REQUIRED_FCITX_VERSION 5.1.22)
set(PROJECT_SOURCE_DIR "${ADDONS_DIR}")
foreach(module chttrans punctuation fullwidth)
    add_subdirectory("${ADDONS_DIR}/modules/${module}" "modules/${module}")
endforeach()
add_subdirectory("${ADDONS_DIR}/tools" tools)
add_subdirectory("${ADDONS_DIR}/po" po)
# rename to include executable in apk
set_target_properties(scel2org5 PROPERTIES OUTPUT_NAME libscel2org5.so)

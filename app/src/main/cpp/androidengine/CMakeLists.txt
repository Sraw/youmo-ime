add_definitions(-DFCITX_GETTEXT_DOMAIN=\"fcitx5-android\")

add_library(androidengine MODULE androidengine.cpp)
target_link_libraries(androidengine Fcitx5::Core Fcitx5::Utils)
# the punctuation addon's header only: it is called through fcitx, not linked
target_include_directories(androidengine PRIVATE
        $<TARGET_PROPERTY:fcitx5-chinese-addons::punctuation,INTERFACE_INCLUDE_DIRECTORIES>)

configure_file(androidengine.conf.in.in androidengine.conf.in @ONLY)
fcitx5_translate_desktop_file(${CMAKE_CURRENT_BINARY_DIR}/androidengine.conf.in androidengine.conf)
install(FILES "${CMAKE_CURRENT_BINARY_DIR}/androidengine.conf"
        DESTINATION "${FCITX_INSTALL_PKGDATADIR}/addon"
        COMPONENT config)

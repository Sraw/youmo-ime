# the macro globs *.po without CONFIGURE_DEPENDS; LINGUAS changes whenever a language does
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/LINGUAS")
fcitx5_install_translation(fcitx5-chinese-addons)

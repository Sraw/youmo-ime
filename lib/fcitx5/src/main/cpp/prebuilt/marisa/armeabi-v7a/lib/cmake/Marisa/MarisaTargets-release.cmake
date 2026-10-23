#----------------------------------------------------------------
# Generated CMake target import file for configuration "Release".
#----------------------------------------------------------------

# Commands may need to know the format version.
set(CMAKE_IMPORT_FILE_VERSION 1)

# Import target "Marisa::marisa" for configuration "Release"
set_property(TARGET Marisa::marisa APPEND PROPERTY IMPORTED_CONFIGURATIONS RELEASE)
set_target_properties(Marisa::marisa PROPERTIES
  IMPORTED_LINK_INTERFACE_LANGUAGES_RELEASE "CXX"
  IMPORTED_LOCATION_RELEASE "${_IMPORT_PREFIX}/lib/libmarisa.a"
  )

list(APPEND _cmake_import_check_targets Marisa::marisa )
list(APPEND _cmake_import_check_files_for_Marisa::marisa "${_IMPORT_PREFIX}/lib/libmarisa.a" )

# Commands beyond this point should not need to know the version.
set(CMAKE_IMPORT_FILE_VERSION)

# Listed one by one: a GLOB is only re-read when CMake is re-run, so a new font file made the build
# fail to link (and the old firmware stayed on the board).
set(FONT_SRCS
    "fonts/font_clock72.c" "fonts/font_flip64.c" "fonts/font_icon30.c" "fonts/font_icon40.c"
    "fonts/font_num52.c" "fonts/font_vn14.c" "fonts/font_vn18.c" "fonts/font_vn24.c")
# No REQUIRES: "main" then sees every component, which works the same whether LVGL and the
# ILI9341 driver sit in components/ or are downloaded by the component manager.
idf_component_register(
    SRCS "app_main.c" "cfg.c" "display.c" "touch.c" "net.c" "portal.c" "link.c" "video.c" "ui.c" ${FONT_SRCS}
    INCLUDE_DIRS "."
    REQUIRES json lvgl esp_http_server nvs_flash esp_wifi esp_event esp_http_client esp-tls mbedtls esp_rom
)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wno-unused-function)

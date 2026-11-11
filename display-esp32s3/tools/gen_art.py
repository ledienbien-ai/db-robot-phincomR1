#!/usr/bin/env python3
"""Pictures for the display's answer cards (main/art.c): Twemoji (CC BY 4.0) icons as LVGL RGB565A8
images, and every Twemoji national flag as a small JPEG (decoded on the display with TJpgDec).

    python3 gen_art.py <twemoji>/assets/svg ../main/art.c

Needs cairosvg and Pillow.
"""
import io
import sys

import cairosvg
from PIL import Image

SVG, OUT = sys.argv[1], sys.argv[2]

ICON = 72          # card icons; forecast days show them at half size
FLAG_W = 120       # flags: width in pixels
ICONS = {
    # weather (WMO codes are mapped to these in ui.c)
    'sun': '2600', 'moon': '1f319', 'sun_cloud_s': '1f324', 'sun_cloud': '26c5', 'cloud': '2601',
    'fog': '1f32b', 'rain': '1f327', 'showers': '1f326', 'snow': '1f328', 'storm': '26c8',
    # cards
    'coin': '1fa99', 'fuel': '26fd', 'calendar': '1f4c5', 'abacus': '1f9ee', 'apple': '1f34e',
    'globe': '1f30f', 'moon_full': '1f315',
    # home assistant domains
    'bulb': '1f4a1', 'plug': '1f50c', 'fan': '1f300', 'snowflake': '2744', 'window': '1fa9f',
    'lock': '1f512', 'tv': '1f4fa', 'house': '1f3e0',
}


def render(code, w, h):
    png = cairosvg.svg2png(url=f'{SVG}/{code}.svg', output_width=w, output_height=h)
    return Image.open(io.BytesIO(png)).convert('RGBA')


def rgb565a8(im):
    px = im.load()
    w, h = im.size
    rgb, alpha = bytearray(), bytearray()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)
            rgb += bytes((v & 0xFF, v >> 8))
            alpha.append(a)
    return bytes(rgb + alpha)


def carray(name, data):
    lines = []
    for i in range(0, len(data), 24):
        lines.append('    ' + ','.join(f'0x{b:02x}' for b in data[i:i + 24]) + ',')
    return f'static const uint8_t {name}[] = {{\n' + '\n'.join(lines) + '\n};\n'


out = ['/*\n * Pictures for the answer cards (ui.c): Twemoji icons (Copyright Twitter, Inc. and other\n'
       ' * contributors, CC BY 4.0, https://github.com/twitter/twemoji) as RGB565A8, and the Twemoji\n'
       ' * national flags as JPEG. Made by tools/gen_art.py; do not edit.\n */\n'
       '#include <string.h>\n#include "lvgl.h"\n#include "art.h"\n\n']
names = []
for name, code in ICONS.items():
    im = render(code, ICON, ICON)
    out.append(carray(f'{name}_px', rgb565a8(im)))
    out.append(f'const lv_image_dsc_t art_{name} = {{ .header = {{ .magic = LV_IMAGE_HEADER_MAGIC, '
               f'.cf = LV_COLOR_FORMAT_RGB565A8, .w = {ICON}, .h = {ICON}, .stride = {ICON * 2} }}, '
               f'.data_size = sizeof {name}_px, .data = {name}_px }};\n\n')
    names.append(name)

flags = []
import os
for f in sorted(os.listdir(SVG)):
    base = f[:-4]
    parts = base.split('-')
    if len(parts) != 2 or not all(0x1F1E6 <= int(p, 16) <= 0x1F1FF for p in parts):
        continue
    cc = ''.join(chr(ord('a') + int(p, 16) - 0x1F1E6) for p in parts)
    im = render(base, FLAG_W, FLAG_W)
    box = im.getbbox()
    im = im.crop(box)
    bg = Image.new('RGB', im.size, (255, 255, 255))
    bg.paste(im, mask=im.split()[3])
    # TJpgDec wants whole 8/16-pixel blocks to be safe; trim to a multiple of 2
    w, h = bg.size
    bg = bg.crop((0, 0, w - w % 2, h - h % 2))
    buf = io.BytesIO()
    bg.save(buf, 'JPEG', quality=80)
    data = buf.getvalue()
    out.append(carray(f'flag_{cc}', data))
    flags.append((cc, bg.size[0], bg.size[1], len(data)))

out.append('const art_flag_t ART_FLAGS[] = {\n')
for cc, w, h, n in flags:
    out.append(f'    {{ "{cc}", {w}, {h}, {n}, flag_{cc} }},\n')
out.append('};\nconst int ART_FLAG_COUNT = sizeof ART_FLAGS / sizeof ART_FLAGS[0];\n\n'
           'const art_flag_t *art_flag(const char *cc)\n{\n'
           '    for (int i = 0; i < ART_FLAG_COUNT; i++) if (!strcmp(ART_FLAGS[i].cc, cc)) return &ART_FLAGS[i];\n'
           '    return NULL;\n}\n')
open(OUT, 'w').write(''.join(out))

hdr = ['#pragma once\n#include "lvgl.h"\n\n/* Pictures for the answer cards: see art.c (made by tools/gen_art.py). */\n'
       f'#define ART_ICON {ICON}\n']
hdr += [f'extern const lv_image_dsc_t art_{n};\n' for n in names]
hdr.append('\ntypedef struct { char cc[3]; uint16_t w, h; uint32_t len; const uint8_t *jpg; } art_flag_t;\n'
           'extern const art_flag_t ART_FLAGS[];\nextern const int ART_FLAG_COUNT;\n'
           '/* The flag of a country by its ISO 3166 code in lower case ("vn", "jp"), or NULL. */\n'
           'const art_flag_t *art_flag(const char *cc);\n')
open(OUT[:-2] + '.h', 'w').write(''.join(hdr))
print(len(names), 'icons,', len(flags), 'flags,', sum(f[3] for f in flags), 'bytes of JPEG')

#!/usr/bin/env python3
"""幽默输入法's launcher icon: a monkey in sunglasses, grinning. The shapes are listed once, as
path data, and written out twice: as the adaptive icon's vector drawables (background, foreground,
monochrome) and, through cairosvg, as the PNGs that launchers before Android 8 take. Run it after
changing a shape; it writes into app/src/main/res. Needs cairosvg and Pillow.

The canvas is the adaptive icon's 108 dp; launchers show at least the circle of radius 33 round its
centre, and the legacy PNGs are its middle 72."""
import io
import os

import cairosvg
from PIL import Image, ImageDraw

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'res')
FUR, SKIN, INK, GLINT = '#8B5A2B', '#F8DDB5', '#3B2314', '#CCFFFFFF'
BACKGROUNDS = {'': ('#FFB74D', '#F57C00'), '_debug': ('#90A4AE', '#455A64')}
TILT = -6  # degrees, about (54, 56)


def circle(cx, cy, r):
    return f'M{cx - r},{cy} a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0 Z'


def rounded(x, y, w, h, r):
    return (f'M{x + r},{y} h{w - 2 * r} a{r},{r} 0 0,1 {r},{r} v{h - 2 * r} a{r},{r} 0 0,1 {-r},{r} '
            f'h{-(w - 2 * r)} a{r},{r} 0 0,1 {-r},{-r} v{-(h - 2 * r)} a{r},{r} 0 0,1 {r},{-r} Z')


# (path data, fill, stroke, stroke width)
EARS = [(circle(x, 56, 7.5), FUR, None, 0) for x in (33, 75)]
EAR_INSIDES = [(circle(x, 56, 4), SKIN, None, 0) for x in (33, 75)]
HEAD = [(circle(54, 56, 20), FUR, None, 0), ('M48,38 Q51,29 55,36 Q58,29 61,38 Z', FUR, None, 0)]
PATCH = [(circle(46.8, 52, 7.8), SKIN, None, 0), (circle(61.2, 52, 7.8), SKIN, None, 0),
         (circle(54, 61.5, 9.5), SKIN, None, 0), ('M46.8,52 h14.4 v9.5 h-14.4 Z', SKIN, None, 0)]
NOSE = [(circle(52.2, 59.5, 1), INK, None, 0), (circle(55.8, 59.5, 1), INK, None, 0)]
GLASSES = [(rounded(39.5, 47, 13, 9, 4), INK, None, 0), (rounded(55.5, 47, 13, 9, 4), INK, None, 0),
           ('M52,49.5 h4 v1.8 h-4 Z', INK, None, 0)]
GLINTS = [('M42.5,49.5 Q45,48 48,49', None, GLINT, 1.2), ('M58.5,49.5 Q61,48 64,49', None, GLINT, 1.2)]
GRIN = [('M47.5,63 Q54,69.5 60.5,63', None, INK, 2.2)]
FOREGROUND = EARS + EAR_INSIDES + HEAD + PATCH + NOSE + GLASSES + GLINTS + GRIN
# one colour, for themed icons: the head as a ring, the rest as it is
MONOCHROME = ([(circle(x, 56, 7.5), '#000', None, 0) for x in (33, 75)]
              + [(circle(54, 56, 18), None, '#000', 4), ('M48,39 Q51,30 55,37 Q58,30 61,39 Z', '#000', None, 0)]
              + [(d, '#000', None, 0) for d, _, _, _ in NOSE + GLASSES] + [('M47.5,63 Q54,69.5 60.5,63', None, '#000', 2.6)])


def vector(shapes, gradient=None):
    out = ['<?xml version="1.0" encoding="utf-8"?>\n<!-- written by app/launcher-icon/make_icon.py -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    xmlns:aapt="http://schemas.android.com/aapt"\n'
           '    android:width="108dp"\n    android:height="108dp"\n'
           '    android:viewportWidth="108"\n    android:viewportHeight="108">\n']
    if gradient:
        out.append('    <path android:pathData="M0,0 h108 v108 h-108 Z">\n        <aapt:attr name="android:fillColor">\n'
                   '            <gradient android:type="linear" android:startX="0" android:startY="0" '
                   'android:endX="108" android:endY="108">\n'
                   f'                <item android:offset="0" android:color="{gradient[0]}" />\n'
                   f'                <item android:offset="1" android:color="{gradient[1]}" />\n'
                   '            </gradient>\n        </aapt:attr>\n    </path>\n')
    if shapes:
        out.append(f'    <group android:rotation="{TILT}" android:pivotX="54" android:pivotY="56">\n')
        for d, fill, stroke, width in shapes:
            attrs = [f'android:pathData="{d}"']
            if fill:
                attrs.append(f'android:fillColor="{fill}"')
            if stroke:
                attrs += [f'android:strokeColor="{stroke}"', f'android:strokeWidth="{width}"',
                          'android:strokeLineCap="round"']
            out.append('        <path\n' + ''.join(f'            {a}\n' for a in attrs).rstrip('\n') + ' />\n')
        out.append('    </group>\n')
    out.append('</vector>\n')
    return ''.join(out)


def svg(shapes, gradient):
    """the same, for cairosvg; a colour's alpha, Android's first byte, becomes opacity"""
    def paint(c):
        if len(c) == 9:
            return c[0] + c[3:], int(c[1:3], 16) / 255
        return c, 1
    body = []
    for d, fill, stroke, width in shapes:
        attrs = [f'd="{d}"']
        if fill:
            c, a = paint(fill)
            attrs.append(f'fill="{c}"' + (f' fill-opacity="{a:.3f}"' if a < 1 else ''))
        else:
            attrs.append('fill="none"')
        if stroke:
            c, a = paint(stroke)
            attrs.append(f'stroke="{c}" stroke-width="{width}" stroke-linecap="round"'
                         + (f' stroke-opacity="{a:.3f}"' if a < 1 else ''))
        body.append(f'<path {" ".join(attrs)}/>')
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'
            '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">'
            f'<stop offset="0" stop-color="{gradient[0]}"/><stop offset="1" stop-color="{gradient[1]}"/>'
            '</linearGradient></defs><rect width="108" height="108" fill="url(#g)"/>'
            f'<g transform="rotate({TILT} 54 56)">{"".join(body)}</g></svg>')


def write(path, text):
    with open(os.path.join(RES, path), 'w') as f:
        f.write(text)


for suffix, colors in BACKGROUNDS.items():
    write(f'drawable/ic_launcher_background{suffix}.xml', vector([], colors))
write('drawable/ic_launcher_foreground.xml', vector(FOREGROUND))
write('drawable/ic_launcher_foreground_monochrome.xml', vector(MONOCHROME))

SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
for suffix, colors in BACKGROUNDS.items():
    for density, size in SIZES.items():
        full = round(size * 108 / 72)
        art = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg(FOREGROUND, colors).encode(),
                                                     output_width=full, output_height=full))).convert('RGBA')
        margin = (full - size) // 2
        art = art.crop((margin, margin, margin + size, margin + size))
        for shape in ('', '_round'):
            # the mask drawn four times over and shrunk: smooth edges
            mask = Image.new('L', (size * 4, size * 4), 0)
            box = (0, 0, size * 4 - 1, size * 4 - 1)
            if shape:
                ImageDraw.Draw(mask).ellipse(box, fill=255)
            else:
                ImageDraw.Draw(mask).rounded_rectangle(box, radius=size * 4 * 0.18, fill=255)
            icon = Image.new('RGBA', (size, size), (0, 0, 0, 0))
            icon.paste(art, (0, 0), mask.resize((size, size), Image.LANCZOS))
            icon.save(os.path.join(RES, f'mipmap-{density}', f'ic_launcher{shape}{suffix}.png'), optimize=True)

# a sheet to look at: release, debug, themed (the monochrome layer tinted), not for the app
preview = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'preview.png')
sheet = Image.new('RGBA', (3 * 220 + 20, 240), (245, 245, 245, 255))
themed = [(d, f and '#1F3A5F', s and '#1F3A5F', w) for d, f, s, w in MONOCHROME]
for i, (shapes, colors) in enumerate(((FOREGROUND, BACKGROUNDS['']), (FOREGROUND, BACKGROUNDS['_debug']),
                                      (themed, ('#D7E3F7', '#D7E3F7')))):
    art = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg(shapes, colors).encode(), output_width=300,
                                                 output_height=300))).convert('RGBA').crop((50, 50, 250, 250))
    mask = Image.new('L', (200, 200), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, 199, 199), fill=255)
    sheet.paste(art, (20 + i * 220, 20), mask)
sheet.save(preview)
print('written; preview at', preview)

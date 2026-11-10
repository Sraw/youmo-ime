"""The animated picture of KeyboardPerfBaselineTest's A4 rows (design 060-render-p3 §4.3): a gradient
sliding and dots moving, 720x480 (the longest side an animated picture plays at), each frame 50 ms (20 fps, the fastest A4 plays). Needs Pillow with WebP.

  python3 uat/tools/perf_picture.py app/src/androidTest/assets/perf-picture.webp 30 50   # frames, quality
"""
import sys, math
from PIL import Image, ImageDraw

W, H, N = 720, 480, int(sys.argv[2])
frames = []
for i in range(N):
    t = i / N
    im = Image.new('RGB', (W, H))
    d = ImageDraw.Draw(im)
    for x in range(0, W, 8):
        k = (x / W + t) % 1.0
        c = (int(58 + (242 - 58) * k), int(110 + (193 - 110) * k), int(165 + (78 - 165) * k))
        d.rectangle([x, 0, x + 8, H], fill=c)
    for j in range(40):
        cx = (j * 97 + t * W) % W
        cy = (j * 61) % H
        r = 20 + j % 7 * 9
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255))
    frames.append(im)
frames[0].save(sys.argv[1], 'WEBP', save_all=True, append_images=frames[1:], duration=50, loop=0, quality=int(sys.argv[3]), method=4)
im = Image.open(sys.argv[1]); print(im.size, im.n_frames)

"""Recover alpha from the black/white render batches and repack into 2048px atlas pages (64x64 cells of 32px).

usage: python -I pack_atlas.py <recipedump/icons dir> <out atlas dir>
"""
import os, sys
import numpy as np
from PIL import Image

SRC, OUT = sys.argv[1:3]
os.makedirs(OUT, exist_ok=True)
cols, rows, icon, total, batches = map(int, open(os.path.join(SRC, 'manifest.txt')).read().split())
per = cols * rows
GRID = 64
pages = {}
for b in range(batches):
    blk = np.asarray(Image.open(os.path.join(SRC, f'b{b}_black.png')).convert('RGB'), dtype=np.float32)
    wht = np.asarray(Image.open(os.path.join(SRC, f'b{b}_white.png')).convert('RGB'), dtype=np.float32)
    a = 1.0 - (wht - blk).mean(axis=2) / 255.0
    a = np.clip(a, 0, 1)
    rgb = np.where(a[..., None] > 0.004, blk / np.maximum(a[..., None], 1e-3), 0)
    rgba = np.dstack([np.clip(rgb, 0, 255), a * 255]).astype(np.uint8)
    for k in range(per):
        i = b * per + k
        if i >= total: break
        cx, cy = (k % cols) * icon, (k // cols) * icon
        cell = rgba[cy:cy + icon, cx:cx + icon]
        p = i >> 12
        if p not in pages: pages[p] = np.zeros((GRID * icon, GRID * icon, 4), np.uint8)
        x, y = (i & 63) * icon, ((i >> 6) & 63) * icon
        pages[p][y:y + icon, x:x + icon] = cell
for p, arr in pages.items():
    Image.fromarray(arr, 'RGBA').save(os.path.join(OUT, f'a{p}.png'), optimize=True)
    print('page', p, os.path.getsize(os.path.join(OUT, f'a{p}.png')))

#!/usr/bin/env python3
"""Render the established SVG brand mark into Apple's required icon sizes."""
import io
import json
from pathlib import Path
import cairosvg
from PIL import Image

root = Path(__file__).resolve().parents[1]
assets = root / "ios/Resources/Assets.xcassets"
assets.mkdir(parents=True, exist_ok=True)
info = {"author": "xcode", "version": 1}
(assets / "Contents.json").write_text(json.dumps({"info": info}, indent=2) + "\n")
svg = root / "branding/torrenttoddl-logo.svg"


def render(path, pixels):
    raw = cairosvg.svg2png(url=str(svg), output_width=pixels, output_height=pixels)
    image = Image.open(io.BytesIO(raw)).convert("RGBA")
    background = Image.new("RGB", image.size, "#0B0F13")
    background.paste(image, mask=image.getchannel("A"))
    background.save(path)


icons = assets / "AppIcon.appiconset"
icons.mkdir(exist_ok=True)
images = []
for idiom, sizes in [("iphone", [(20, [2, 3]), (29, [2, 3]), (40, [2, 3]), (60, [2, 3])]),
                     ("ipad", [(20, [1, 2]), (29, [1, 2]), (40, [1, 2]), (76, [1, 2]), (83.5, [2])]),
                     ("ios-marketing", [(1024, [1])])]:
    for size, scales in sizes:
        for scale in scales:
            filename = f"{idiom}-{size}-{scale}.png"
            render(icons / filename, int(size * scale))
            images.append({"idiom": idiom, "size": f"{size}x{size}", "scale": f"{scale}x", "filename": filename})
(icons / "Contents.json").write_text(json.dumps({"images": images, "info": info}, indent=2) + "\n")
mark = assets / "AppMark.imageset"
mark.mkdir(exist_ok=True)
images = []
for scale in [1, 2, 3]:
    filename = f"mark-{scale}.png"
    render(mark / filename, 64 * scale)
    images.append({"idiom": "universal", "scale": f"{scale}x", "filename": filename})
(mark / "Contents.json").write_text(json.dumps({"images": images, "info": info}, indent=2) + "\n")
print("iOS icons generated from branding/torrenttoddl-logo.svg")

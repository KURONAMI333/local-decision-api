#!/usr/bin/env python3
"""Render the approved Local Inference API icon at distribution sizes."""

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent.parent
SIZE = 2048


def pixel(value: float) -> int:
    return round(value * SIZE)


def background() -> Image.Image:
    yy, xx = np.mgrid[0:SIZE, 0:SIZE]
    rgb = np.zeros((SIZE, SIZE, 3), dtype=np.float32)
    total = np.zeros((SIZE, SIZE), dtype=np.float32)
    base = np.array((68, 103, 198), dtype=np.float32)
    rgb += base * 0.18
    total += 0.18
    stops = (
        (0.10, 0.14, (234, 67, 53)),
        (0.88, 0.08, (249, 201, 53)),
        (0.90, 0.90, (45, 190, 113)),
        (0.08, 0.86, (66, 133, 244)),
    )
    for cx, cy, color in stops:
        distance = ((xx / SIZE - cx) ** 2 + (yy / SIZE - cy) ** 2) ** 0.72
        weight = 1.0 / (0.055 + distance)
        rgb += weight[..., None] * np.array(color, dtype=np.float32)
        total += weight
    rgb /= total[..., None]
    gray = (rgb[..., 0] * 0.2126 + rgb[..., 1] * 0.7152 + rgb[..., 2] * 0.0722)[..., None]
    rgb = (gray + (rgb - gray) * 1.25) * 0.84
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def icon() -> Image.Image:
    mask = Image.new("L", (SIZE, SIZE), 0)
    draw = ImageDraw.Draw(mask)

    def rounded(box: tuple[float, float, float, float], radius: float, fill: int) -> None:
        draw.rounded_rectangle([pixel(v) for v in box], radius=pixel(radius), fill=fill)

    rounded((0.20, 0.30, 0.80, 0.73), 0.12, 255)
    rounded((0.13, 0.41, 0.25, 0.63), 0.045, 255)
    rounded((0.75, 0.41, 0.87, 0.63), 0.045, 255)
    rounded((0.43, 0.22, 0.57, 0.34), 0.035, 255)
    rounded((0.29, 0.39, 0.71, 0.64), 0.080, 0)
    rounded((0.355, 0.435, 0.435, 0.595), 0.035, 255)
    rounded((0.565, 0.435, 0.645, 0.595), 0.035, 255)

    image = background()
    white = Image.new("RGBA", (SIZE, SIZE), "white")
    image.alpha_composite(Image.composite(white, Image.new("RGBA", (SIZE, SIZE)), mask))
    return image.resize((512, 512), Image.Resampling.LANCZOS)


def main() -> None:
    final = icon()
    for size in (512, 256, 128, 64):
        output = ROOT / "branding" / f"local_inference_api_icon_{size}.png"
        final.resize((size, size), Image.Resampling.LANCZOS).save(output)
    (ROOT / "common" / "src" / "main" / "resources" / "logo.png").write_bytes(
        (ROOT / "branding" / "local_inference_api_icon_256.png").read_bytes()
    )


if __name__ == "__main__":
    main()

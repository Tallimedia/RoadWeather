#!/usr/bin/env python3
"""Render the Play Store artwork that has no dependency on a built app.

  icon-512.png             app icon, 512x512
  feature-1024x500.png     feature graphic

Screenshots are deliberately NOT generated here — Play wants real captures from
the AAOS emulator, and a drawing of a screen that does not exist yet would be a
lie that has to be redone. Capture those once the app runs (see
PLAY-CONSOLE-SETUP.md).

Same visual family as VolvoWatch and FIWeatherWatch — dark navy, ice-blue
accent — so the listings read as siblings from one maker.

    python3 store/make_store_art.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = Path(__file__).parent

# Palette — FIWeatherWatch's, deliberately reused.
BG_TOP = (9, 13, 26)
BG_BOTTOM = (19, 26, 48)
WHITE = (245, 248, 253)
GREY = (150, 166, 194)
ACCENT = (94, 148, 255)
ICE = (138, 198, 255)
ROAD = (44, 54, 78)
ROAD_EDGE = (72, 86, 120)

SF = "/System/Library/Fonts/SFNS.ttf"
HELV = "/System/Library/Fonts/HelveticaNeue.ttc"


def font(size: int, weight: str = "regular") -> ImageFont.FreeTypeFont:
    try:
        f = ImageFont.truetype(SF, size)
        try:
            f.set_variation_by_name("Bold" if weight == "bold" else "Regular")
        except Exception:
            pass
        return f
    except Exception:
        return ImageFont.truetype(HELV, size)


def vertical_gradient(size: tuple[int, int], top, bottom) -> Image.Image:
    w, h = size
    grad = Image.new("RGB", (1, h))
    px = grad.load()
    for y in range(h):
        t = y / max(h - 1, 1)
        px[0, y] = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    return grad.resize((w, h))


def road(draw: ImageDraw.ImageDraw, cx: int, top: int, bottom: int,
         top_half_w: int, bottom_half_w: int, dash: bool = True) -> None:
    """A road in perspective, narrowing towards the horizon."""
    draw.polygon(
        [(cx - bottom_half_w, bottom), (cx + bottom_half_w, bottom),
         (cx + top_half_w, top), (cx - top_half_w, top)],
        fill=ROAD, outline=ROAD_EDGE, width=max(2, (bottom - top) // 120),
    )
    if not dash:
        return
    # Centre line, dashes shrinking with distance.
    y = bottom
    i = 0
    while y > top:
        t = (bottom - y) / max(bottom - top, 1)
        seg = max(4, int(44 * (1 - t) ** 1.6))
        gap = max(4, int(30 * (1 - t) ** 1.6))
        half = max(1, int(7 * (1 - t) ** 1.2))
        y2 = max(top, y - seg)
        if i % 2 == 0:
            draw.polygon([(cx - half, y), (cx + half, y),
                          (cx + max(1, half - 1), y2), (cx - max(1, half - 1), y2)],
                         fill=WHITE)
        y = y2 - gap
        i += 1


def frost(img: Image.Image, height: int) -> None:
    """An ice-blue wash down from the top — the frost the app is about."""
    w, h = img.size
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    for y in range(height):
        a = int(90 * (1 - y / height) ** 1.5)
        od.line([(0, y), (w, y)], fill=(*ICE, a))
    img.alpha_composite(overlay)


def glow(img: Image.Image, cx: int, cy: int, r: int, colour, strength: int = 140) -> None:
    """A soft blurred halo — gives a flat readout some depth without detail."""
    w, h = img.size
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    od.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(*colour, strength))
    overlay = overlay.filter(ImageFilter.GaussianBlur(r * 0.45))
    img.alpha_composite(overlay)


def sheen(img: Image.Image, box: tuple[int, int, int, int]) -> None:
    """A soft diagonal highlight across a region — reads as a glint off ice,
    not just a flat dark panel."""
    x0, y0, x1, y1 = box
    w, h = img.size
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    band = int((x1 - x0) * 0.22)
    sx = x0 + int((x1 - x0) * 0.62)
    od.polygon(
        [(sx, y0), (sx + band, y0), (sx - band * 2, y1), (sx - band * 3, y1)],
        fill=(*ICE, 55),
    )
    overlay = overlay.filter(ImageFilter.GaussianBlur(band * 0.35))
    img.alpha_composite(overlay)


def make_icon() -> None:
    """Legibility at 48 px decides this, not detail at 512.

    So: one big glyph and one unmistakable shape. The road is a band across the
    lower third, not a vanishing-point drawing — at launcher size a converging
    road is a grey triangle.
    """
    S = 512
    img = vertical_gradient((S, S), BG_TOP, BG_BOTTOM).convert("RGBA")
    frost(img, int(S * 0.40))

    horizon = int(S * 0.62)
    glow(img, S // 2, int(horizon * 0.42), int(S * 0.34), ICE, strength=110)

    d = ImageDraw.Draw(img)
    road(d, S // 2, horizon, S, int(S * 0.30), int(S * 0.72))
    d.line([(0, horizon), (S, horizon)], fill=ROAD_EDGE, width=5)
    sheen(img, (0, horizon, S, S))
    d = ImageDraw.Draw(img)

    f = font(216, "bold")
    t = "0°"
    box = d.textbbox((0, 0), t, font=f)
    ty = horizon - 46 - (box[3] - box[1]) - box[1]
    d.text(((S - (box[2] - box[0])) / 2 - box[0], ty), t, font=f, fill=ICE)
    img.convert("RGB").save(HERE / "icon-512.png")


MIPMAP_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def make_launcher_icon() -> None:
    """The in-app header badge, not the Play Store listing — these are
    different assets with a different crop. Confirmed from a real screenshot
    in the car, 2026-10-19: the host circle-crops this one, and the old
    edge-to-edge composition lost the '0°' top and nearly all of the road
    once masked. Content here stays inside a ~70%-diameter safe circle,
    centred; only the background gradient is allowed to bleed to the square
    edge, which a circular crop removes harmlessly.
    """
    S = 1024  # master render; downsampled per density below
    img = vertical_gradient((S, S), BG_TOP, BG_BOTTOM).convert("RGBA")
    frost(img, int(S * 0.40))

    scale = 0.70
    pad = int(S * (1 - scale) / 2)
    cs = int(S * scale)

    content = Image.new("RGBA", (cs, cs), (0, 0, 0, 0))
    horizon = int(cs * 0.60)
    glow(content, cs // 2, int(horizon * 0.42), int(cs * 0.36), ICE, strength=120)

    cd = ImageDraw.Draw(content)
    road(cd, cs // 2, horizon, cs, int(cs * 0.30), int(cs * 0.74))
    cd.line([(0, horizon), (cs, horizon)], fill=ROAD_EDGE, width=max(2, cs // 160))
    sheen(content, (0, horizon, cs, cs))

    cd = ImageDraw.Draw(content)
    f = font(int(cs * 0.40), "bold")
    t = "0°"
    box = cd.textbbox((0, 0), t, font=f)
    ty = horizon - int(cs * 0.08) - (box[3] - box[1]) - box[1]
    cd.text(((cs - (box[2] - box[0])) / 2 - box[0], ty), t, font=f, fill=ICE)

    img.alpha_composite(content, (pad, pad))
    rgb = img.convert("RGB")

    res_dir = HERE.parent / "automotive" / "src" / "main" / "res"
    for name, size in MIPMAP_SIZES.items():
        out = res_dir / name / "ic_launcher.png"
        rgb.resize((size, size), Image.LANCZOS).save(out)
        print(f"{name}/ic_launcher.png  {size}x{size}  {out.stat().st_size // 1024} kB")


def make_feature() -> None:
    W, H = 1024, 500
    img = vertical_gradient((W, H), BG_TOP, BG_BOTTOM).convert("RGBA")
    frost(img, int(H * 0.5))
    d = ImageDraw.Draw(img)
    road(d, int(W * 0.86), int(H * 0.30), H, 86, 300)

    d.text((64, 150), "Finnish", font=font(62, "bold"), fill=WHITE)
    d.text((64, 214), "RoadWeather", font=font(62, "bold"), fill=ACCENT)
    d.text((64, 306), "Road surface conditions, ice risk", font=font(27), fill=GREY)
    d.text((64, 342), "and Finnish weather — in the car.", font=font(27), fill=GREY)
    d.text((64, 404), "Ilmatieteen laitos  ·  Fintraffic", font=font(21), fill=(98, 112, 138))
    img.convert("RGB").save(HERE / "feature-1024x500.png")


if __name__ == "__main__":
    make_icon()
    make_launcher_icon()
    make_feature()
    for p in ("icon-512.png", "feature-1024x500.png"):
        im = Image.open(HERE / p)
        print(f"{p:24} {im.size[0]}x{im.size[1]}  {(HERE / p).stat().st_size // 1024} kB")

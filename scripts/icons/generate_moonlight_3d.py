#!/usr/bin/env python3
"""Generate the Moonlight 3D Android launcher and notification icon family."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "app" / "src" / "main"
RES = MAIN / "res"

CANVAS = 1024
VISIBLE_INSET = 52
ADAPTIVE_SYMBOL_SCALE = 0.76

ACCENT = "#8AB4F8"
ACCENT_BRIGHT = "#D7E5FF"

DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def cubic(p0, p1, p2, p3, steps=36):
    points = []
    for index in range(steps + 1):
        t = index / steps
        u = 1.0 - t
        points.append((
            u**3 * p0[0] + 3 * u**2 * t * p1[0] + 3 * u * t**2 * p2[0] + t**3 * p3[0],
            u**3 * p0[1] + 3 * u**2 * t * p1[1] + 3 * u * t**2 * p2[1] + t**3 * p3[1],
        ))
    return points


def draw_round_line(draw, points, fill, width):
    draw.line(points, fill=fill, width=width, joint="curve")
    radius = width // 2
    # Pillow can leave hairline wedges between steep polyline segments even
    # with joint="curve". Filling each sampled joint keeps the generated
    # horizon bands solid at both launcher and store-art sizes.
    for x, y in points:
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=fill)


def draw_symbol(monochrome=False):
    image = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    moon_color = "#FFFFFF" if monochrome else ACCENT_BRIGHT
    far_color = "#FFFFFF" if monochrome else ACCENT_BRIGHT
    near_color = "#FFFFFF" if monochrome else ACCENT

    # A crescent loses much more optical mass than its outer circle suggests.
    # Make it wider than Sunshine 3D's solid sun so both marks feel equally
    # prominent after the launcher reduces them to 48 px.
    draw.ellipse((300, 184, 724, 608), fill=moon_color)
    draw.ellipse((405, 108, 793, 496), fill=(0, 0, 0, 0))

    # These are the same near/far planes used by the Sunshine 3D host mark.
    # Their vertical offset remains legible after launcher masking.
    far_plane = cubic((188, 704), (342, 582), (682, 582), (836, 704))
    draw_round_line(draw, far_plane, far_color, 104)
    near_plane = cubic((164, 810), (338, 650), (686, 650), (860, 810))
    draw_round_line(draw, near_plane, near_color, 112)

    return image


def scale_about_center(image, scale):
    scaled_size = round(CANVAS * scale)
    scaled = image.resize((scaled_size, scaled_size), Image.Resampling.LANCZOS)
    result = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    offset = (CANVAS - scaled_size) // 2
    result.alpha_composite(scaled, (offset, offset))
    return result


def draw_legacy_icon(symbol):
    image = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    image.alpha_composite(symbol)
    visible_box = (
        VISIBLE_INSET,
        VISIBLE_INSET,
        CANVAS - VISIBLE_INSET,
        CANVAS - VISIBLE_INSET,
    )
    return image.crop(visible_box).resize(
        (CANVAS, CANVAS),
        Image.Resampling.LANCZOS,
    )


def svg_for():
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="{VISIBLE_INSET} {VISIBLE_INSET} {CANVAS - 2 * VISIBLE_INSET} {CANVAS - 2 * VISIBLE_INSET}" role="img" aria-labelledby="moonlight3d-title moonlight3d-description">
  <title id="moonlight3d-title">Moonlight 3D</title>
  <desc id="moonlight3d-description">A crescent moon above two curved depth planes.</desc>
  <defs>
    <mask id="moonlight3d-crescent" maskUnits="userSpaceOnUse" x="0" y="0" width="1024" height="1024">
      <rect width="1024" height="1024" fill="#000000"/>
      <circle cx="512" cy="396" r="212" fill="#FFFFFF"/>
      <circle cx="599" cy="302" r="194" fill="#000000"/>
    </mask>
  </defs>
  <rect width="1024" height="1024" fill="#D7E5FF" mask="url(#moonlight3d-crescent)"/>
  <path d="M188 704C342 582 682 582 836 704" fill="none" stroke="#D7E5FF" stroke-width="104" stroke-linecap="round"/>
  <path d="M164 810C338 650 686 650 860 810" fill="none" stroke="#8AB4F8" stroke-width="112" stroke-linecap="round"/>
</svg>
"""


def save_png(master, path, size):
    path.parent.mkdir(parents=True, exist_ok=True)
    resized = master.resize((size, size), Image.Resampling.LANCZOS)
    resized.save(path, format="PNG", optimize=True)


def require_transparent_canvas(image, label):
    alpha = image.getchannel("A")
    transparent_fraction = alpha.histogram()[0] / (image.width * image.height)
    if transparent_fraction < 0.5 or alpha.getpixel((0, 0)) != 0:
        raise RuntimeError(
            f"{label} must retain a transparent canvas; "
            f"fully transparent area was only {transparent_fraction:.1%}"
        )


def main():
    color_symbol = draw_symbol()
    legacy_icon = draw_legacy_icon(color_symbol)
    adaptive_foreground = scale_about_center(color_symbol, ADAPTIVE_SYMBOL_SCALE)
    notification_icon = draw_symbol(monochrome=True)
    for label, image in (
        ("legacy launcher", legacy_icon),
        ("adaptive foreground", adaptive_foreground),
        ("notification", notification_icon),
    ):
        require_transparent_canvas(image, label)

    (ROOT / "moonlight3d.svg").write_text(svg_for(), encoding="utf-8", newline="\n")

    for density, (legacy_size, foreground_size) in DENSITIES.items():
        mipmap = RES / f"mipmap-{density}"
        save_png(legacy_icon, mipmap / "ic_launcher.png", legacy_size)
        save_png(
            adaptive_foreground,
            mipmap / "ic_launcher_foreground.png",
            foreground_size,
        )

    save_png(legacy_icon, MAIN / "ic_launcher-web.png", 512)
    save_png(notification_icon, RES / "drawable" / "app_icon.png", 96)
    print("Generated Moonlight 3D Android icon family.")


if __name__ == "__main__":
    main()

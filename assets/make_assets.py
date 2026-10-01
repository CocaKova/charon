#!/usr/bin/env python3
"""Regenerate the README header art (hero-dark.svg, hero-light.svg). Stdlib only."""
import os

HERE = os.path.dirname(os.path.abspath(__file__))

# GitHub-like neutrals; the accent is the app's own Styx teal (theme/Color.kt), darkened for light.
THEMES = {
    "dark": dict(bg="#0d1117", panel="#161b22", line="#30363d", text="#e6edf3", dim="#8b949e",
                 accent="#3ecfb2", coin="#d9a441"),
    "light": dict(bg="#ffffff", panel="#f6f8fa", line="#d0d7de", text="#1f2328", dim="#656d76",
                  accent="#0e7c6b", coin="#9a6700"),
}
FONT = "ui-sans-serif, -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif"
MONO = "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace"


def box(x, y, w, h, label, sub, c, stroke):
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{c["panel"]}" stroke="{stroke}" stroke-width="1.5"/>'
            f'<text x="{x + w / 2}" y="{y + 30}" text-anchor="middle" font-family="{FONT}" font-size="17" font-weight="600" fill="{c["text"]}">{label}</text>'
            f'<text x="{x + w / 2}" y="{y + 52}" text-anchor="middle" font-family="{FONT}" font-size="12.5" fill="{c["dim"]}">{sub}</text>')


def both_ways(x1, x2, y, color):
    """A two-headed arrow: keys go one way, screen output comes back."""
    return (f'<line x1="{x1 + 7}" y1="{y}" x2="{x2 - 7}" y2="{y}" stroke="{color}" stroke-width="1.8"/>'
            f'<path d="M{x2 - 8},{y - 5} L{x2},{y} L{x2 - 8},{y + 5} Z" fill="{color}"/>'
            f'<path d="M{x1 + 8},{y - 5} L{x1},{y} L{x1 + 8},{y + 5} Z" fill="{color}"/>')


def waves(x, y, color):
    # The launcher icon's three river lines, small, beside the name.
    out = []
    for i, op in enumerate((1.0, 0.7, 0.45)):
        yy = y + i * 7
        out.append(f'<path d="M{x},{yy} q4,-4 8,0 t8,0 t8,0 t8,0" fill="none" stroke="{color}" '
                   f'stroke-width="2" stroke-linecap="round" opacity="{op}"/>')
    return "".join(out)


def hero(c):
    W, H = 1200, 330
    s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" role="img" '
         f'aria-label="Charon: phone keyboard, the terminal-core emulator, an SSH session, and your hosts">',
         f'<rect width="{W}" height="{H}" rx="16" fill="{c["bg"]}" stroke="{c["line"]}"/>',
         f'<circle cx="74" cy="66" r="11" fill="{c["coin"]}"/><circle cx="74" cy="66" r="4.5" fill="{c["bg"]}"/>',
         waves(58, 84, c["accent"]),
         f'<text x="108" y="78" font-family="{MONO}" font-size="38" font-weight="700" fill="{c["text"]}">charon</text>',
         f'<text x="60" y="128" font-family="{FONT}" font-size="18" fill="{c["dim"]}">'
         'An Android SSH client with a terminal emulator written from scratch. Nothing functional is paywalled.</text>']
    y, w, h = 170, 240, 70
    xs = [60, 340, 620, 900]
    s.append(box(xs[0], y, w, h, "keyboard + key row", "IME words · sticky Ctrl/Alt · F-keys", c, c["line"]))
    s.append(box(xs[1], y, w, h, "terminal-core", "VT/xterm · truecolor · inline images", c, c["accent"]))
    s.append(box(xs[2], y, w, h, "SSH session", "shell · SFTP · port forwards", c, c["line"]))
    s.append(box(xs[3], y, w, h, "your hosts", "tmux · tailnet · LAN", c, c["line"]))
    for a, b in zip(xs, xs[1:]):
        s.append(both_ways(a + w, b, y + h / 2, c["dim"]))
    s.append(f'<text x="60" y="284" font-family="{FONT}" font-size="13" fill="{c["dim"]}">'
             f'Keys and passwords sealed by the Android Keystore · whole vault exports to one '
             f'<tspan font-family="{MONO}" fill="{c["text"]}">.charon</tspan> file (Argon2id + AES-GCM) · no cloud account</text>')
    s.append("</svg>")
    return "".join(s)


for theme, colors in THEMES.items():
    with open(os.path.join(HERE, f"hero-{theme}.svg"), "w", encoding="utf-8") as f:
        f.write(hero(colors))
print("ok")

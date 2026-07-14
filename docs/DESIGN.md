# Charon — Design Identity

## The look: character-grid to the bone

Charon is a terminal app, and its visual identity commits to that: **the signature
visual is an animated braille ASCII sea** — the waters of the Styx rendered in U+2800
braille cells, monospace, animated at ~10 fps. Not pixels pretending to be a terminal;
actual text.

Where the sea appears:
- **Landing / empty states** — the river waits below the wordmark
- **Connect-in-progress** — you are crossing (later: a small boat glyph traversing the waves)
- **Drawer header ornament** (subtle, few rows)
- Anywhere a spinner would be boring

Implementation: `presentation/components/BrailleSea.kt`. Each braille char is a 2x4 dot
matrix; a cols x rows text grid gives a (2·cols) x (4·rows) dot field. The surface is
three layered sines (swell + drift + ripple); dots below the surface are filled; rows
fade with depth. Pure Compose `Text`, no Canvas, battery-kind frame rate.

## Palette (theme/Color.kt)

| Token | Hex | Role |
|---|---|---|
| StyxBlack | `#000000` | OLED background — the far shore |
| AbyssInk | `#0A0F14` | surfaces |
| DepthSlate | `#141C24` | raised surfaces |
| StyxTeal | `#3ECFB2` | primary — the water's glow |
| DeepTeal | `#1C5F53` | dimmed water |
| ObolGold | `#D9A441` | secondary — the ferryman's coin (reserved for supporter/payment moments) |
| BoneWhite | `#E6EDF3` | primary text |
| MistGrey | `#8FA3AD` | secondary text |
| WarnEmber | `#E0563E` | errors, host-key mismatch red screen |

Dark-first (Charon works at night); the light theme exists but is not the identity.

## Type

Monospace-forward everywhere, not just the terminal. JetBrains Mono ships as the bundled
terminal font (OFL); UI chrome uses platform mono until then. Wordmark: `CHARON`,
letter-spaced, teal.

## Motifs

- **The obol**: coin iconography for anything payment/supporter related. The app icon is
  the obol above the waters.
- **Crossing the river**: connection flows are framed as crossings — connect = "crossing"
  (the sea quickens, then the terminal crossfades in), disconnect = "return to shore",
  TOFU trust prompt = "meeting the ferryman" (fingerprint as the toll).
- Restraint: mythology flavors labels and empty states; it never obscures function.
  Error messages stay literal.

## Terminal chrome — how Charon reads different from Termux/Termius

Termux is a bare grid with stock buttons; Termius buries the terminal in toolbar. Charon
wears **one thin band of instrument panel** and otherwise gets out of the way:

- **Status strip** above the grid: state dot (StyxTeal breathing = connected, ObolGold =
  crossing, WarnEmber = lost) + `user@host` + live `cols×rows`. It later becomes the
  session switcher (v0.5) — the chrome earns its pixels before it multiplies.
- **Accessory row** is part of the instrument: JetBrains Mono labels, DepthSlate key
  pills on AbyssInk, press = quick sink-and-spring scale + teal ripple. Sticky Ctrl/Alt
  read the theme — **teal = armed** (charged one-shot), **obol-gold = locked**;
  long-press latches. Long-press variants (tab→back-tab, symbols→shifted mates),
  auto-repeat arrows, and a gold **Fn** page (F1–F12) all ship in v0.4. Full model in
  `docs/INPUT.md`.
- **Touch is expressive, in theme**: selection washes StyxTeal with a teal **copy**
  pill; scrolled-back history shows a gold **▼ live** pill (the mouse-app wheel and the
  scrollback share one drag). Gesture/selection/mouse model: `docs/INPUT.md`.
- **Cursor** is the one always-on brand mark inside the grid: a StyxTeal block with a
  soft 530 ms blink; the off-phase keeps a hairline teal outline (never vanishes), and
  output holds it solid.
- **JetBrains Mono everywhere** — terminal cells and UI chrome share one voice.
- The grid itself stays honest: correct rendering beats decoration; themes come as
  curated ANSI-16 schemes (default scheme "Styx", tuned to the palette) once the
  palette-base plumbing lands in terminal-core.

## The Dock (connection hub)

The landing surface evolves into a hub — v0.2's host vault is its first real form:

- Saved hosts as cards (name, user@host, tags, reachability dot later), the sea beneath.
- Quick connect stays, demoted to one card among the fleet.
- v0.1.x precursor: the last crossing's host/user are remembered (never the password)
  and prefill the form — reconnect is two taps.
- Single live session for now; when multi-session lands (v0.5) the Dock and the status
  strip are the two faces of the same session list.

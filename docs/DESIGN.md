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
- **Crossing the river**: connection flows are framed as crossings — connect = "crossing",
  disconnect = "return to shore", TOFU trust prompt = "meeting the ferryman" (fingerprint
  as the toll).
- Restraint: mythology flavors labels and empty states; it never obscures function.
  Error messages stay literal.

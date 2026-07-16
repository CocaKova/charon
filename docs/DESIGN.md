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

- **Session switcher** above the grid (v0.5): one tab per live crossing — a breathing
  state dot (StyxTeal = connected, ObolGold = crossing, WarnEmber = lost) + `user@host`
  + a `×` to close; the active tab wears DepthSlate. A trailing teal **+** drops to the
  Dock to raise another crossing. Grew out of the v0.1 single status strip — the chrome
  earned its pixels, then multiplied.
- **Accessory row** is part of the instrument: JetBrains Mono labels, DepthSlate key
  pills on AbyssInk, press = quick sink-and-spring scale + teal ripple. Sticky Ctrl/Alt
  read the theme — **teal = armed** (charged one-shot), **obol-gold = locked**;
  long-press latches. Long-press variants (tab→back-tab, symbols→shifted mates),
  auto-repeat arrows, and a gold **Fn** page (F1–F12) all ship in v0.4. Full model in
  `docs/INPUT.md`.
- **Smart autofill** (v0.5.2–3): a slim strip above the accessory row offers inline
  completions — your history, command grammar (tmux/git/docker/systemctl…), and the
  **live host** (installed commands, running tmux sessions, containers — probed over a
  silent exec channel). Chips: gold `»`, typed part dimmed, completion glowing teal; a
  tap types only the missing tail, cascading into the next word's offers. Mechanism in
  `docs/INPUT.md §2b`.
- **The grid breathes**: a touch of line leading (glyphs re-centred in the taller cell)
  and a slim gutter keep text off the screen's corners — legible, not cramped, without
  reading childish. Default 14 sp, pinch-zoom 8–32 sp persisted. The soft keyboard drops
  the instant a crossing ends or you step back to the Dock.
- **Touch is expressive, in theme**: selection washes StyxTeal with a teal **copy**
  pill; scrolled-back history shows a gold **▼ live** pill (the mouse-app wheel and the
  scrollback share one drag). Gesture/selection/mouse model: `docs/INPUT.md`.
- **The toll** (v0.7.3): when the remote reads a secret (sudo/ssh/su — recognized by
  prompt grammar plus an echo net), a gold-ringed pill rises top-center: "**the
  ferryman asks the toll**", an obol coin that flips once per hidden keystroke (never
  a length gauge), "**the toll is paid**" in gold + Confirm haptic on Enter. Beneath
  the ceremony, the real work: hidden keystrokes never touch autofill, history, or the
  IME's dictionary (the keyboard itself flips to a password editor). `docs/INPUT.md §2c`.
- **The lading** (v0.7.3): a package install (apt/dnf/pacman/pip/npm…) sails a strip
  at the live edge — a gold **laden barge** crossing braille water, steered by the
  manager's own on-screen percent (patrolling when there isn't one), the package under
  hand named beneath, "cargo ashore" at 100%. The wall of scrolling text becomes a
  crossing you can watch. `docs/INPUT.md §2c`.
- **Cursor** is the one always-on brand mark inside the grid: a StyxTeal block with a
  soft 530 ms blink; the off-phase keeps a hairline teal outline (never vanishes), and
  output holds it solid.
- **JetBrains Mono everywhere** — terminal cells and UI chrome share one voice.
- The grid itself stays honest: correct rendering beats decoration; themes come as
  curated ANSI-16 schemes (default scheme "Styx", tuned to the palette) once the
  palette-base plumbing lands in terminal-core.

## The Dock (connection hub)

The landing surface evolves into a hub — v0.2's host vault is its first real form:

- Saved hosts as **moorings** (name, user@host, last-crossed, a lantern dot), the sea beneath.
- Quick connect stays, demoted to one card among the fleet.
- v0.1.x precursor: the last crossing's host/user are remembered (never the password)
  and prefill the form — reconnect is two taps.

### Scaling the fleet — harbors, lanterns, search (v0.5)

A homelab grows past a flat list, so the Dock categorises without clutter:

- **Harbors** — each mooring can name a harbor (free-text, autocompletes from harbors
  already in the fleet). The Dock renders **collapsible sections**: named harbors first
  (alphabetical, `▾`/`▸` caret + count), the un-harbored moorings under a final
  *unsorted* header. A plain fleet with no harbors stays a plain headerless list — the
  structure only appears once it earns its keep.
- **Lantern** — a per-host colour tag from a small curated palette (or the default
  dimmed teal glow), shown as the mooring's dot. Categorises at a glance; reachability
  dots (live TCP dial) take this same spot at the fleet milestone.
- **Search** — a filter field surfaces once the fleet passes a handful of hosts; it
  matches label / address / harbor and drops the headers for a flat result run.
- **Underway strip** — when crossings are live and you've stepped back to the Dock (the
  switcher's **+**), a row of tap-to-resume chips rides the top: the two faces of the
  one session list.

## The Hold (v0.6 — SFTP)

The ferry carries cargo, and it's part of the fare (Termius paywalls SFTP; Charon
never will). The gold **⇅** in the session switcher opens the active session's deck:

- **Single pane**, JetBrains Mono, quiet sigils: directories are teal doors (`▸`),
  files bone cargo (`·`), symlinks `⇝`; size + mtime in mist. `↰ ..` climbs.
- **Cargo sheet** (tap a file / long-press anything): **carry ashore** (download via
  SAF — the user points at the landing spot), **rename**, **release into the river**
  (delete, ember, with a plain-words confirm). **⇡ aboard** in the top bar uploads
  any SAF document into the current directory; **+dir** and **↻** round it out.
- **The ledger** — transfers stream over their own channels (a long pull never blocks
  browsing), show live progress rows at the bottom (teal pulls, gold pushes), and
  **resume themselves** from the landed byte offset through hiccups. The FGS keeps
  them alive in the background.
- Errors stay in-theme but honest: "the hold is unreachable", then a retry.

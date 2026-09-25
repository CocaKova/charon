# terminal-core — Feature Matrix & Conformance Checklist

`:terminal-core` is a pure Kotlin/JVM module. Zero Android imports — everything here is
unit-tested on the JVM. The bar for v0: **vim, htop, and tmux render correctly.**

## Architecture

- `Utf8Decoder` — streaming bytes → code points; invalid sequences → U+FFFD; total
- `Parser` — Paul Flo Williams' VT500 state machine (ground, escape, escape_intermediate,
  csi_entry, csi_param, csi_intermediate, csi_ignore, osc_string, dcs_entry, dcs_param,
  dcs_intermediate, dcs_passthrough, dcs_ignore, sos_pm_apc_string); emits actions into
  the emulator; **total** — no input may throw (fuzz-tested)
- `TerminalEmulator` — the grid mutator + mode state; emits responses via a callback
- `ScreenBuffer` / `Line` — ring-buffer scrollback; packed-Long attrs (`CellAttrs`);
  `Line.isWrapped` continuation flag from day one (enables reflow later, no reflow in v1)
- `KeyEncoder` / `MouseEncoder` — pure input-encoding functions

## v0 feature checklist (walking skeleton)

- [x] C0 controls: BEL BS HT LF VT FF CR SO SI
- [x] CSI cursor: CUU CUD CUF CUB CNL CPL CHA CUP HVP VPA
- [x] CSI edit: ED EL ECH ICH DCH IL DL SU SD
- [x] SGR: 0-9, 21-29, 30-37/39, 40-47/49, 90-97, 100-107, 38;5 48;5, 38;2 48;2 (truecolor)
- [x] DECSTBM scroll regions + origin mode (DECOM)
- [x] Autowrap **with deferred-wrap (pending-wrap) semantics** — explicit tests
- [x] Tab stops: HT HTS TBC (BitSet)
- [x] Modes: DECCKM DECTCEM DECAWM IRM
- [x] Alternate screen: 47 / 1047 / 1048 / 1049 (with cursor save/restore)
- [x] DECSC/DECRC, RI IND NEL, RIS + DECSTR
- [x] Charsets: G0/G1 designation, DEC Special Graphics (vim/tmux line-drawing)
- [x] Responses: DA1 (VT220-class), DA2, DSR 5, DSR 6/CPR (origin-aware), OSC 10/11
      color queries, CSI 14t/18t size reports
- [x] wcwidth: generated UCD table; combining = 0; wide CJK = 2; VS16 → 2; ambiguous-width setting

## v0.4 additions

- [x] Mouse **mode tracking** parsed: 9/1000/1002/1003, SGR 1006, focus 1004, bracketed 2004
- [x] Mouse **encoding**: `input/MouseEncoder` — SGR 1006 + legacy X10/normal, mode-gated,
  wheel 64/65 (skip 1005; 1015 not needed). Touch→mouse wiring in the app layer.
- [x] Bracketed paste (2004) honored on paste via `KeyEncoder.paste`
- [x] Selection + copy (`TextSelection`, scroll-aware) and scrollback + wheel-scroll
  (`ScreenBuffer.viewLine`) — see `docs/INPUT.md`
- [x] Back-tab (CBT `ESC[Z`) in `KeyEncoder`
- [ ] OSC 0/1/2 title; OSC 52 clipboard (behind per-host consent); OSC 4/104 palette
- [ ] DECRQSS (minimal) — XTVERSION landed in v1.1 (see below)
- [ ] Hardware keyboard (Ctrl/Alt combos, Ctrl+Shift+C/V)

The **input/interaction** surface (accessory row, gestures, selection, mouse, IME) is
documented end-to-end in `docs/INPUT.md`.

## v1.1 — Apparitions (inline images)

*The shades made visible on the black water.* Two wire protocols in, one placement
model out. See `docs/FRONTIER.md` Tier 1 for the why.

- [x] **Kitty graphics protocol** — `ESC _ G k=v,… ; base64 ESC \`. The parser now
  splits **APC** out of the old SOS/PM/APC discard state (`Parser.apcDispatch`);
  SOS and PM keep being thrown away.
  - actions `a=t` (transmit), `a=T` (transmit + display), `a=p` (put a stored
    image), `a=q` (query), `a=d` (delete)
  - formats `f=100` (PNG/JPEG/GIF/WebP), `f=24` (RGB), `f=32` (RGBA)
  - `t=d` direct transmission only; `t=f/t/s` answer `EBADF` rather than lying
  - chunking (`m=1`/`m=0`), zlib payloads (`o=z`)
  - ids (`i`), image numbers (`I`), placement ids (`p`), z-index (`z`)
  - source crop `x,y,w,h`; cell size `c,r`; cursor policy `C`; quiet `q=1/2`
  - deletes `d=a/A` (all) and `d=i/I` (by image), uppercase also frees the pixels
- [x] **iTerm2 inline images** — `OSC 1337 ; File=inline=1;width=…;height=… : base64`.
  `width`/`height` in cells, `Npx`, `N%` or `auto`; `preserveAspectRatio` honoured.
  A `File=` without `inline=1` is a file transfer and is deliberately ignored.
- [x] **XTVERSION** (`CSI > q` → `DCS > | Charon(<version>) ST`) and **`CSI 16 t`**
  (cell size in pixels). Without these, tools that gate their graphics path on
  recognising the terminal never even try.
- [x] **PTY pixel dimensions** — `ws_xpixel`/`ws_ypixel` are sent on PTY allocation
  and on every window change (including a pinch that only changes the cell size).
  A PTY reporting 0×0 sends `kitten icat` down its no-graphics path before a single
  escape reaches us.
- [x] **Placement model** — an image is anchored to the `Line` its top-left cell sits
  on (`Line.apparitions`), not to a row number. The line object is what moves up the
  grid and into scrollback, so images scroll with their text for free, and
  `Line.clear()` drops them — clearing a line clears what was drawn over it.
- [x] **Byte budget** — `ApparitionStore` holds transmitted images under an LRU cap
  (24 MB); the app-side `ApparitionCache` caps decoded bitmaps separately (48 MB) and
  keys on image *identity*, so a re-transmitted id never draws the old picture.

Not aboard yet, and honest about it: **Sixel** (`DCS q`), Kitty **animation**
(`a=f`/`a=c`, answered `ENOTSUP`), Kitty **Unicode placeholders** (`U=1`, the
tmux-safe placement path), and **relative/virtual placements**.

## Kitty keyboard protocol (progressive enhancement)

*The Ferryman hears every word.* Spec: <https://sw.kovidgoyal.net/kitty/keyboard-protocol/>.
The encoder is a port of kitty's own `key_encoding.c`, so where the prose could be read
two ways, Charon answers the way crossterm/libtermkey/fish are tested against.

**Mode stack** (`KittyKeyboardStack`, one per screen — main and alternate never share):

| Sequence | Meaning |
|---|---|
| `CSI > flags u` | push (flags default 0); a full stack (16) evicts its oldest entry |
| `CSI < n u` | pop n (default 1; an explicit 0 pops 1); popping past the bottom empties it |
| `CSI = flags ; mode u` | modify the top entry: mode 1 replace (default), 2 OR, 3 AND-NOT; creates the entry on an empty stack |
| `CSI ? u` | query → `CSI ? flags u` |

Undefined bits are masked off (flags ∈ 0‥31). RIS and DECSTR clear both stacks (as kitty's
resets do), so `reset` recovers from a program that died mid-enhancement. The alternate
screen's stack survives leaving and re-entering it, as in kitty. Plain `CSI u` is still
SCORC.

**What each flag does on the wire** (`KeyEncoder.encodeKitty*`; the app reads
`TerminalEmulator.kittyKeyboardFlags` beside `cursorKeysApp`):

| Flag | Supported | Notes |
|---|---|---|
| 1 disambiguate | yes | Esc → `CSI 27u`; Ctrl/Alt/Super chords on text keys → `CSI cp;mods u` (Ctrl+I ≠ Tab, Ctrl+[ ≠ Esc); modified Enter/Tab/Backspace → `CSI 13/9/127;mods u`; Shift+Tab → `CSI 9;2u`; arrows/Home/End/F1–F4 drop SS3 and DECCKM (`CSI A`, `CSI 1;5D`, `CSI P`, F3 = `CSI 13~`) |
| 2 event types | yes, where Android delivers them | repeat `:2`, release `:3`. Hardware keyboards report all three (`onKeyDown` repeatCount / `onKeyUp`); the soft keyboard in **RAW** mode also reports releases (its IME synthesizes the up-stroke). The accessory row and predictive-IME text have no key-up and report presses only. Unmodified Enter/Tab/Backspace never report a release below flag 8 (spec) |
| 4 alternate keys | yes | shifted key when Shift is held (`CSI 97:65;6u`) and the US PC-101 base-layout key when it differs (`CSI 97::113;5u` on AZERTY) — Android keycodes name physical US positions, so the keycode answers it. Soft-keyboard chars carry a shifted key only for A–Z |
| 8 all keys as escapes | yes, for key events | plain letters, Enter/Tab/Backspace and bare modifier keys (Shift, Ctrl, Alt, Super, Caps/Num/Scroll Lock → `CSI 574xx u`) all report as escapes from the hardware keyboard / RAW mode |
| 16 associated text | yes | typed text as code points in the third field (`CSI 97;2;65u`); releases carry none |

Modifiers are `1 + bits` (shift 1, alt 2, ctrl 4, super 8, caps 64, num 128); Android's
Meta (Windows/⌘) key is reported as **super**. Lock bits are sent as the spec requires, so
Ctrl+A with Num Lock on is `CSI 97;133u`. Flags 4 and 16 alone change nothing (they only
reshape escapes the other bits produce), and flag 2 alone keeps legacy bytes for Esc and
Ctrl/Alt letters — both exactly as kitty.

**Input paths.**

- *Hardware keyboard* (`TerminalInputView.onKeyDown/onKeyUp` → `HardwareKeys`): the full
  event — key, modifiers, press/repeat/release.
- *Accessory row*: sticky Ctrl **and** Alt fold into the key's escape (sticky Ctrl + ← is
  `CSI 1;5D`; in legacy, Ctrl still doesn't apply to special keys and Alt stays an ESC
  prefix). A single typed character with a sticky modifier goes through
  `KeyEncoder.encodeKittyChar` (CR = Enter, DEL = Backspace, `C` = Shift+c).
- *Soft keyboard, predictive mode*: **committed text stays plain text at every flag
  level**, as kitty does with its own IME commits. The IME speaks in words, corrections
  and backspace diffs, not key events; turning a glide-typed word into per-letter escapes
  would break the one thing that mode exists for. helix, nvim and fish push flags 1
  (+4), where plain text is plain text anyway, so nothing they need is lost; a program
  that pushes flag 8 still receives what you type, just not as escapes. Use RAW mode if a
  flag-8 program must see soft-keyboard keys as key events.

With no flags pushed every path is byte-for-byte the legacy encoder
(`KeyEncoderTest.legacyTableIsPinned`).

**Not aboard:** keypad keys stay their non-keypad equivalents (`KP_*` codes are never
sent — Android's numpad Enter is Enter); F13–F35, media keys and Hyper/Meta modifiers
(no Android source for them). AltGr (right Alt alone) counts as typing when the layout
turns it into a character, and as Alt otherwise — the legacy path, unchanged, still
treats it as Alt.

## Explicit non-goals (until someone asks)

Scrollback reflow (v1.x backlog — data model is ready via `isWrapped`), OSC 8
(v1.x), perfect grapheme clustering (we match remote `wcwidth()` — that's what programs
lay out against).

## Test strategy

1. **Unit tests per control function** — hundreds of small JVM tests
2. **Corpus goldens** (`src/test/resources/corpus/`): byte streams recorded with `script`
   (TERM=xterm-256color, 80x24 and 120x40): vim syntax-highlighted file, htop, tmux
   split panes, `ls --color`, truecolor script. Replay → assert full grid render +
   per-cell attr checksums
3. **Fuzz totality**: random byte streams — never throws, never OOMs
4. **Throughput benchmark**: ≥50 MB/s mixed SGR text on the JVM
5. **v1.0 gate**: vttest core screens + esctest subset run over a real Charon session —
   checklist tracked here

## Conformance log

- 2026-07-13 — v0 checklist implemented; 98 JVM tests green; corpus goldens locked
  (vim syntax screen, htop meters, tmux split, ls --color, truecolor gradient);
  parse throughput 91.7 MB/s on the Spark (floor: 50).
- 2026-07-13 — v0.4 input slice: MouseEncoder + TextSelection + scrollback viewport
  landed with unit tests; verified on-device against the Spark (htop mouse click +
  wheel, select/copy/bracketed-paste, scrollback, sticky Ctrl-C, Fn page).
- 2026-07-26 — **v1.0 gate: vttest + esctest run live against the emulator** via
  `tools/conformance_vttest.py` + `ConformanceBridge` (real vttest/esctest under a
  pty; the emulator answers DA/DSR/CPR in the loop; every screen snapshotted and
  reviewed by hand).

  **vttest** (tests 1, 2, 3, 4, 6, 8 + submenus): cursor movements (E-frame,
  autowrap letter ladder, ESC-embedded controls, leading zeros), screen features
  (WRAP, tab set/reset, 80-col light/dark, soft/jump scroll in narrow + full
  regions), origin mode, SGR pattern, save/restore cursor with DEC graphics,
  charset screens (B + DEC special complete in G0/G1), VT102 accordion
  (IL/DL/ICH/DCH/IRM: "A's, X's, nothing more", 'A***B', 'AB', staggered column)
  and terminal reports (DSR 5/6, DA1, DA2, DECREQTPARM "-- OK") all render/report
  correctly.

  **esctest** (`--expected-terminal xterm --max-vt-level 2`): **113 passed,
  354 xterm-known-bugs, 82 failed — every failure an accounted-for policy**:
  XtermWinops (28: no window moving/resizing/title-stack on a phone),
  Change/Reset dynamic colors (40: XParseColor rgbi:/TekHVC/CIELab setters out of
  scope; rgb: get/set works), DECCOLM-dependent (RIS/DECSET 3: 80/132 switching
  deliberately ignored — phone width is physical, same as stock xterm with c132
  off / tmux / Termux), DA/DA2 exact-string (4: we answer honestly as VT220-class
  w/o printer/locator; esctest wants xterm's exact IDs), S8C1T (1: UTF-8-first,
  no 8-bit C1), and RI/NEL/IND/HTS_8bit (4: inverted — esctest expected xterm to
  FAIL these with wide chars enabled; we handle 8-bit C1 input fine and
  "unexpectedly succeed").

  **Fixed during the pass** (all with new JVM tests): DECSTR now resets the DECSC
  saved-cursor state and reverse-wrap mode; DECREQTPARM answered; DECID (ESC Z)
  answered; DSR ?15/?25/?26 answered; **reverse wraparound (DECSET 45)
  implemented** — BS annuls a pending wrap, climbs rows region-confined (top
  wraps to region bottom), CUB walks back across soft wraps. Known limits
  (accepted): NRC/Latin-1-in-GR render as U+FFFD (UTF-8-only, like kitty/
  Alacritty), DECDHL/DECDWL degrade to single-size lines (like tmux), LNM
  keyboard side (Enter→CRLF) not wired.

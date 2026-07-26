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
- [ ] DECRQSS (minimal), XTVERSION
- [ ] Hardware keyboard (Ctrl/Alt combos, Ctrl+Shift+C/V)

The **input/interaction** surface (accessory row, gestures, selection, mouse, IME) is
documented end-to-end in `docs/INPUT.md`.

## Explicit non-goals (until someone asks)

Scrollback reflow (v1.x backlog — data model is ready via `isWrapped`), sixel, OSC 8
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

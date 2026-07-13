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

- [ ] C0 controls: BEL BS HT LF VT FF CR SO SI
- [ ] CSI cursor: CUU CUD CUF CUB CNL CPL CHA CUP HVP VPA
- [ ] CSI edit: ED EL ECH ICH DCH IL DL SU SD
- [ ] SGR: 0-9, 21-29, 30-37/39, 40-47/49, 90-97, 100-107, 38;5 48;5, 38;2 48;2 (truecolor)
- [ ] DECSTBM scroll regions + origin mode (DECOM)
- [ ] Autowrap **with deferred-wrap (pending-wrap) semantics** — explicit tests
- [ ] Tab stops: HT HTS TBC (BitSet)
- [ ] Modes: DECCKM DECTCEM DECAWM IRM
- [ ] Alternate screen: 47 / 1047 / 1048 / 1049 (with cursor save/restore)
- [ ] DECSC/DECRC, RI IND NEL, RIS + DECSTR
- [ ] Charsets: G0/G1 designation, DEC Special Graphics (vim/tmux line-drawing)
- [ ] Responses: DA1 (VT220-class), DA2, DSR 5, DSR 6/CPR (origin-aware), OSC 10/11
      color queries, CSI 14t/18t size reports
- [ ] wcwidth: generated UCD table; combining = 0; wide CJK = 2; VS16 → 2; ambiguous-width setting

## v0.4 additions

- [ ] Mouse reporting: modes 9/1000/1002/1003, SGR 1006 encoding (1015 fallback, skip 1005)
- [ ] Bracketed paste (2004), focus events (1004)
- [ ] OSC 0/1/2 title; OSC 52 clipboard (behind per-host consent); OSC 4/104 palette
- [ ] DECRQSS (minimal), XTVERSION

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

(append dated results here as milestones pass)

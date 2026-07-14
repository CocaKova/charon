# INPUT.md — the touch, keys & gestures model

How Charon turns a phone into a real terminal. This is the moat as much as the
emulator is: Termux gives you a bare grid and stock buttons; Termius buries the
grid under chrome. Charon makes the **whole surface** expressive — one thin
accessory row, and gestures that mean the right thing depending on what the
remote app is doing — while staying in theme (Styx teal, obol gold).

Pure encoding lives in `terminal-core` (`input/KeyEncoder`, `input/MouseEncoder`,
`TextSelection`) so it's JVM-tested on the Spark; the Android layer
(`presentation/terminal/*`) maps touch and IME onto it.

---

## 1. Text entry — two IME modes

The `abc` / `raw` key at the right of the accessory row toggles how the soft
keyboard talks to the grid. State persists (prefs `charon` / `input_mode`).

- **predictive (`abc`)** — the input view advertises a real multiline text field,
  so Gboard/Samsung light up glide-typing, suggestions and voice. Composing text
  is translated to terminal bytes as common-prefix diffs (backspaces to retract +
  the new tail); the editable is cleared on each commit. This is what makes
  swipe-typing a shell prompt possible — no other terminal does it this way.
- **raw** — `inputType = TYPE_NULL`: real key events, the classic terminal path.
  Use it in full-screen TUIs where a composing echo would fire hotkeys. Gold when
  active — you've stepped off the charted water.

Never log typed content: it includes remote passwords. Debug logs carry event
names only.

---

## 2. The accessory row — the flagship

`presentation/terminal/AccessoryRow.kt`. Horizontally scrollable, JetBrains Mono
labels, DepthSlate pills, a sink-and-spring press + teal ripple. Every pill honours
a uniform min-width so single-glyph keys (arrows, symbols) square up into the same
grid rhythm as the word keys — the row reads as one designed keyboard, not a ragged
scroll. Logical clusters are set apart by **whitespace** (a wider breath, `GroupGap`)
rather than a hairline rule. Layout:

```
 esc  tab  ctrl  alt    ↑  ↓  ←  →   -  /  |  ~   home end pgup pgdn    paste  fn  abc
                                       └── swaps to F1…F12 when fn is on ──┘
```

### Sticky modifiers (Ctrl, Alt)

Three states, applied to whatever you send next (`TerminalScreen.emit`):

| State  | How you get there        | Colour     | Meaning                         |
|--------|--------------------------|------------|---------------------------------|
| off    | default; tap when on     | surface    | inactive                        |
| armed  | **tap**                  | StyxTeal   | charged for **one** keystroke   |
| locked | **long-press**           | ObolGold   | latched until tapped off        |

- **Ctrl** folds a single character to its control code (`c` → `Ctrl-C`), via
  `KeyEncoder.ctrl`. It doesn't apply to multi-char input or special keys.
- **Alt** (Meta) prefixes `ESC` to whatever goes out (`KeyEncoder.alt`), including
  arrows/F-keys — xterm `metaSendsEscape`.
- **armed** clears itself after one send; **locked** persists (so `Ctrl` locked +
  typing sends a stream of control codes — handy for readline, careful with
  `Ctrl-S` XOFF).

### Long-press variants

Long-press reaches the shifted mate the soft keyboard buries:

| Key   | tap         | long-press          |
|-------|-------------|---------------------|
| tab   | `\t`        | back-tab `ESC[Z` (CBT) |
| `-`   | `-`         | `_`                 |
| `/`   | `/`         | `\`                 |
| `\|`  | `\|`        | `` ` ``             |
| `~`   | `~`         | `^`                 |
| ctrl  | arm/off     | lock/unlock         |
| alt   | arm/off     | lock/unlock         |

### Auto-repeat

`↑ ↓ ← →` and `pgup`/`pgdn` repeat while held — fire on down, then ramp from a
400 ms delay to 60 ms (driven by a `LaunchedEffect` keyed on a pressed flag the
gesture toggles; `waitForUpOrCancellation` is a restricted-suspend call, so the
timing has to live outside it).

### Fn page

`fn` (gold when on) swaps the row's middle for `F1…F12`. `esc/tab/ctrl/alt` and
`paste/fn/abc` stay put so modifiers still compose with F-keys.

---

## 3. Gestures on the grid

`TerminalView` routes a single finger by mode. `session.mouseActive` is true when
the remote app has requested mouse tracking (DECSET 9/1000/1002/1003).

| Gesture              | mouse **off**                    | mouse **on** (htop/vim/tmux)      |
|----------------------|----------------------------------|-----------------------------------|
| **tap**              | clear selection, else focus + IME| clear selection, else mouse click |
| **long-press**       | select the word under the finger | *(same — local select always works so you can copy out of a mouse app)* |
| **drag** (plain)     | scroll our scrollback            | send wheel notches                |
| **drag** (after long-press) | extend the selection      | extend the selection              |
| **pinch**            | zoom the font (8–32 sp, persisted) | zoom the font                   |

One "notch" = one cell-height of travel. Fast flicks barely move (the OS eats
them as a fling); a slower drag scrolls smoothly.

---

## 4. Selection, copy & paste

- **Long-press** selects the word (`TextSelection.wordAt` keeps paths/URLs/idents
  whole); **drag** extends it. The selection is washed in translucent StyxTeal.
- A **copy** pill (teal, top-right) appears while a selection holds → Android
  clipboard, then clears. Copy is scroll-aware: it reads the viewport you're
  looking at (`TextSelection.extract` with the scroll offset), soft-wrapped rows
  joined without a newline, per-row trailing spaces trimmed.
- **paste** key → `KeyEncoder.paste`, bracketed-guarded (`ESC[200~…ESC[201~`)
  when the app enabled DECSET 2004, CR line endings either way.

---

## 5. Scrollback & wheel

`ScreenBuffer.viewLine(scrollOffset, row)` is the single mapping into the
scrollback+live virtual space; renderer, selection and copy all read it so they
agree on what a visible row means.

- Drag (mouse off) walks `TerminalSession.scrollOffset` back through history. New
  output while you're scrolled up grows the offset so the view stays put instead
  of yanking.
- While scrolled the cursor hides and a gold **▼ live** pill shows; tap it — or
  type anything — to snap to the bottom. Resize also snaps.
- Drag (mouse on) sends wheel buttons 64/65 instead, so htop/less scroll their
  own way. Scrollback-in-history selection works (long-press while scrolled).

---

## 6. Mouse reporting

`MouseEncoder` turns a pointer event + the active mode into bytes:

- **SGR (1006)** preferred: `ESC[<Cb;Cx;Cy M` (press/motion) / `…m` (release),
  1-based, unbounded coordinates.
- **legacy** fallback: `ESC[M` + three bytes offset by 32.
- Mode-aware: returns nothing when the mode doesn't report the event (release
  under X10 mode 9, motion under plain 1000, a wheel "release"). Modifier +
  motion bits are handled; wheel is buttons 64/65.

Touch mapping: tap → left press+release at the hit cell; drag → wheel notches (see
§3); a real hardware mouse path (hover/right-click) lands with keyboard support.

---

## Testing

- terminal-core JVM suite on the Spark: `KeyEncoderTest` (incl. back-tab),
  `MouseEncoderTest` (SGR + legacy + mode gating), `TextSelectionTest` (word
  boundaries, wrapped joins, scrollback extract).
- On-device (adb over Tailscale): verified word-select + copy + bracketed paste,
  drag-scroll of scrollback + `▼ live`, htop wheel-scroll, htop tap-to-click,
  sticky Ctrl arm/lock + `Ctrl-C`, the Fn page. Pinch-zoom and the biometric key
  flows need real fingers.

See also `TERMINAL.md` (emulator conformance) and `DESIGN.md` (visual identity).

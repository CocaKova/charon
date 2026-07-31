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
  the new tail). The editable mirrors the current line (reset on Enter) and
  selection moves are reported (`updateSelection`), so the IME tracks the field
  instead of guessing — an IME that can't see its own committed text re-commits
  it, which is how first words used to double. When the IME re-opens a finished
  word (`setComposingRegion`, the autocorrect/suggestion-tap path) the diff is
  re-anchored onto that word; an edit the end-of-line wire cursor can't reach is
  mirrored in the editable only and the connection restarted — never re-typed.
  Text that lands outside the IME (autofill chips, snippets, paste, accessory
  keys) restarts the connection so a stale composition can't commit on top of it.
  This is what makes swipe-typing a shell prompt possible — no other terminal
  does it this way.

  **The editor half of the contract.** `BaseInputConnection` alone is not a real
  editor, and the ways it falls short all show up as the *same* two symptoms: a
  word that lands twice, or a suggestion tap that does nothing at all. All four
  gaps are now filled, and the reasoning is worth keeping because each one is
  silent — the IME simply stops offering, and nothing is logged anywhere:

  | Gap | What the IME concludes | Fix |
  |---|---|---|
  | `updateSelection` never called | "I don't know where the cursor is" | reported on every edit, deferred to batch-edit end |
  | `beginBatchEdit()` returns `false` | "composite edits are refused here" | accepted, depth-counted |
  | `getExtractedText()` returns `null` | "this field has no text to replace" | the mirror, handed over whole |
  | `initialSelStart/End` left at `-1` | "cursor position unknown" — before a single keystroke | seeded to 0 on a fresh connection |
  | `commitCorrection()` returns `false` | "this field can't be corrected" | accepted (the text already arrived via `commitText`) |

  The mapping itself — what is on the wire versus what the IME thinks is in the
  field — lives in `PredictiveWire`, which has no Android in it and is unit-tested
  against real Gboard call sequences (`PredictiveWireTest`). The connection is a
  thin adapter over it. **If you touch this, add the sequence to that test first:**
  this bug has been fixed twice, and both times the hole was a call order nobody
  had written down.
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

`↑ ↓ ← →` and `pgup`/`pgdn` repeat while held: a tap fires on the up-stroke, a
still hold starts repeating after 400 ms at 60 ms per step (a `LaunchedEffect`
keyed on a pressed flag the gesture toggles). The gesture is **swipe-proof
without being tap-hostile** — drift *within* the key never cancels (thumbs roll
15–25 px on fast taps; a keyboard that drops rolled taps feels broken). What
cancels is structural, watched in the `Final` pass: the pointer leaving the key
bounds (+12 dp margin) — a glide crossing the row, or the key sliding out from
beneath a stationary finger — or the row genuinely taking the pointer for a
scroll, measured as accrued *consumed displacement* past touch slop (merely
stopping a fling consumes an honest tap's micro-moves, and must not eat it).

### Fn page

`fn` (gold when on) swaps the row's middle for `F1…F12`. `esc/tab/ctrl/alt` and
`paste/fn/abc` stay put so modifiers still compose with F-keys.

---

## 2b. Smart autofill — the suggestion strip

`CommandSuggestions` in `TerminalScreen.kt`, driven by the `autocomplete/` engine
(`Completer` + `Specs` + `RemoteContext`) and `CommandHistory`. A slim scrollable
strip **above** the accessory row offering inline completions as you type —
Termius-class autocomplete, in theme.

Three blended sources, ranked in this order:

1. **Your history** (`CommandHistory`, prefs `charon_history`, cap 300, deduped) —
   full past lines that continue the draft; the most personal signal, first.
   History only learns **command lines** (`CommandGate`): a line's first token must
   be an executable the host actually has (the PATH inventory), a shell
   keyword/builtin, a path invocation, a variable, or an env assignment — an
   unknown token (alias, function, inventory not landed) passes only when the line
   is *shaped* like an invocation (short, or carrying option/path/operator
   characters). A qualifying first token is necessary but **not sufficient**:
   English is full of words that are also programs (`make`, `test`, `find`,
   `read`, `do`, `if`, `let`, `time`, `who`, `install`), so the whole line is read
   too. Shell evidence anywhere in it — an option, a path, an operator, an
   assignment — settles it as a command. With none, a bare-word line is prose if it
   carries the closed-class English function words a sentence can't be built
   without, a contraction, or sentence punctuation (or simply runs past eight bare
   words). Shell compound skeletons (`for f in …`) are exempt, since their bare
   `in` is grammar, not English. Sentences typed into a chat or REPL running on the
   host never enter the history, and the same gate screens what's already stored
   before it may suggest (so lines recorded before the gate existed stay silent —
   no history clearing needed after a gate fix). The gate
   is deliberately **not** tied to the alternate screen: tmux runs its shells
   there, and per-host tmux auto-attach is the default workflow.
2. **Command grammar** (`Specs`) — curated specs for the tools a homelab hand
   actually types (tmux, git, docker, systemctl, journalctl, apt, ssh…):
   subcommands, common flags, and *which* flag/positional takes which dynamic value.
3. **The live host** (`RemoteContext`) — probed over a **silent exec channel** on the
   session's own transport (`SshConnection.exec`, never through the PTY): every
   executable on PATH (`compgen -c`, `ls $bindirs` fallback; refreshed each
   crossing), plus on-demand values with a 15 s TTL — running tmux sessions,
   `docker ps` names, systemd units. So `tm` offers `tmux` because the host *has*
   it, and `tmux attach -t ` offers the sessions running *right now*. Probes are
   best-effort with timeouts; a missing tool = an empty list, never an error.
   A **failed** probe caches nothing (the next request retries) — only a real
   answer counts. Dynamic kinds are **closed-world** (tmux sessions, containers,
   units — the probe IS the universe) or **open-world** (ssh targets from the
   remote's own `~/.ssh/config` — a helpful subset). Once a closed-world kind
   has answered, the live host is the authority: in that value position history
   recall is suppressed entirely, so a dead session name from last week can
   never outrank — or even sit beside — the sessions that exist right now.
   Open-world kinds keep history beside them. The exec read itself is
   deadline-polled (never block-to-EOF; the old read made the join timeout dead
   code), so a wedged probe can't strand its kind for the whole session.
4. **Remote paths** — any token starting `/` or `~/` completes from a live
   `ls -1Ap` of its directory (dotfiles included, 15 s TTL, last 8 directories
   cached), in any argument position, for any command, spec'd or not.
   Directories cascade (`etc/` keeps completing into the next level); files
   close the token with a space. Absolute and `~/` only — the two shapes
   knowable without tracking the shell's cwd; relative paths await OSC 7 shell
   integration (FRONTIER). `ssh`/`scp`/`sftp`/`rsync` also complete
   `user@host` — the user half is yours, the host half matches the remote's
   ssh book.

The flow: `tm` → `tmux` → (space cascades) `attach` `new` `ls`… → `-t` → your
actual session names. Completion follows the shell's own structure: `sudo`,
`doas`, and env assignments are transparent prefixes, and only the segment after
the last `&&`/`||`/`|`/`;` completes as a fresh command (`ssh spark && tm` →
`tmux`). It's built to run on every keystroke: the inventory keeps a pre-built
set for membership and a sorted list for binary-searched prefix runs, and the
history is gate-screened once per change, not per key.

- **How it knows the line** — `TerminalSession.trackInput` reconstructs the command
  being typed from the *outgoing* bytes (fed only from genuine user input: `emit`
  and `paste`, never DA/DSR/mouse replies). Printables append; `^?`/`^H` pop; `^C`
  `^U` reset; `^W` deletes a word; `\r`/`\n` commits the line to history. The moment
  editing goes non-linear — any arrow/edit escape or a `\t` completion — the tracker
  stops trusting its reconstruction and blanks the draft, so it never suggests
  against a wrong prefix. Crossing into or out of the **alternate screen** (tmux,
  vim, htop) resets the tracker too — the line belonged to a different world, so
  stale text can't keep feeding suggestions after you step back out.
- **The offer** — up to six chips: a gold `»`, the part you typed dimmed, the
  completion glowing teal.
- **Accepting** — a tap types *only the missing tail* (token completions add the
  trailing space, which immediately surfaces the next word's suggestions). No
  injection of whole lines; the remote echoes everything in place.
- **Privacy** — passwords never land here: the **toll** (§2c) intercepts hidden
  input before it can touch the draft or the history. Probes send fixed read-only
  commands; nothing typed is ever executed by the engine.

## 2c. The toll — hidden input, and the lading

When the remote reads a secret — `sudo`, `ssh`, `su`, `read -s` — nothing typed may
touch the autofill draft, the command history, or the IME's dictionary. Charon
recognizes the moment two ways, both structural (`TerminalSession`):

1. **The prompt grammar** — the cursor resting at the end of a line that ends in
   `:` and asks for a `password`/`passphrase` (the PAM/OpenSSH prompt convention)
   arms the toll *before the first keystroke*.
2. **The echo net** — a printable keystroke the remote never answers (700 ms with
   zero output; a normal line echoes within the RTT) means it is reading in secret.
   This catches `read -s` and prompts in any language: the session forgets whatever
   it had reconstructed and raises the toll itself (`markHiddenInput`, timer in
   `TerminalScreen`). Any remote output cancels the pending probe (`echoPending`).

While the toll stands:

- **Nothing typed reaches `trackInput`'s line buffer** — keystrokes feed only
  `tollPulse`, an animation counter (backspace drains it; it is never shown as a
  length). Enter flips the toll to PAID and commits nothing.
- **The IME becomes a password editor** (`TerminalInputView.secure`,
  `TYPE_TEXT_VARIATION_PASSWORD | NO_SUGGESTIONS` in PREDICTIVE mode): no
  suggestion bar, no glide trail, no dictionary learning. RAW mode already exposes
  nothing.
- **The toll pill** (top-center, gold ring): "the ferryman asks the toll", an obol
  that flips once per hidden keystroke, "the toll is paid" + Confirm haptic on
  Enter. A failed attempt (sudo re-prompt) tears it down and re-arms it.
- Suggestion strip and snippet bar stay down.

The toll releases when the prompt line moves on (cursor row change, scroll, or the
line's text changing under it), on `^C`, on alt-screen flips, and on session switch.

**The lading** (`cargo/CargoLading.kt`): a submitted command that invokes a package
manager (`apt`/`apt-get`/`dnf`/`pacman`/`dpkg`/`pip`/`npm`/`cargo`/`brew`/`snap`/
`flatpak`…, `sudo`/env/path transparent) arms a cargo watch on the session. While
the manager's documented output grammar is visible near the cursor (per-package
verb lines — `Unpacking…`, `Setting up…`, `Collecting…` — gleaned every 200 ms from
the bottom rows), a strip rides the live edge: a gold laden barge crossing braille
water, steered by the freshest on-screen percent when there is one and patrolling
when there isn't, the package under hand named beneath, "cargo ashore" at 100%.
The strip fades ~4 s after cargo stops moving; the watch itself ends on `^C`,
alt-screen, a new cargo command, or a minute of total silence. While the toll is
up (sudo's password ahead of the install), the strip yields.

## 3. Gestures on the grid

`TerminalView` routes a single finger by mode. `session.mouseActive` is true when
the remote app has requested mouse tracking (DECSET 9/1000/1002/1003).

| Gesture              | mouse **off**                    | mouse **on** (htop/vim/tmux)      |
|----------------------|----------------------------------|-----------------------------------|
| **tap**              | clear selection, else focus + IME| clear selection, else mouse click |
| **long-press**       | select the word under the finger | *(same — local select always works so you can copy out of a mouse app)* |
| **drag** (clear water) | scroll our scrollback          | send wheel notches                |
| **drag** (starting on the selection, ±1 row) | extend the selection | extend the selection |
| **pinch**            | zoom the font (8–32 sp, persisted) | zoom the font                   |

A selection no longer hijacks every drag: grab the selection (or the row beside
it) to grow it, grab anywhere else to scroll — the selection survives the scroll.
Holding a select-drag at the glass's top or bottom edge crawls the viewport a row
at a time, so one gesture can walk a selection deep into scrollback.

One "notch" = one cell-height of travel. Fast flicks barely move (the OS eats
them as a fling); a slower drag scrolls smoothly.

---

## 4. Selection, copy & paste

- **Long-press** selects the word (`TextSelection.wordAt` keeps paths/URLs/idents
  whole); **drag from it** extends it. The selection is washed in translucent
  StyxTeal.
- Selections live in **buffer space** (row 0 = top of the live grid, negative
  rows reach into scrollback — `ScreenBuffer.relativeLine`), so a selection stays
  glued to its text while you scroll, slides back naturally as new output evicts
  lines into history, and one selection can span scrollback and the live screen.
  Entering/leaving the alternate screen clears it (different world).
- **copy** and **all** pills (top-right) appear while a selection holds: **all**
  swells the selection to the entire scrollback + screen, **copy** → Android
  clipboard, then clears. Extraction joins soft-wrapped rows without a newline
  and trims per-row trailing spaces.
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

# Charon — the Frontier Track

*How Charon goes from "a better Termius" to "a terminal that matches or beats Ghostty,
WezTerm, and Kitty — on a phone."*

This is a **post-v1.0 track** that layers on the roadmap in `PLAN.md`. Nothing here blocks
the v0.8 fleet → v0.9 vault → v1.0 public spine. It answers a different question: once the
SSH client is done, what makes the **terminal itself** frontier-class — and where does being
*mobile* let us do things no desktop terminal can?

Two rules carry over from everything else:

1. **In theme, always.** Every feature below has a Styx name. Charon ferries the dead across
   the river; images are the shades made visible, prompts are mooring posts, links are marked
   passages. The braille sea, StyxTeal `#3ECFB2`, and ObolGold `#D9A441` are the whole visual
   language — a frontier feature that doesn't feel like Charon isn't shipped.
2. **The terminal is the moat, so the terminal is free.** Everything in this doc ships in the
   GitHub/FOSS build. The Obol (`[[charon-premium-tier]]`) only ever sells *liveries* —
   cosmetics. We do not paywall a graphics protocol. That's the whole pitch.

The competitive read: Ghostty/WezTerm/Kitty compete on GPU speed, inline images, the Kitty
keyboard protocol, shell integration, and built-in multiplexing. Charon can **match** all of
that. Charon can **surpass** them on the one axis they can't touch — it's the terminal in your
pocket, so "your build finished" can be a buzz on your wrist and an image can be a tap-to-save.

---

## Tier 1 — Apparitions (inline image rendering) ★ the marquee

*The shades made visible on the black water.* Images rendered in the terminal grid — the
feature Jonny asked for by name, and the single biggest "this is a real terminal" signal.

### What the frontier does
Three wire protocols exist. They are not competitors so much as eras:

| Protocol | Wire form | Who emits it | Verdict |
|---|---|---|---|
| **iTerm2 inline images** | `OSC 1337 ; File=…:<base64>` | `imgcat`, most "show me a picture" scripts | Simplest. Fastest path to a live demo. |
| **Kitty graphics protocol** | `APC _G <key=val>,<base64> ST` | `kitten icat`, `timg`, modern TUIs, image viewers, `mpv` | The frontier standard. Placements, z-index, Unicode placeholders (reflow-safe), animation. Most work, most future. |
| **Sixel** | `DCS q … ST` | `img2sixel`, `lsix`, `gnuplot`, `mpv -vo sixel`, `neofetch`, old-school tools | Legacy bitmap. Widest *classic* tool support, least modern. |

### The foundation is already built
- `Parser.kt` already has `DCS_PASSTHROUGH` (→ Sixel) and `SOS_PM_APC_STRING` (→ Kitty). The
  APC path currently lumps SOS/PM/APC together and discards. **Split APC out**, route `_G`
  payloads to a Kitty handler; everything else keeps discarding.
- `oscDispatch()` already fires — add an `OSC 1337` branch for iTerm2.
- Rendering: images live in a new **placement layer** in `ScreenBuffer` — each Apparition owns
  a rectangle of cells (anchored to a scrollback row so it **scrolls with the text** and evicts
  when its anchor rolls off). The Compose renderer draws decoded bitmaps into those cell rects
  *under* the text pass. `Line.isWrapped` and the ring buffer already give us the anchoring math.
- Decode = platform `BitmapFactory` (PNG/JPEG free) + a small owned RGB/RGBA path for raw Kitty
  frames. No NDK, no new heavy dep. Sixel needs a ~200-line owned band decoder.

### The mobile surpass (no desktop terminal has these)
- **Tap an Apparition → the lightbox.** Fullscreen, pinch-zoom (the gesture already exists),
  and a gold **⇣ carry ashore** to save to the gallery / **share** out the Android share sheet.
  On desktop an inline image is a dead pixel rectangle; here it's a first-class photo.
- **Send a shade across the river** — camera/gallery → terminal. Pick a photo, Charon renders
  it inline locally *and* can `scp` it up into the hold. The phone's camera becomes a terminal
  input device.
- Apparitions survive scrollback and honor the theme frame (a hairline StyxTeal border while
  loading, a soft dissolve-in — shades *appear*).

### Themed surface
Loading = "**a shade rises from the water**" (braille shimmer over the rect). Saved to gallery
= the same "carry ashore" language as SFTP (`[[project_charon_ssh_client]]` the hold). Decode
failure = "the shade would not hold its shape."

**Build order (decided with Jonny — frontier-first):** **Kitty graphics protocol → iTerm2 →
Sixel.** We plant the flag on the standard Ghostty/WezTerm are judged on (`kitten icat`, `timg`,
placements, reflow-safe Unicode placeholders) first, pick up iTerm2 `imgcat` compatibility
second for near-free, and add the Sixel legacy long tail last. All three eventually ship.

---

## Tier 2 — the modern TUI contract

*Making Charon feel current to anyone who lives in neovim, helix, lazygit, or fzf.* These are
the invisible protocols that separate a 2015 terminal from a 2026 one.

- **The Ferryman hears every word — the Kitty keyboard protocol** (`CSI u`, progressive
  enhancement). Full modifier + key disambiguation. Helix, neovim, kakoune *require* this to
  tell `Ctrl+I` from `Tab`, report `Ctrl+Shift+key`, and handle key-release. Encoder work in
  `KeyEncoder` + a mode flag; the biggest single "modern TUI works right" unlock.
- **The deep colors — undercurl & colored underlines** (`SGR 4:3` curly / `4:4` dotted /
  `4:5` dashed, `SGR 58/59` underline color). This is how nvim draws LSP squiggles. Pure
  renderer work — draw a sine underline in the cell's underline slot.
- **Marked passages — OSC 8 hyperlinks.** Real semantic links (not regex-guessed). Tap to open
  the Android browser; long-press to copy or share. Pairs with the touch hint mode in Tier 3.
- **Soundings — shell integration (OSC 133 semantic prompts + OSC 7 cwd).** The terminal learns
  where prompts start/end and whether the last command succeeded. This unlocks a *lot*:
  prompt-to-prompt jump, a red/green mooring-post glyph in the gutter per command, cwd-aware
  autocomplete (feeds the existing `autocomplete/` engine), and — the big one — **the horn**
  (Tier 4). Requires shipping shell-integration snippets users source on the host (Charon can
  offer to install them over the exec channel, same courier pattern as `ssh-copy-id`).
- **XTVERSION / kitty query / DECRQSS** so remote programs *detect* Charon and light up their
  fancy paths. Without this, `kitten icat` won't even try to send an image.

---

## Tier 3 — the river's memory & reach

*Navigation and productivity across the scrollback.*

- **Dredging the wake — search in scrollback.** Regex, live highlight, jump between hits. On a
  phone this is huge — you can't just eyeball 10k lines. A themed search bar (teal matches,
  gold current hit).
- **The catch — hint / quick-select mode.** Kitty's `hints` and tmux-fingers: overlay labels
  on every URL / path / IP / git-hash / port on screen, grab one with a keystroke. **Mobile
  surpass:** skip the labels — you just *tap* the thing. One-tap "open URL / copy path / SSH to
  that host / open that file in the hold." Extends the OSC 8 work and the existing URL sense.
- **The river remembers its banks — reflow on resize.** The data model is already reflow-ready
  (`Line.isWrapped` from day one). A phone *rotates* — this is a bigger correctness win here
  than on any desktop. Rewrap scrollback on width change instead of hard-truncating.
- **One voice to the fleet — broadcast input.** Type once, send to N sessions. Homelab
  superpower (`apt upgrade` across the whole tailnet). Reuses the v0.5 multi-session model.

---

## Tier 4 — the mobile surpass (features no desktop terminal can ship)

*This is where Charon stops matching and starts winning.* Every one of these is impossible or
pointless on a desktop and native on a phone.

- **★ The horn across the water — command-done push notifications.** Built on OSC 133 (Tier 2):
  you start `make`, lock your phone, and get a **buzz + notification** — "▲ make · done · exit 0"
  or "✕ make · failed · exit 2" — when it finishes, with the elapsed time. Tap to jump straight
  back to that session at that mooring post. This uses the `BuiltinPushService`/specialUse FGS
  that's *already in the app* (`[[project_charon_ssh_client]]`). **No desktop terminal needs
  this. Every phone user wants it.** This is the single most valuable feature in this doc.
- **The barge reads the hold — OSC 9;4 progress → the Lading.** Remote progress
  (`apt`, `curl`, conmon, anything emitting OSC 9;4) *steers the cargo barge* we already built
  in `CargoLading`. The barge stops being a guess and becomes a true progress readout. Pure
  integration — the barge and its animation already exist.
- **Apparition lightbox + save/share** (see Tier 1) — the reason to want images on a phone.
- **Send a shade across** — camera/gallery → terminal/scp (see Tier 1).
- **Haptic soundings.** The toll and lading already speak in haptics
  (`[[project_charon_ssh_client]]`). Extend it: a soft Confirm buzz when a backgrounded command
  finishes; a distinct pattern for success vs failure. Your pocket tells you the build passed.

---

## Tier 5 — the flotilla (multiplexing) — the hard mobile question

Desktop terminals ship splits/panes/tabs. On a 6-inch screen, four panes is a toy.

**Decided with Jonny: the fleet at the oars — tmux control mode (`tmux -CC`).** iTerm2's best
trick: Charon speaks tmux control mode, so **tmux windows become native Charon tabs and panes
become native splits**, driven by the touch/gesture layer, surviving disconnects for free (tmux
already persists them). This *reuses the v0.5 session-tab UI*, needs no fragile in-app pane
geometry, and gives the homelab crowd exactly what they already run. It also composes perfectly
with the existing `startupCommand`/tmux-auto-attach mooring behavior.

Native in-app pane geometry (Charon owning drag-to-split itself) is explicitly *not* the path —
too much layout code for too little screen. tmux control mode is both the frontier move and the
pragmatic mobile one.

---

## Also in the frontier bucket (smaller, still expected)

- **Ligatures & font features** — opt-in (`calt`/`liga`); platform text already shapes, so this
  is mostly plumbing a font flag. Fira Code / Cascadia as Obol liveries.
- **Liveries — color-scheme packs** (Obol cosmetic). Import iTerm2/Ghostty/base16 schemes; the
  StyxCrossing scene is livery #1. This is the *only* frontier-adjacent thing that's paid, and
  only because it's pure cosmetics.
- **OSC 9 / 777 desktop notifications** (folds into the horn).
- **Bell polish** — visual bell as a StyxTeal ripple, per-host audible/haptic/silent.
- **True-color-aware theming, minimum-contrast** (Ghostty's readability floor).
- **vttest / esctest conformance** as the v1.0 gate already promises — the frontier features add
  Kitty-protocol and Sixel conformance suites on top.

---

## Sequencing (decided — "the order best fit to make this great")

The base spine in `PLAN.md` runs first: **v0.8 fleet → v0.9 vault → v1.0 public release.** A
tagged, public 1.0 is the momentum milestone and the terminal is already ahead of Termius
without a single frontier feature. The frontier track then opens as **v1.x**, in dependency-and-
leverage order — not marquee-first, but *foundation-first so each step makes the next cheaper*:

1. **The modern contract core** (Tier 2) — **OSC 133 soundings + the Kitty keyboard protocol +
   OSC 8 + undercurl.** Bedrock: moderate effort, makes modern TUIs (helix/nvim/lazygit)
   correct, and OSC 133 is the prerequisite for the horn. Everything downstream leans on this.
2. **★ The horn + progress + haptics** (Tier 4) — the highest value-per-effort item in the doc,
   now unblocked by OSC 133 and riding the FGS that already exists. Small lift, huge payoff. This
   is the mobile-surpass moment, and it lands *before* the big image lift so the "wins on mobile"
   story arrives early.
3. **★ Apparitions** (Tier 1) — the marquee, Kitty-first. The biggest single lift; standalone,
   so it can even run in parallel with (1)/(2). Lands as the headline visual version.
4. **The river's memory** (Tier 3) — search, touch hints (build on the OSC 8 work), reflow,
   broadcast.
5. **The flotilla** (Tier 5) — tmux control mode.

Two escape hatches Jonny keeps: **Apparitions or the horn can jump ahead of v1.0** as a pre-1.0
marquee at any time — both are self-contained enough. The default above optimizes for a clean
public 1.0 first, then a foundation-first frontier climb where the small high-leverage wins
(soundings, the horn) land before the big one (images).

*Nothing here is committed to code yet — this is the map, not the crossing.*

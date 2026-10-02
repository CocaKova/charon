# The Guide

How to sail Charon. Everything here is free; nothing below is ever paywalled.

Charon speaks in river terms — hosts are **moorings**, connecting is a **crossing**,
your key store is the **reliquary**. The guide uses both the themed name and the plain
one, so you always know what a screen means.

---

## 1. First crossing

1. Install the APK from [Releases](https://github.com/CocaKova/charon/releases) and open it.
2. On the Dock, tap **+ new crossing**. Give the mooring a label, address
   (`user@host`, port if not 22), and either a password or a key of passage
   (see §3 — you can attach one later).
3. Cross. On first contact you'll **meet the ferryman**: the host's key fingerprint,
   shown before anything is sent. Verify it against the server
   (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`) and accept. It's pinned from
   then on — if the host's key ever *changes*, Charon refuses with a full-screen
   warning and will not reconnect unless you explicitly replace the pin.
4. Type `exit` when you're done — the ferry returns to shore and the session closes
   cleanly. A dropped connection (as opposed to a clean exit) redials itself; see §9.

## 2. The Dock

The Dock is your fleet.

- **Moorings** are cards: label, address, a **lantern** you can color per host, and a
  reachability glow — a teal halo means the host answered a probe just now (with its
  latency), an ember tick means it didn't. Probes run every 25 s while the Dock is
  open, never in the background.
- **Harbors** group moorings. Set a harbor name on a host and the Dock grows
  collapsible sections; leave harbors empty and it stays a flat list. A search field
  appears once you have more than a few moorings.
- **Long-press a card** for quick actions: cross, step aboard a live session, open its
  files, copy the address, edit, or release the mooring (delete).
- **Chart the waters** (card under *+ new crossing*) imports hosts in bulk:
  - **tailnet** — reads `tailscale status` over an existing connection and offers
    every peer as a mooring.
  - **near waters** — sweeps your Wi-Fi /24 for machines answering on port 22.
  - **ssh config** — reads an OpenSSH `~/.ssh/config`, asked of a mooring
    (`cat ~/.ssh/config` over its crossing) or pasted. Each `Host` keeps its own
    `User`, `Port`, `ProxyJump` (found among the ships moored in the same go, or
    among moorings you already have) and `ForwardAgent`. Wildcard blocks are
    defaults, never ships; `Match`, `Include`, `ProxyCommand` and `IdentityFile`
    can't cross from a phone and are named in notes rather than guessed at.
  Pick the sightings you want, set the shared username/port/key, and they dock.
- **Hail a ferry** — type `user@host[:port]` (or paste an `ssh://` link) on the Dock
  to cross once without mooring anything. The ferryman still checks the host's key.
  When it comes home, one quiet line offers to moor it.
- **Ways in from outside:** an `ssh://user@host:port` link anywhere on the phone
  opens the hail sheet filled in (it never casts off on its own); a long press on
  Charon's launcher icon lists your latest moorings and crosses to one (or steps
  aboard its live crossing); the **Charon** quick-settings tile counts crossings
  under way and takes you to the one you last stood on.
- **A first crossing** plays on the Dock: the boat poles off during the handshake,
  holds mid-river while a host key or a server's question waits for you (**turn
  back** calls it off), and the terminal dissolves in on landing. With crossings
  still at sea the boat stays out on the water; it comes home when the last one ends.
- A trusted host's key draws a small **braille sigil** on its card (and on the trust
  sheet). A rekeyed host draws a different one.
- The river and the lanterns move only when there's news — a crossing, a sounding —
  and then hold still. A still Dock costs no battery.

## 3. Keys of passage

Open **the keys** (key icon on the Dock):

- **Forge** a new Ed25519 key directly on the phone. Keys are born in the app,
  private half sealed with the Android Keystore — it never exists as a plain file.
- **Import** an existing OpenSSH private key (paste). Encrypted keys keep their
  passphrase.
- **Biometric seal** (default for forged keys): using the key — even for a crossing —
  costs a fingerprint. The gate is enforced by the phone's keystore hardware, not by
  app code.
- **Grant passage** carries a key's public half to a host you can still reach by
  password (`ssh-copy-id`, done over a verified channel). After that, the mooring
  crosses passwordless.

Attach a key to a mooring in the host editor. Vault export includes keys — see §11.

**More ways across** (host editor):

- **Two-factor and PAM-only servers** (keyboard-interactive): the server's own
  questions come up as a sheet. A saved password answers the first password prompt
  once; a one-time code or anything else is yours to answer. Answers are never
  stored or logged.
- **Cross via** (ProxyJump): pick another mooring to hop through. Each shore checks
  its own host key and asks its own questions. Up to three hops; a loop stops.
- **Lend the key onward** (agent forwarding): the far side can ask this mooring's
  key for signatures (for `git` or a further `ssh`) and nothing else; every
  signature shows a gold **⚿ your key signed onward** pill. Off unless you turn it on,
  and only for a mooring with a key attached.

## 4. At sea — the terminal

The emulator is Charon's own: truecolor, mouse reporting, bracketed paste,
xterm-conformance-tested (see `TERMINAL.md`).

**Touch:**

| Gesture | In a normal shell | In a mouse app (htop, tmux w/ mouse) |
|---|---|---|
| tap | focus / raise keyboard | sends the click to the app |
| tap a link | opens the link sheet | sends the click to the app |
| long-press | select word (keeps paths whole) | select word |
| long-press a link | opens the link sheet | opens the link sheet |
| drag | scroll the scrollback | wheel-scroll the app |
| drag from a selection | extend the selection | — |
| pinch | zoom the glyphs (6–32 sp) | same |

- Selection lives in the scrollback, not just the visible screen — select, scroll,
  keep selecting; **copy** and **all** pills appear with a selection.
- Scrolled up, a gold **▼ live** pill takes you back to the bottom; any keystroke
  also snaps to live.
- **Dredging the wake** — the search bar over the water. Open it with **⌕** on the
  accessory row, or the teal **⌕** pill beside **▼ live** when you're scrolled back;
  the field takes the caret as it opens. Type and every sighting washes teal right
  in the grid, the one under your eye golds; ▲/▼ walk the sightings (the glass jumps
  to each), ✕ releases the dredge and returns to live.
  Case folds itself; matches run oldest-first from the deep scrollback up onto the
  live grid.
  Since 1.2 the dredge also takes **patterns** (the **.\*** pill turns regex on),
  is **smart-case** (a capital in the query asks for exact case), finds a match the
  grid wrapped across two rows, puts the wash on the right cells next to wide (CJK,
  emoji) characters, starts at the **newest** sighting, and keeps reading as output
  arrives while it's open, staying on the sighting you're looking at.
- A flick on the scrollback **coasts** and slows down like water; a touch stops it.
- When the width re-snaps (a pinch, a rotation) a **cols×rows** pill flashes: teal
  normally, gold when a full-screen program is up and the grid is under 80×24 (zoom
  out or go landscape), ember only when the glass is genuinely too small. The
  keyboard rising or falling doesn't flash it.
- **Soundings** (a rigged shell, see §10): a faint **✓ 2m14s** or **✕ exit 2** sits at
  the end of each finished prompt line, and when you're scrolled back **⇡ ⇣** hop
  from command to command.
- **A seam in the wake** — after a dropped crossing is re-made, a gold dashed rule
  marks the spot in the scrollback: *re-crossed 14:02 · 8s adrift*.
- **Re-crossing** keeps your screen readable under a light veil, counts down to the
  next try (**cross now** skips the wait), and says *network's back — crossing now*
  the moment the phone's network returns.
- **Daybreak** (the paper livery) holds every glyph to a minimum contrast, so a
  program's white text still reads on paper; selections and sightings wash in the
  livery's own colours.

**Marked passages — links.** When the far side marks text as a real link (OSC 8 —
`ls --hyperlink=auto`, `gcc`/`clang` diagnostics, `delta`, `systemctl`, `eza`, many
modern tools), it wears a quiet dotted underline in your livery's accent. Tap it and a
sheet rises — Charon never opens a link on a tap alone, because the far side chooses
both the words and the target:

- the words as they read in the grid, then **leads to** the real host, then the
  **full address, verbatim** (select it if you like). Read the host: text saying
  one thing and leading to another is the oldest trick there is.
- **open** — hands it to your browser or mail app. Only `http`, `https` and `mailto`
  links open; anything else (`file://`, `ssh://`, …) can only be copied.
- **copy** — the address to the clipboard.
- **select** — selects the words instead, as a long-press used to.

While the sheet is up, every piece of that link glows — even when it's split across
lines. Links stay tappable in your scrollback. Inside a mouse app (tmux with mouse
on, htop) a tap belongs to the app; **long-press** the link to reach its sheet.

**Plain URLs** printed as text (no OSC 8) are links too: tap one and the same sheet
rises. A long-press keeps a URL's `?`, `=`, `&`, `#` and `%` inside the selection.

- **localhost links carry themselves.** Tap `http://localhost:5173` in `npm run dev`
  output and the sheet offers **carry & open**: Charon opens a channel from the same
  port on the phone (any free port if that one's taken) to the far side's
  `localhost:5173` and opens it in your browser. The channel lives as long as the
  crossing and isn't saved.
- **`file://` links** (`ls --hyperlink`) offer **open the hold** at that path (a file
  opens its folder).

**The modern contract** — what neovim, tmux and friends expect, aboard since 1.2:

- the cursor takes the shape the program asks for (vim's insert-mode bar, replace
  underline) and morphs between them;
- the bell is a teal ring from the cursor with a light tick, never more than a few
  a second;
- a tmux or nvim yank (OSC 52) reaches the phone's clipboard only after you say yes,
  once per shore; the far side can never read your clipboard;
- focus in/out (1004) as you switch tabs or leave the app, synchronized frames (2026)
  so TUIs never show a half-drawn screen, DECRQM/DECRQSS/XTGETTCAP answers, and real
  drags in mouse apps;
- the keyboard rising never eats the prompt: lines leave over the top into
  scrollback, and come back when it falls.

**The accessory row** (above the keyboard):

- **Ctrl/Alt are sticky**: tap arms for one keystroke (teal), long-press locks (gold),
  tap again clears. Ctrl+C is: tap Ctrl, tap c.
- Long-press keys for variants (Tab→Shift-Tab, `-`→`_`, `|`→`\`, `~`→`` ` ``).
- Arrows and PgUp/PgDn auto-repeat when held.
- **Fn** swaps in F1–F12.
- **abc/raw** toggles the keyboard mode: *abc* keeps swipe/voice/predictions; *raw*
  sends pure key events — use raw inside TUIs that fight composing input. The
  default lives at the helm.

**Snippets:** with an empty command line, the strip shows your snippet chips
(❯) — tap to type one, long-press to edit. Snippets can be global or pinned to one
host. Manage them from the ⇅/⇆ area in the session switcher.

**Shades on the water — pictures in the terminal.** Charon draws inline images
right in the grid. Anything that speaks the **Kitty graphics protocol** or
**iTerm2's `imgcat`** works with no setup on your side:

```sh
kitten icat photo.jpg      # kitty's own viewer
timg screenshot.png        # timg, and most modern image tools
imgcat photo.jpg           # the iTerm2 script
```

Images scroll with the text and stay in your scrollback, exactly like the lines
around them.

Then the part no desktop terminal can do: **tap a picture**. It opens full screen —
pinch or double-tap to look closer, drag to move around, tap the dark to let it
sink back. Two pills at the bottom:

- **⇣ carry ashore** — save it wherever you like (your gallery folder, Drive,
  anywhere the file picker reaches). It's saved exactly as it arrived; a JPEG stays
  a JPEG.
- **send onward** — hand it straight to the Android share sheet.

If a picture doesn't appear, the usual cause is the far side deciding your terminal
can't show one. Charon answers `XTVERSION` and reports its cell size, so most tools
work it out; inside `tmux` you may need `set -g allow-passthrough on`.

## 5. The strip that thinks — autocomplete

Charon's suggestions come from the *host you're on*, not a canned dictionary:

- On every crossing it quietly inventories what's installed, and live-probes
  arguments as you type: `tmux attach -t ` offers the sessions *actually running*,
  `docker exec ` the containers, `systemctl` the units, `ssh ` the hosts in the
  remote's own `~/.ssh/config`.
- Paths complete anywhere: any argument starting `/` or `~/` lists the remote
  directory, cascading level by level.
- **Soundings** — rig your shell with the block in [HORN.md](HORN.md) (the same one
  the horn uses) and the prompt tells Charon where you're standing. Then bare names
  complete from *that* directory: `vim no` → `notes.txt`, `cd s` → `src/`,
  `cat src/ma` → `main.c`. Only where a file makes sense — never for the command
  itself, a flag, or something like `man ` or `tmux attach -t `. Dotfiles appear
  once you type the dot.
- **Branches**, same rig: in a repo, `git checkout ` / `git switch ` offer your
  branches (including ones that exist only on the remote, by the name `switch`
  would create), `git merge ` / `rebase ` / `log ` / `diff ` offer `origin/…` too,
  and `git branch -d ` offers only local branches.
- The soundings switch themselves off whenever they can't be sure: right after you
  press Enter until the next prompt reports in, after you `ssh` onward to another
  machine, inside a REPL. Then you get exactly the old behaviour — `/` and `~/`
  paths only. In tmux they need two lines in `~/.tmux.conf` (see HORN.md).
- Chained commands are understood — after `&&`, `|`, `;` it completes a fresh
  command.
- History recall: full lines you've run before, current-host lines first.

Two gates keep the strip honest:

- **The prose gate** — lines that read as English (messages typed into a chat or
  REPL over SSH) are never learned and never suggested. Commands are recognized
  by structure, not by a word list.
- **The secret gate** — lines whose *shape* declares a secret (`PASSWORD=`,
  `--token`, `Authorization: Bearer …`) are never learned. Values are not
  inspected; names and flags are.

Command history is encrypted at rest and host-tagged. Long-press a history chip to
make it forget that line; **the river's memory** at the helm shows the count and can
forget everything. Pasted lines are never learned.

## 6. The toll & the lading

- **The toll**: when a password prompt appears (sudo, ssh, `read -s`, anything that
  echoes nothing), Charon arms automatically — keystrokes bypass the suggestion
  strip, command history, and the keyboard's own learning; the IME flips to its
  password layout; a gold pill shows *the ferryman asks the toll* and flashes
  *the toll is paid* on Enter. Nothing about the secret is stored, suggested, or
  shown.
- **The lading**: package-manager runs (apt, pip, npm, cargo, docker pull, …) get a
  quiet progress barge at the live edge with the current package name and percent —
  *cargo ashore* when done.

## 7. The hold — files (SFTP)

From the session switcher, **⇅** opens the hold — at the shell's own directory when
it's rigged (OSC 7), else at home:

- Browse the remote tree (▸ dirs, ⇝ links). The strip under the bar sorts **by
  name / size / time** (folders first) and shows or hides **dotfiles**; both are
  remembered.
- **Long-press** chooses: tap more to add them, then **⇣ ashore** carries the files
  into one folder you pick, **release** deletes them all (after one confirm), and
  with just one chosen **⋯** opens its cargo sheet — carry it ashore, rename it, or
  release it. **⇡** carries local files aboard (several at once); **+dir** makes a
  directory.
- **Pictures** (png, jpg, gif, webp, heic…) open in place for a look, scaled to the
  glass; **⇣ ashore** saves one.
- **Editing a scroll**: a small text file (under 256K, clean UTF-8) has **edit** in
  its reader. **save** writes it back whole; back with unsaved edits asks once.
- **Reading a scroll**: tap a text file — `.md`, `.txt`, `.log`, `.conf`, config,
  source, a dotfile — and it opens *in place*, no download and no second app.
  Markdown is rendered (headings, lists, quotes, tables, code blocks, inline
  marks); **raw** shows it as written. Text is selectable, **⇣** carries it ashore
  after all, and back returns to the deck. Only the first 512K comes aboard; past
  that the reader says so. Anything else stays cargo: the sheet's **☰ read aboard**
  will try any file, and a binary says it's cargo rather than filling the screen.
- Transfers are resumable — a dropped link retries from the landed byte, four
  times, before giving up. The ledger strip shows progress; finished pulls offer
  *tap to open*.
- Transfers ride their own channel: a big pull never blocks browsing.

## 8. Channels — port forwards

From the session switcher, **⇆** charts channels:

- **L** (local): phone-port → host-reached target. To browse a dev server bound on
  the host: L `5174 → localhost:5173`, then open `127.0.0.1:5174` in the phone's
  browser.
- **R** (remote): a port on the host forwards back to something the phone can reach.
- **D** (dynamic): a SOCKS5 proxy on the phone, tunneled through the host — point
  apps at `127.0.0.1:<port>`.
- Mark a channel **auto** and it re-opens on every crossing, reconnects included.

## 9. Many crossings

- Each connection is a tab in the switcher; the Dock stays reachable (⌂) with
  crossings live. A notification shows how many are at sea.
- A tab's dot is a **latency lantern**: it breathes while its crossing is busy,
  slower and warmer (toward ember) as the time from keystroke to echo climbs, and
  holds still when nothing's happening. Long-press the tab to read it (*echo 42 ms*).
  A mooring with a lantern colour tints its tab dot and the waterline under the tabs
  — give your production hosts ember.
- A tab you aren't on flashes **gold** when a long command (5 s+) comes home, a
  program calls (OSC 9/777), and **ember** when one runs aground; it keeps that hue
  until you step aboard.
- Closing a live tab takes two taps: the first turns **×** into an ember *close?*.
- The **waterline** under the tabs reads the output rate: flat when nothing's
  arriving, a swell under a trickle, choppy in a flood.
- A tab is named by the remote's window title when it sets one (falling back to
  `user@host`). With `set -g set-titles on` in tmux — and, say,
  `set -g set-titles-string "#W"` — each tab wears its tmux window's name.
- **Clean exits** (`exit`, logout) just end. **Drops** redial with backoff, and the
  instant the network returns (airplane mode off, Wi-Fi back) Charon redials
  immediately.
- Set a mooring's **startup command** to `tmux new -As main` and every crossing —
  including automatic redials — lands you back in your tmux session exactly where
  you left off. This is the closest thing to mosh without mosh.
- Battery: keepalives relax to a slow beat while the app is backgrounded and step
  up when you return; the renderer sleeps between output bursts. Idle sessions cost
  very little — but OEM battery killers can still murder the background service; if
  sessions die overnight, exempt Charon from battery optimization.

## 10. The horn

Flip **the horn** at the helm and rig your shell: long-press the mooring on the Dock →
**rig the horn**. Charon asks the shore which shell it logs in with (bash, zsh, fish),
shows you the exact lines and the exact rc file, and writes nothing until you tap
**rig it** (the block sits between `# >>> charon rig >>>` markers; [HORN.md](HORN.md)
has it for doing by hand). The same rig powers the soundings in §4–§5.

- Any command that runs ≥ 15 s while you're in another app blows the horn — a
  notification saying it finished (or *ran aground* with its exit code). Tapping it
  opens **that tab, scrolled to that command**.
- You feel it too: **two soft ticks** for ashore, **one long low buzz** for aground
  (in the app on another tab you get the buzz and the tab flash, no notification).
- Programs can call you themselves: `printf '\e]9;build done\a'` (OSC 9) or OSC 777
  `notify;title;body` post a notification while you're away — at most one every ten
  seconds per crossing.
- A program's own progress bar (OSC 9;4) steers the cargo barge.

Command lines never appear on the lock screen.

## 11. The reliquary — vault export/import

**The reliquary** at the helm seals your whole fleet — moorings, keys, pinned host
fingerprints, snippets, channels — into a single passphrase-locked `.charon` file
(Argon2id + AES-256-GCM; format documented in [VAULT_FORMAT.md](VAULT_FORMAT.md)).

- **Seal**: choose what leaves with you; biometric-gated keys ask for a fingerprint
  each (refuse and that key stays behind, named).
- **Open**: previews what's inside, then lands it. Merges by identity, newer wins;
  **pinned host keys are never overwritten** by an import.
- This is the free answer to cloud sync: move to a new phone, or keep an offline
  backup. No account, no server, ever.

**Migrating installs** (e.g. coming from a pre-1.0 debug-signed build, which the
1.0 signature can't update in place): seal the reliquary → copy the file off /
keep it in phone storage → uninstall → install the new APK → open the reliquary.

## 12. The helm — settings

Gear icon on the Dock:

- **glyph size** — same value the pinch gesture writes; reset button included.
- **keyboard** — abc / raw default (see §4).
- **keep the screen lit at sea** — no dozing while a terminal is up.
- **the sky** — phone / night / day theme.
- **livery** — terminal color scheme, per your next crossing; the mini-terminal
  previews each.
- **the river's memory** — history count, forget-all (see §5).
- **the horn** — see §10.
- **the reliquary** — see §11.

## 13. Ran aground? — troubleshooting

A failed crossing says the error twice: the literal message first (so nothing is
hidden from you), then what it most likely means in plain words — no such host,
nothing listening on that port, no answer (asleep or firewalled), every key and
password refused, a key that wouldn't open. The hold does the same when SFTP won't
open (often: the server has no `Subsystem sftp` line).

- **"terminal size too small" (btop, htop)** — you're under 80×24. Pinch out (6 sp
  fits 80 columns portrait), or rotate.
- **Predictions interfering in vim/emacs** — switch the accessory row to **raw**.
- **Password showed up in suggestions?** It shouldn't — that's the toll's job. If
  you ever see it, long-press the chip to forget it and please file an issue.
- **Sessions die when the phone sleeps** — exempt Charon from battery optimization;
  pair with tmux auto-attach (§9) so even a kill costs nothing.
- **No horn notifications** — Android 13+ needs the notification permission Charon
  asks for on first run; check it wasn't denied, and verify the shell rig
  (`HORN.md`). Inside tmux the marks need `set -g allow-passthrough on`.
- **Relative paths / branches don't complete** — the shell rig (`HORN.md`) isn't
  loaded (open a new shell after adding it), or you're inside tmux without the two
  `~/.tmux.conf` lines from HORN.md, or the prompt names a different machine than
  the one you crossed to (an onward ssh — by design). `/…` and `~/…` paths work
  either way.
- **Host key changed warning** — someone reinstalled the host's OS, or something is
  impersonating it. Verify out-of-band before replacing the pin.
- **Import from Tailscale finds nothing** — the fetch runs `tailscale status` on an
  already-connected host; cross somewhere first, or check that host has Tailscale.

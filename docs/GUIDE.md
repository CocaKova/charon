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
  Pick the sightings you want, set the shared username/port/key, and they dock.

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

## 4. At sea — the terminal

The emulator is Charon's own: truecolor, mouse reporting, bracketed paste,
xterm-conformance-tested (see `TERMINAL.md`).

**Touch:**

| Gesture | In a normal shell | In a mouse app (htop, tmux w/ mouse) |
|---|---|---|
| tap | focus / raise keyboard | sends the click to the app |
| long-press | select word (keeps paths whole) | select word |
| drag | scroll the scrollback | wheel-scroll the app |
| drag from a selection | extend the selection | — |
| pinch | zoom the glyphs (6–32 sp) | same |

- Selection lives in the scrollback, not just the visible screen — select, scroll,
  keep selecting; **copy** and **all** pills appear with a selection.
- Scrolled up, a gold **▼ live** pill takes you back to the bottom; any keystroke
  also snaps to live.
- Every grid re-snap flashes a **cols×rows** pill — ember-tinted when you're under
  80×24, which full-screen tools like btop insist on (zoom out, or go landscape).

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

## 5. The strip that thinks — autocomplete

Charon's suggestions come from the *host you're on*, not a canned dictionary:

- On every crossing it quietly inventories what's installed, and live-probes
  arguments as you type: `tmux attach -t ` offers the sessions *actually running*,
  `docker exec ` the containers, `systemctl` the units, `ssh ` the hosts in the
  remote's own `~/.ssh/config`.
- Paths complete anywhere: any argument starting `/` or `~/` lists the remote
  directory, cascading level by level.
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

From the session switcher, **⇅** opens the hold:

- Browse the remote tree (▸ dirs, ⇝ links); tap a file to **carry it ashore**
  (download via the system file picker), rename it, or release it into the river
  (delete). **⇡** carries a local file aboard; **+** makes a directory.
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

Flip **the horn** at the helm and rig your shell with the one-liner in
[HORN.md](HORN.md): any command that runs ≥ 15 s while you're in another app blows
the horn — a notification saying it finished (or *ran aground* with its exit code).
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

- **"terminal size too small" (btop, htop)** — you're under 80×24. Pinch out (6 sp
  fits 80 columns portrait), or rotate.
- **Predictions interfering in vim/emacs** — switch the accessory row to **raw**.
- **Password showed up in suggestions?** It shouldn't — that's the toll's job. If
  you ever see it, long-press the chip to forget it and please file an issue.
- **Sessions die when the phone sleeps** — exempt Charon from battery optimization;
  pair with tmux auto-attach (§9) so even a kill costs nothing.
- **No horn notifications** — Android 13+ needs the notification permission Charon
  asks for on first run; check it wasn't denied, and verify the shell rig
  (`HORN.md`).
- **Host key changed warning** — someone reinstalled the host's OS, or something is
  impersonating it. Verify out-of-band before replacing the pin.
- **Import from Tailscale finds nothing** — the fetch runs `tailscale status` on an
  already-connected host; cross somewhere first, or check that host has Tailscale.

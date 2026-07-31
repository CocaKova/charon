# Charon ⚶

**The ferryman for your fleet.** A native Android SSH client that refuses to paywall the basics.

Everything Termius charges for is free in Charon: unlimited hosts, SFTP, snippets, port
forwarding, key management, encrypted vault export/import. If you want to tip the
ferryman, there's the **Obol** — a one-time purchase that unlocks cosmetic theme packs,
alternate app icons, and a supporter mark. Nothing functional is ever behind it.

## Why another SSH client?

- **The terminal is the product.** Charon's emulator is written from scratch — truecolor,
  proper VT/xterm emulation, tested against recorded vim/htop/tmux sessions, a keyboard
  accessory row with sticky modifiers that makes phone SSH actually usable.
- **Fleet awareness.** Tag and group hosts, see reachability at a glance, pin per-host
  quick actions, import your Tailscale fleet.
- **Sessions that survive.** A foreground service keeps connections alive in the
  background; auto-reconnect plus per-host startup commands (`tmux new -As main`) mean a
  dropped connection puts you right back where you were.
- **Your data is yours.** Keys are encrypted with the Android Keystore, optionally behind
  biometrics. The vault exports to a single passphrase-encrypted file with a documented
  format — no cloud account, ever.

## What's aboard (v1.1)

- From-scratch VT/xterm terminal: truecolor, mouse reporting, scrollback + selection into
  scrollback, bracketed paste, pinch-zoom, per-session color liveries — corpus-tested
  against recorded vim/htop/tmux sessions and conformance-checked (see `docs/TERMINAL.md`)
- **Inline images** — the Kitty graphics protocol and iTerm2's `imgcat`, drawn in the grid
  and scrolling with the text. Tap one for a full-screen lightbox with pinch-zoom, save
  and share: on a phone an inline image is a photo, not a dead rectangle
- Accessory key row with sticky/lockable Ctrl-Alt, long-press variants, auto-repeat, F-keys
- Host-aware autocomplete: command grammar plus live probes of what's actually installed
  and running on the host (tmux sessions, docker containers, systemd units, remote paths) —
  with a prose gate and a secret gate so it never learns your sentences or your tokens
- Multi-session with auto-reconnect, instant network-return redial, and per-host startup
  commands (`tmux new -As main`); adaptive keepalive so idle sessions sip battery
- SFTP browser with resumable transfers; local, remote, and dynamic (SOCKS5) port
  forwards; snippets; fleet view with reachability soundings, Tailscale import, LAN sweep
- The toll: password prompts are detected and keystrokes routed around the suggestion
  strip, the IME's learning, and command history
- The reliquary: the whole vault (hosts, keys, known hosts, snippets, forwards) exports to
  one passphrase-sealed `.charon` file — Argon2id + AES-GCM, format in `docs/VAULT_FORMAT.md`
- The horn: a notification when a long command finishes while you're away (OSC 133,
  rig one-liners in `docs/HORN.md`)

## Installing

Grab the APK from [Releases](https://github.com/CocaKova/charon/releases) and sideload it.
Every release is signed with the same key, so updates install in place.

**[The Guide](docs/GUIDE.md)** covers everything from the first crossing to vault
export — start there. Deeper references: [terminal conformance](docs/TERMINAL.md),
[input model](docs/INPUT.md), [vault format](docs/VAULT_FORMAT.md),
[the horn's shell rig](docs/HORN.md).

## Building

```
source ~/android-buildenv/env.sh   # or your own JDK 17 + Android SDK 36
./gradlew :terminal-core:test      # emulator core test suite (pure JVM)
./gradlew :app:assembleDebug
```

Release signing is configured via `local.properties` (`charon.keystore`,
`charon.keystore.password`, `charon.key.alias`, `charon.key.password`); without it,
release builds fall back to the debug keystore for sideloading.

## Authorship

Charon is written and maintained solely by Jonathan Kovacs ([@CocaKova](https://github.com/CocaKova)).

## License

[PolyForm Noncommercial 1.0.0](LICENSE) — read it, build it, use it, share it freely for
any noncommercial purpose. Selling Charon or shipping it in a commercial product is
reserved to the author.

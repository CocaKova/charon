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

## Status

Pre-release (v0.1 walking skeleton in progress). See `docs/PLAN.md` for the roadmap.

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

TBD — all rights reserved until a license is chosen (see `LICENSE`).

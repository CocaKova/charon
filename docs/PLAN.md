# Charon — a better SSH client than Termius

## Context

Jonny's next big app: a native Android SSH client named **Charon** (the ferryman who carries you across — and whom you pay an obol). Termius paywalls the basics (sync, SFTP, snippets); Charon's pitch is: **everything they paywall is free**, a **world-class terminal** (the moat — built from scratch, 100% owned code, no GPL), **fleet awareness** for a real homelab/tailnet, and **warmhearted monetization** — a one-time "Obol for the Ferryman" IAP on Play that unlocks cosmetics only.

Decisions locked with Jonny:
- **Android-first**, Kotlin + Compose, built with the existing `~/android-buildenv` toolchain (source `env.sh`; **no NDK** → pure-JVM SSH stack, mosh deferred)
- **Own terminal emulator** (no Termux/jackpal code — license stays Jonny's)
- **Play + GitHub distribution**; core free everywhere; Obol IAP = cosmetics + supporter mark
- **Jonny is the sole contributor**: no `Co-Authored-By` trailers, no session links, anywhere, ever (same rule as Orpheus)

## Foundations copied from Keryx (recon-verified)

- Single-module-style Compose app shape: manual DI via Application singletons (`KeryxApp.kt` pattern), no Navigation-Compose (drawer + sheets), SharedPreferences settings wrapper, theme/Color+Theme+Type.kt with dual accents
- Proven-under-QEMU version pins from `~/workspace/keryx/Hermes-Chat/gradle/libs.versions.toml`: AGP 9.0.1, Kotlin 2.0.0, KSP 2.0.0-1.0.21, Compose BOM 2026.03.01, compileSdk/targetSdk 36, minSdk 24, JDK 17
- **The load-bearing template**: `keryx/.../notify/BuiltinPushService.kt` — `specialUse` FGS (manifest property + `ServiceCompat.startForeground` SDK-34 gate), START_STICKY, MIN-importance ongoing notification, exponential backoff (`3s shl retries`), `registerDefaultNetworkCallback` instant reconnect
- Signing via `local.properties` (`charon.keystore.*`), debug-keystore fallback, R8 OFF, manual `assembleRelease` → GitHub Releases as changelog, no CI
- `PetPickerSheet.kt` UX shape → Obol theme-pack picker

## Repo & project setup

- New repo `~/workspace/charon` → GitHub `CocaKova/charon`; **private through the messy middle, public at v1.0** (Jonny can flip earlier)
- **Gradle root at repo root** (avoid Keryx's nested `Hermes-Chat/` mistake). Modules: `:app` + `:terminal-core`
- applicationId/namespace: **`com.cocakova.charon`** (terminal core = `com.cocakova.charon.terminal`)
- LICENSE file created day one as "TBD — all rights reserved until chosen"; choosing (Apache-2.0 vs source-available/PolyForm) blocks the go-public step, nothing else
- Docs: `docs/PLAN.md` (this plan), `docs/TERMINAL.md` (feature matrix + conformance checklist), `docs/VAULT_FORMAT.md`, `docs/PLAY_DECLARATIONS.md`

```
charon/
├── settings.gradle.kts  build.gradle.kts  gradle/libs.versions.toml
├── LICENSE  README.md  docs/
├── terminal-core/            # pure Kotlin JVM module — ZERO Android imports
│   └── src/main/kotlin/com/cocakova/charon/terminal/
│       Parser.kt TerminalEmulator.kt ScreenBuffer.kt Line.kt CellAttrs.kt
│       Utf8Decoder.kt Wcwidth.kt TabStops.kt Modes.kt Palette.kt Charsets.kt
│       Responses.kt input/{KeyEncoder,MouseEncoder}.kt
│   └── src/test/ (JVM unit tests + resources/corpus/ goldens)
└── app/src/
    ├── main/java/com/cocakova/charon/
    │   CharonApp.kt MainActivity.kt
    │   data/db/  data/repository/  data/crypto/  domain/
    │   ssh/      # SshEngine.kt SshjEngine.kt SessionManager.kt
    │             # KnownHostsVerifier.kt Socks5Server.kt SftpTransfers.kt
    │   service/ConnectionService.kt
    │   billing/EntitlementRepository.kt
    │   presentation/terminal/  # TerminalView, AccessoryRow, TerminalInput, SelectionOverlay
    │   presentation/{hosts,fleet,sftp,snippets,forwards,vault,settings}/
    │   theme/
    ├── foss/…/billing/FossEntitlements.kt
    └── play/…/billing/{PlayEntitlements,ObolBilling}.kt
```

## Key technical decisions

### SSH engine: sshj (Apache-2.0, pure JVM)
Only pure-JVM lib combining openssh-key-v1 (incl. bcrypt-encrypted), ed25519/chacha20-poly1305/aes-gcm, full SFTP, all forwarding types. Fallback: mwiede JSch fork — kept cheap via a thin `SshEngine` interface (connect/auth/shell+PTY/exec/sftp/direct-tcpip/keepalive), nothing more.
- **Day-one tripwire**: ship `bcprov-jdk18on`; in `CharonApp`: `Security.removeProvider("BC")` then insert real BouncyCastle at position 1 (Android's stripped BC shadows it otherwise — the #1 sshj-on-Android bug)
- Dynamic SOCKS forwarding: small owned SOCKS5 server (~200 lines) over `direct-tcpip` channels
- Keys: never plaintext at rest — per-identity AES-256-GCM key in AndroidKeyStore (non-exportable), ciphertext in Room; optional biometric gate (`setUserAuthenticationRequired` + BiometricPrompt/CryptoObject); decrypt only at connect, zero buffers after

### Terminal emulator (the crown jewel, ~⅓ of total effort)
- **`:terminal-core` = pure Kotlin JVM module** → heavy unit testing on the Spark, no device needed
- Parser: Paul Flo Williams VT500 state machine (verbatim states), streaming UTF-8 decoder in front, parser must be total (fuzz: never throws)
- Grid: ring-buffer scrollback (10k default); packed-Long cell attrs (25-bit fg + 25-bit bg + style bits); **no reflow v1** but `Line` carries `isWrapped` flag from day one (enables reflow later without a data-model rewrite)
- v0 scope = "vim + htop + tmux render correctly": full cursor/edit CSI set, SGR incl. truecolor, DECSTBM, origin mode, **deferred-wrap semantics (the classic emulator bug — test explicitly)**, tab stops, DECCKM/DECTCEM, alternate screen (1049 et al.), charsets w/ DEC Special Graphics, DA1/DA2/DSR/CPR responses, OSC 10/11 color queries
- v0.4 additions: mouse (SGR 1006), bracketed paste, focus events, OSC 0/2 title, OSC 52 clipboard behind per-host consent, OSC 4/104
- wcwidth: generated table from UCD (checked-in artifact + regen script); match remote `wcwidth()` semantics, not perfect grapheme clustering; "ambiguous = wide" setting
- **Testing = the quality story**: per-control-function JVM tests; corpus goldens (record real vim/htop/tmux sessions with `script` on the Spark, replay bytes, assert grid); fuzz totality; parse-throughput benchmark (≥50 MB/s target); vttest/esctest as v1.0 checklist

### Rendering & input
- Compose Canvas, **run-batched `nativeCanvas.drawText`** with cached Paints (no glyph atlas — platform text gives shaping/fallback/emoji free); wide/fallback glyphs drawn individually centered in their cell span; dirty-line marks + conflated channel + `withFrameNanos` frame pacing (floods skip to latest state, never queue)
- Bundle JetBrains Mono (OFL); fallback: user TTF (SAF) → JetBrains Mono → system monospace; pinch-zoom 8–32sp persisted per host (triggers PTY window-change)
- **IME: dual mode** — default raw (`inputType = TYPE_NULL` → real KeyEvents, the proven terminal approach) + predictive fallback mode (commitText→bytes, composing overlay strip) for misbehaving IMEs
- **Accessory key row = flagship differentiator**: Esc/Tab/Ctrl/Alt/arrows/PgUp-Dn//|– with long-press variants, autorepeat arrows, **sticky modifiers** (tap=one-shot, double-tap=lock, visual latch), swipe to F-key page. Full hardware-keyboard path (Ctrl combos, Alt-as-Meta, Ctrl+Shift+C/V)
- Selection: long-press word + draggable handles + copy toolbar; URL detect + tap-to-open

### Data & vault
- Room 2.6.1 + KSP; UUID string PKs + `lastModified` everywhere (merge-able)
- Entities: Host (auth method, identityId, tags JSON, group, color, `tailscaleNodeId`, `startupCommand`, autoReconnect), Identity (encrypted blob + keystoreAlias + biometricGated), KnownHost (**TOFU sheet with SHA256 fingerprint; mismatch = full-screen red warning, refuse unless explicit replace**), Snippet + per-host pins, PortForward (L/R/D, autoStart)
- **Vault export/import = free Termius-sync killer**: single `.charon` file — magic `CHARON1` + Argon2id (BouncyCastle `Argon2BytesGenerator`, no new dep; m=32MiB t=3 p=2) + AES-256-GCM over full JSON dump; import merges by UUID, newer wins; format documented in `docs/VAULT_FORMAT.md`

### Session lifecycle
- `SessionManager` (app singleton) owns N sessions (sshj client + shell channel + emulator + reader coroutine); `ConnectionService` (**specialUse FGS**, Keryx template) exists to pin the process + own the notification ("Charon — 3 sessions", per-session disconnect/jump actions); starts 0→1 sessions, stops 1→0
- Why specialUse: `dataSync` has a ~6h cap on Android 15; `connectedDevice` = documented fallback if Play review balks; declaration text kept in `docs/PLAY_DECLARATIONS.md`
- **Mosh compensation** (no NDK): auto-reconnect (backoff capped 120s + network-callback instant redial) + per-host `startupCommand` (`tmux new -As main`) + FGS ≈ 90% of mosh's value
- No boot receiver (deliberate — auto-SSH on boot fights the credential model); sshj keepalive 15–30s; battery-optimization exemption flow per Keryx + troubleshooting page

### Obol monetization
- **Flavors `foss` / `play`** (runtime detection would compile Play Billing into the GitHub APK — dead dep in a "self-contained" build). Same applicationId + signing key → installs cross-update
- `play`: billing-ktx, one-time product `obol`, entitlement cached in prefs, no server verification (it gates cosmetics; piracy irrelevant by design)
- Obol unlocks: exclusive terminal theme packs, alt app icons (activity-alias), supporter ferryman-coin mark. **Proposed: foss builds ship theme packs unlocked** (tip, not lock); supporter mark stays Obol-only in both — Jonny confirms before v1.1

## Milestones (each ends demoable on the phone via adb over Tailscale)

| Ver | Name | Size | Demo gate |
|---|---|---|---|
| **v0.1** | Walking skeleton: terminal-core v0 + corpus rig, Compose renderer, raw input + minimal key row, sshj connect (password + pasted key), ConnectionService, BC wiring | L | ssh to the Spark from the phone; vim + htop render right; background 10 min, session alive |
| v0.2 | Host vault & trust: Room, host CRUD, TOFU sheet + mismatch red screen, Keystore passwords | M | Saved hosts, fingerprint prompt, reconnect in 2 taps |
| v0.3 | Keys & identities: keygen (ed25519 default), import openssh/PEM incl. encrypted, ssh-copy-id flow, biometric gate | M | Key born on phone → passwordless + biometric login |
| v0.4 | Terminal excellence: full accessory row, hardware kbd, predictive IME mode, selection/paste, mouse, pinch zoom, OSC 52 consent, color schemes | L | Comfortable real tmux session with touch mouse |
| v0.5 | Multi-session: tabs, per-session notification actions, auto-reconnect + tmux auto-attach | M | 3 hosts; airplane-mode toggle; all return attached |
| v0.6 | SFTP: single-pane remote browser + SAF local, transfers w/ progress in FGS, resume | L | Pull log, edit locally, push back |
| v0.7 | Forwards & snippets: L/R/D + owned SOCKS5, snippet bar + per-host pins | M | Server web UI forwarded to phone browser |
| v0.8 | Fleet dashboard: groups/tags, reachability dots (parallel TCP dial, no daemon), Tailscale JSON import, LAN port-22 sweep | M | Green dots across the tailnet, one-tap quick actions |
| v0.9 | Vault & polish: .charon export/import, theming, onboarding | M | Export phone A → import phone B, identical fleet |
| **v1.0** | Public release: LICENSE decided, README, repo public, signed GitHub Release, conformance pass | M | Tagged v1.0.0 anyone can sideload |
| v1.1 | Obol + Play: flavors, billing, theme packs, alt icons, Play listing + specialUse declaration | M | Obol unlocks pack on Play build; foss unaffected |
| v1.x | Backlog: reflow, OSC 8, ProxyJump (pure-JVM, good early candidate), agent forwarding, ssh-config import, mosh (blocked on NDK) | — | — |

Dependency spine: 0.1 → 0.2 → 0.3 → 0.4 ∥ 0.5 → 0.6 ∥ 0.7 → 0.8 → 0.9 → 1.0 → 1.1.

## Top risks

1. **Terminal correctness tar pit** (H/H) — corpus goldens from day one; hard v0 list; "vim+htop+tmux correct" is the bar
2. **IME weirdness across keyboards** (H/M) — dual input modes; test on Jonny's phone in v0.1, not v0.4
3. sshj Android edges (M/M) — BC recipe day one; `SshEngine` keeps JSch swap cheap; test vs modern OpenSSH + old Dropbear early
4. specialUse Play review (M/M, v1.1-only) — declaration prepped; connectedDevice fallback
5. OEM battery killers (H/M) — Keryx's proven FGS combo + tmux auto-attach makes kills survivable
6. Output-flood perf (M/M) — core throughput benchmark + conflated frame-paced rendering, measured before v1.0

## Verification

- **terminal-core**: JVM test suite on the Spark (`./gradlew :terminal-core:test`) — control-function units, corpus goldens (recorded vim/htop/tmux byte streams), fuzz totality, throughput benchmark
- **App**: `source ~/android-buildenv/env.sh && ./gradlew :app:assembleDebug`; install on phone via `adb connect 100.93.255.23` (never drive the screen while Jonny's using it); each milestone's demo gate above is the acceptance test, against the Spark itself as the SSH target
- **v1.0 gate**: vttest/esctest checklist in `docs/TERMINAL.md`, flood benchmark on device, 10-minute-background session survival

## Deferred decisions (Jonny's, non-blocking now)

- LICENSE choice (blocks repo-public at v1.0): Apache-2.0 goodwill vs source-available clone-protection
- foss flavor: theme packs unlocked (proposed) vs locked (blocks v1.1)


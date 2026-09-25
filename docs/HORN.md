# The Horn — command-done pushes (and the soundings)

*The mobile surpass: your terminal is in your pocket, so "the build finished" can be a
buzz in your hand.* When a command that ran ≥ 15 s finishes while Charon is in the
background, the horn sounds — a push notification with the command, how long its voyage
took, the exit code if it ran aground, and which crossing it came from. Tap it to step
back aboard. Toggle lives at the helm ("the horn", on by default).

The same rig also takes **soundings**: the prompt reports the shell's working
directory, and the suggestion strip completes relative paths and git branches from
where you actually stand (`docs/INPUT.md` §2b).

## How it works

Charon implements **OSC 133 semantic prompts** (the shell-integration protocol Ghostty,
WezTerm, Kitty and friends share). The shell emits invisible marks; Charon pairs the
`D` (command finished, with `$?`) against the line you pressed Enter on and measures the
voyage. `C` (output begins) refines the start time when present. Unrigged shells simply
never sound the horn — nothing breaks.

**Soundings** ride **OSC 7** (`ESC ] 7 ; file://host/path BEL`, percent-encoded — the
convention VTE, WezTerm, kitty and tmux share). The emulator parses it strictly: a
report that isn't a `file:` URL, or whose escapes don't decode to UTF-8, is dropped and
the last good one stands. The app then decides whether to *believe* it:

- **Right host only.** Completion probes run over a silent exec channel on the host
  this crossing is connected to, so a report must name that host (`hostname`, compared
  by short name — `spark` matches `spark.lan`), or no host at all. A shell you ssh'd
  onward to reports *its* paths, and is ignored.
- **Fresh only.** Enter at a prompt on the normal screen forgets the cwd; the next
  rigged prompt reports it again. Anything that isn't a rigged prompt — an onward
  `ssh`, a REPL, a chat, an unrigged shell — never re-reports, so relative completion
  simply stays off there instead of guessing at a directory that no longer applies.
  This is why the rig below reports on **every** prompt, not only on `cd`.
- **Screens and drops.** Crossing into or out of the alternate screen forgets it too
  (unless the new side reported its own in the same burst), and so does a dropped
  connection.

## Rigging a host

One block in the shell's rc file. Idempotent (sourcing twice doesn't double up), keeps
`$?` intact for the rest of your prompt, and harmless in any other terminal.

**bash** (`~/.bashrc`):

```bash
# Charon shell integration: the horn (OSC 133) + the soundings (OSC 7)
__charon_prompt() {
  local rc=$? LC_ALL=C s=$PWD u= c i
  for ((i = 0; i < ${#s}; i++)); do
    c=${s:i:1}
    case $c in [A-Za-z0-9/._~-]) u+=$c ;; *) printf -v c '%%%02X' "'$c"; u+=$c ;; esac
  done
  if [ -n "$TMUX" ]; then printf '\ePtmux;\e\e]133;D;%s\a\e\\' "$rc"
  else printf '\e]133;D;%s\a' "$rc"; fi
  printf '\e]7;file://%s%s\a' "$HOSTNAME" "$u"
  return $rc
}
[[ $PROMPT_COMMAND == *__charon_prompt* ]] ||
  PROMPT_COMMAND="__charon_prompt${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
```

**zsh** (`~/.zshrc`):

```zsh
# Charon shell integration: the horn (OSC 133) + the soundings (OSC 7)
__charon_prompt() {
  local rc=$? LC_ALL=C s=$PWD u= c i
  for (( i = 1; i <= ${#s}; i++ )); do
    c=${s[i]}
    case $c in ([A-Za-z0-9/._~-]) u+=$c ;; (*) printf -v c '%%%02X' $(( #c & 255 )); u+=$c ;; esac
  done
  if [[ -n $TMUX ]]; then printf '\ePtmux;\e\e]133;D;%s\a\e\\' $rc
  else printf '\e]133;D;%s\a' $rc; fi
  printf '\e]7;file://%s%s\a' $HOST $u
  return $rc
}
(( ${precmd_functions[(I)__charon_prompt]} )) || precmd_functions+=(__charon_prompt)
```

**fish** (`~/.config/fish/config.fish`):

```fish
# Charon shell integration: the horn (OSC 133) + the soundings (OSC 7)
function __charon_prompt --on-event fish_prompt
    set -l rc $status
    if set -q TMUX
        printf '\ePtmux;\e\e]133;D;%s\a\e\\' $rc
    else
        printf '\e]133;D;%s\a' $rc
    end
    printf '\e]7;file://%s%s\a' $hostname (string escape --style=url -- $PWD)
end
```

The loop in the bash and zsh versions percent-encodes the path byte by byte (a space,
a `%`, an `é` all survive the trip); fish has an encoder built in. Checked against bash
5, zsh 5.9 and fish 4.

Optional, for more accurate horn timing (marks when output actually starts, so shell
startup lag doesn't count against the voyage) — bash example via a preexec hook, or in
zsh:

```zsh
preexec() { printf '\e]133;C\a' }
```

## Inside tmux

tmux sits between the shell and Charon, and **swallows both marks by default** —
verified on tmux 3.4: a raw OSC 133 or OSC 7 from a pane never reaches the outer
terminal, with or without `allow-passthrough`. Each needs its own way across.

**The horn (OSC 133)** crosses only when wrapped in tmux's passthrough envelope
(`ESC P tmux; … ESC \`) — the rig above wraps it whenever `$TMUX` is set — and tmux is
told to let passthrough out:

```tmux
set -g allow-passthrough on
```

`on` passes marks from *visible* panes only; `all` (tmux 3.4+) also lets a finished
command in a background window sound the horn.

**The soundings (OSC 7)** are better handled by tmux itself: it records each pane's
reported path (`#{pane_path}`) and, once it knows the outer terminal understands OSC 7,
tells it the *active* pane's path — again whenever you switch panes or windows, and an
empty report when the pane you switched to never reported one (Charon then stops
completing relative names rather than use the wrong pane's directory). Two lines in
`~/.tmux.conf`:

```tmux
set -as terminal-features 'xterm*:osc7'
set -g set-titles on
```

The first tells tmux Charon speaks OSC 7 (Charon's `TERM` is `xterm-256color`); the
second matters because tmux only sends the path as part of updating the outer
terminal's title — which Charon also uses to name the tab (`docs/GUIDE.md` §9). The
rig's OSC 7 is deliberately **not** wrapped in passthrough: raw, it updates tmux's own
pane path, which is what tmux forwards.

Honest limits inside tmux: tmux forwards a path only when it *changes*, so Charon keeps
the cwd across Enter there instead of forgetting it — if you ssh onward from a tmux
pane into a host whose shell isn't rigged, the pane still carries the old path and
relative completion keeps offering the old directory until you switch panes or come
back. The `osc7` terminal feature was verified on tmux 3.4; older tmux may not have it,
in which case the soundings stay off inside tmux (the horn is unaffected).

## The gates

The horn stays silent unless **all** of these hold — it must never become a buzzer:

1. the voyage ran at least **15 s** (you never left the rail for less),
2. Charon is **not on screen** (a push about what you're watching is noise),
3. the helm's horn toggle is on, and notifications are permitted (asked once on 13+).

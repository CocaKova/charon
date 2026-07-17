# The Horn — command-done pushes

*The mobile surpass: your terminal is in your pocket, so "the build finished" can be a
buzz in your hand.* When a command that ran ≥ 15 s finishes while Charon is in the
background, the horn sounds — a push notification with the command, how long its voyage
took, the exit code if it ran aground, and which crossing it came from. Tap it to step
back aboard. Toggle lives at the helm ("the horn", on by default).

## How it works

Charon implements **OSC 133 semantic prompts** (the shell-integration protocol Ghostty,
WezTerm, Kitty and friends share). The shell emits invisible marks; Charon pairs the
`D` (command finished, with `$?`) against the line you pressed Enter on and measures the
voyage. `C` (output begins) refines the start time when present. Unrigged shells simply
never sound the horn — nothing breaks.

## Rigging a host

One line in the shell's rc file. Idempotent, harmless in any other terminal.

**bash** (`~/.bashrc`):

```bash
PROMPT_COMMAND='printf "\e]133;D;%s\a" "$?"'"${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
```

**zsh** (`~/.zshrc`):

```zsh
precmd() { printf '\e]133;D;%s\a' "$?" }
```

**fish** (`~/.config/fish/config.fish`):

```fish
function __charon_horn --on-event fish_prompt
    printf '\e]133;D;%s\a' $status
end
```

Optional, for more accurate timing (marks when output actually starts, so shell startup
lag doesn't count against the voyage) — bash example via a preexec hook, or in zsh:

```zsh
preexec() { printf '\e]133;C\a' }
```

## Inside tmux

tmux passes OSC sequences through when asked. Rig the shell as above; if marks don't
arrive, add to `~/.tmux.conf`:

```tmux
set -g allow-passthrough on
```

## The gates

The horn stays silent unless **all** of these hold — it must never become a buzzer:

1. the voyage ran at least **15 s** (you never left the rail for less),
2. Charon is **not on screen** (a push about what you're watching is noise),
3. the helm's horn toggle is on, and notifications are permitted (asked once on 13+).

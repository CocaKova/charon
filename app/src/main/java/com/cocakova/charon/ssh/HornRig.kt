package com.cocakova.charon.ssh

import java.util.Base64

/**
 * Rigging a shore for the horn and the soundings from the phone: one block in the
 * login shell's rc file (the same block docs/HORN.md prints), between two marker
 * lines so it is easy to find and to take out again. Nothing here runs without the
 * traveller having seen the exact text and the exact file first.
 *
 * The errand travels as base64 into `sh`, so the far shore's login shell (bash, zsh
 * or fish all run the exec request) never gets a chance to read the quotes its own
 * way; the script appends only when the begin marker isn't there yet.
 */
object HornRig {

    enum class Shell(val rcFile: String) {
        BASH("~/.bashrc"),
        ZSH("~/.zshrc"),
        FISH("~/.config/fish/config.fish"),
    }

    /** Ask the far shore which shell it logs in with. Valid in bash, zsh and fish. */
    const val PROBE = "echo \"\$SHELL\""

    const val BEGIN = "# >>> charon rig >>>"
    const val END = "# <<< charon rig <<<"

    /** `/usr/bin/zsh` → ZSH; anything we don't carry a block for → null. */
    fun shellOf(probe: String): Shell? = when (probe.trim().substringAfterLast('/')) {
        "bash" -> Shell.BASH
        "zsh" -> Shell.ZSH
        "fish" -> Shell.FISH
        else -> null
    }

    /** Exactly what lands at the end of the rc file, markers included. */
    fun appended(shell: Shell): String = "\n$BEGIN\n${block(shell)}$END\n"

    /** The errand: idempotent append, answered by `charon-rig:done` or `charon-rig:already`. */
    fun installCommand(shell: Shell): String {
        val file = "\"\$HOME/" + shell.rcFile.removePrefix("~/") + "\""
        val script = buildString {
            append("f=").append(file).append('\n')
            append("if grep -qF '").append(BEGIN).append("' \"\$f\" 2>/dev/null; then echo charon-rig:already; exit 0; fi\n")
            append("mkdir -p \"\$(dirname \"\$f\")\" || exit 1\n")
            append("cat >> \"\$f\" <<'CHARON_RIG_EOF'\n")
            append(appended(shell))
            append("CHARON_RIG_EOF\n")
            append("echo charon-rig:done\n")
        }
        val b64 = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        return "echo $b64 | base64 -d | sh"
    }

    enum class Outcome { RIGGED, ALREADY, FAILED }

    fun outcomeOf(output: String): Outcome = when {
        "charon-rig:done" in output -> Outcome.RIGGED
        "charon-rig:already" in output -> Outcome.ALREADY
        else -> Outcome.FAILED
    }

    /**
     * The block per shell. Prompt start (A), the command's output starting (C), the
     * command done with its exit code (D), and the working directory (OSC 7). Inside
     * tmux the 133 marks ride the passthrough envelope; `$?` survives for the rest
     * of the prompt; sourcing twice doesn't double up.
     */
    fun block(shell: Shell): String = when (shell) {
        Shell.BASH -> """
# Charon shell integration: the horn (OSC 133) + the soundings (OSC 7)
__charon_osc() {
  if [ -n "${'$'}TMUX" ]; then printf '\ePtmux;\e\e]%s\a\e\\' "${'$'}1"; else printf '\e]%s\a' "${'$'}1"; fi
}
__charon_prompt() {
  local rc=${'$'}? LC_ALL=C s=${'$'}PWD u= c i
  for ((i = 0; i < ${'$'}{#s}; i++)); do
    c=${'$'}{s:i:1}
    case ${'$'}c in [A-Za-z0-9/._~-]) u+=${'$'}c ;; *) printf -v c '%%%02X' "'${'$'}c"; u+=${'$'}c ;; esac
  done
  __charon_osc "133;D;${'$'}rc"
  printf '\e]7;file://%s%s\a' "${'$'}HOSTNAME" "${'$'}u"
  __charon_osc "133;A"
  return ${'$'}rc
}
[[ ${'$'}PROMPT_COMMAND == *__charon_prompt* ]] ||
  PROMPT_COMMAND="__charon_prompt${'$'}{PROMPT_COMMAND:+;${'$'}PROMPT_COMMAND}"
[[ ${'$'}PS0 == *__charon_osc* ]] || PS0+='${'$'}(__charon_osc "133;C")'
""".trimStart()
        Shell.ZSH -> """
# Charon shell integration: the horn (OSC 133) + the soundings (OSC 7)
__charon_osc() {
  if [[ -n ${'$'}TMUX ]]; then printf '\ePtmux;\e\e]%s\a\e\\' ${'$'}1; else printf '\e]%s\a' ${'$'}1; fi
}
__charon_prompt() {
  local rc=${'$'}? LC_ALL=C s=${'$'}PWD u= c i
  for (( i = 1; i <= ${'$'}{#s}; i++ )); do
    c=${'$'}{s[i]}
    case ${'$'}c in ([A-Za-z0-9/._~-]) u+=${'$'}c ;; (*) printf -v c '%%%02X' ${'$'}(( #c & 255 )); u+=${'$'}c ;; esac
  done
  __charon_osc "133;D;${'$'}rc"
  printf '\e]7;file://%s%s\a' ${'$'}HOST ${'$'}u
  __charon_osc "133;A"
  return ${'$'}rc
}
__charon_preexec() { __charon_osc "133;C" }
(( ${'$'}{precmd_functions[(I)__charon_prompt]} )) || precmd_functions+=(__charon_prompt)
(( ${'$'}{preexec_functions[(I)__charon_preexec]} )) || preexec_functions+=(__charon_preexec)
""".trimStart()
        Shell.FISH -> """
# Charon shell integration: the horn (OSC 133) + the soundings (OSC 7)
function __charon_osc
    if set -q TMUX
        printf '\ePtmux;\e\e]%s\a\e\\' ${'$'}argv[1]
    else
        printf '\e]%s\a' ${'$'}argv[1]
    end
end
function __charon_prompt --on-event fish_prompt
    set -l rc ${'$'}status
    __charon_osc "133;D;${'$'}rc"
    printf '\e]7;file://%s%s\a' ${'$'}hostname (string escape --style=url -- ${'$'}PWD)
    __charon_osc "133;A"
end
function __charon_preexec --on-event fish_preexec
    __charon_osc "133;C"
end
""".trimStart()
    }
}

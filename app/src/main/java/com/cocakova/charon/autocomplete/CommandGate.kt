package com.cocakova.charon.autocomplete

/**
 * Tells shell command lines apart from prose typed into whatever happens to be
 * running on the remote — a chat, a REPL, an editor buffer. The autofill history
 * must only ever learn the former: one recorded sentence resurfaces as a full-line
 * suggestion the moment a later line shares its opening words.
 *
 * The invariant is structural, never a word list: a command line's first token is
 * an executable the host actually has ([RemoteContext]'s PATH inventory), a shell
 * keyword/builtin, a path invocation, a variable, or an env assignment. An unknown
 * first token (an alias, a shell function, or the inventory not landed yet) is
 * still accepted when the line is *shaped* like an invocation — short, or carrying
 * the option/path/operator characters that command arguments live on and natural
 * sentences don't.
 */
object CommandGate {

    /** Whether [line] reads as a shell command against the host's [installed]
     *  inventory (empty set = inventory unknown, judge on shape alone). */
    fun isCommandLine(line: String, installed: Set<String>): Boolean {
        val tokens = line.trim().split(WS).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        // Subshell/negation wrappers are shell grammar: `(cd x && make)`, `! grep -q`.
        val first = tokens.first().trimStart('(', '!', '{')
        if (first.isEmpty()) return tokens.size > 1
        return when {
            ASSIGN.matches(first) -> true                     // VAR=value prefix
            first.startsWith("$") -> true                     // $EDITOR file
            first.startsWith("~") || first.startsWith(".") || '/' in first -> true // path invocation
            first in SHELL_WORDS -> true                      // keywords + builtins
            first in Specs.all -> true
            first in installed -> true
            else -> invocationShaped(first, tokens)
        }
    }

    /** Unknown first token: an alias/function, or no inventory to ask. Accept only
     *  lines shaped like invocations — sentences run long and carry none of the
     *  characters options, paths, assignments, and operators are made of. */
    private fun invocationShaped(first: String, tokens: List<String>): Boolean {
        if (!NAME.matches(first)) return false
        return tokens.size <= 3 ||
            tokens.any { t -> t.startsWith("-") || t.any { it in SHELL_CHARS } }
    }

    private val WS = Regex("\\s+")
    private val ASSIGN = Regex("[A-Za-z_][A-Za-z0-9_]*\\+?=.*")
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9._+-]{0,31}")

    // Deliberately no quotes and no dot: prose apostrophes ("don't") and sentence
    // periods must not read as shell.
    private const val SHELL_CHARS = "/=|&;<>$(){}[]*~`"

    /** Shell keywords and builtins the `ls $bindirs` inventory fallback can't see. */
    private val SHELL_WORDS = setOf(
        "cd", "export", "source", ".", "alias", "unalias", "set", "unset", "exec",
        "exit", "eval", "type", "command", "builtin", "history", "fg", "bg", "jobs",
        "wait", "read", "umask", "ulimit", "shift", "trap", "return", "local",
        "declare", "typeset", "let", "pushd", "popd", "dirs", "hash", "time", "times",
        "disown", "if", "then", "else", "elif", "fi", "for", "while", "until", "do",
        "done", "case", "esac", "function", "select", "[[", "[", "coproc",
    )
}

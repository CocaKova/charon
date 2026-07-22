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
 *
 * A qualifying first token is necessary but never sufficient. English is full of
 * words that are also programs — `make`, `test`, `find`, `read`, `do`, `if`, `let`,
 * `time`, `who`, `install` — so "make it more responsive" and "let me know when
 * that lands" clear the first-token check on a real host. The rest of the line is
 * therefore read too: a line with no shell evidence anywhere in it (no options, no
 * paths, no operators, no assignments) that carries the closed-class function words
 * every English sentence is built from is prose, whatever it opens with.
 */
object CommandGate {

    /** Whether [line] reads as a shell command against the host's [installed]
     *  inventory (empty set = inventory unknown, judge on shape alone). */
    fun isCommandLine(line: String, installed: Set<String>): Boolean {
        val tokens = line.trim().split(WS).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        return opensLikeCommand(tokens, installed) && !readsAsProse(tokens)
    }

    private fun opensLikeCommand(tokens: List<String>, installed: Set<String>): Boolean {
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

    /**
     * The whole-line read. Shell evidence anywhere — an option, a path, an operator,
     * an assignment — settles it as a command and ends the question; those characters
     * are what arguments are made of, and sentences don't carry them. Without any,
     * the line is bare words, and bare words are where prose and commands look alike:
     * a command's are names (`sudo apt install ripgrep`), a sentence's are held
     * together by function words and sentence punctuation.
     */
    private fun readsAsProse(tokens: List<String>): Boolean {
        if (tokens.size < 3) return false                     // too short to read either way
        if (isCompoundSkeleton(tokens)) return false          // `for f in a b c`, `case x in`
        if (tokens.any { hasShellEvidence(it) }) return false
        // The opening word already qualified as a command; judge it on its arguments.
        return tokens.drop(1).any { isProseMarker(it) } || tokens.size >= LONG_LINE
    }

    /** `for f in …` / `select x in …` / `case x in` — shell grammar whose bare words
     *  (`in`, loop variables) would otherwise read as English. */
    private fun isCompoundSkeleton(tokens: List<String>) =
        tokens.size >= 3 && tokens[2] == "in" &&
            tokens.first() in setOf("for", "select", "case")

    private fun hasShellEvidence(token: String) =
        token.startsWith("-") || ASSIGN.matches(token) || token.any { it in SHELL_CHARS }

    /** Marks of a sentence rather than an argument: a closed-class English word, a
     *  contraction, or sentence punctuation. Closed classes are finite and stable —
     *  this is the shape of the language, not a list of things Jonny happened to type. */
    private fun isProseMarker(token: String): Boolean {
        val word = token.trim(',', '.', '?', '!', ';', ':').lowercase()
        return token.last() in ",?!" ||
            APOSTROPHE.containsMatchIn(token) ||
            word in FUNCTION_WORDS
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
    private val APOSTROPHE = Regex("[A-Za-z][’'][A-Za-z]")

    /** Bare-word lines this long are prose even without a marker: commands that
     *  carry nothing but names run out well before here. */
    private const val LONG_LINE = 8

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

    /**
     * English function words — determiners, pronouns, prepositions, conjunctions,
     * auxiliaries. A closed class: the language stopped minting them centuries ago,
     * so this set doesn't grow with what anyone types. Sentences can't be built
     * without them; command arguments are names, paths, and flags, and essentially
     * never are them.
     *
     * Single letters (`a`, `i`) are left out even though they're English: they're
     * also ordinary short arguments (`cp a b`), and no sentence leans on them alone.
     */
    private val FUNCTION_WORDS = setOf(
        // determiners
        "an", "the", "this", "that", "these", "those", "some", "any", "every",
        "my", "your", "our", "their", "its", "his", "her",
        // pronouns
        "me", "you", "he", "she", "it", "we", "they", "him", "them", "us",
        "who", "whom", "whose", "what", "which", "myself", "yourself", "itself",
        // prepositions
        "of", "in", "on", "at", "to", "for", "with", "from", "by", "about", "into",
        "onto", "over", "under", "after", "before", "between", "through", "during",
        "without", "within", "against", "around", "like", "than",
        // conjunctions / subordinators
        "and", "or", "but", "so", "because", "if", "when", "while", "though",
        "although", "unless", "whether", "as", "since",
        // auxiliaries and copula
        "is", "are", "was", "were", "be", "been", "being", "am", "does", "did",
        "have", "has", "had", "can", "could", "should", "would", "will", "shall",
        "may", "might", "must", "not", "no",
        // degree and discourse
        "very", "too", "just", "really", "quite", "please", "thanks", "more", "most",
        "still", "again", "also", "maybe", "well", "now", "then",
    )
}

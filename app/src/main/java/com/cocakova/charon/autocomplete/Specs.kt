package com.cocakova.charon.autocomplete

/**
 * What a dynamic argument completes to — resolved live against the connected host
 * by [RemoteContext] (running tmux sessions, docker containers…), never hard-coded.
 *
 * [closedWorld] marks kinds whose probe answer IS the whole universe of valid
 * values (the sessions that run, the containers that exist): there, the live host
 * outranks and silences stale history. Open-world kinds (ssh targets) are only a
 * helpful subset — history stays a legitimate voice beside them.
 */
enum class ArgKind(val closedWorld: Boolean) {
    NONE(false),
    /** A file or directory on the host. Absolute and `~/` names always complete;
     *  bare names resolve against the shell's reported cwd (OSC 7) when there is
     *  one. Open-world: you name files that don't exist yet, too. */
    PATH(false),
    /** A directory only (`cd`) — same resolution as [PATH], files left out. */
    DIRECTORY(false),
    /** Names of running/attachable tmux sessions on the host. */
    TMUX_SESSION(true),
    /** Names of running docker containers. */
    DOCKER_CONTAINER(true),
    /** systemd service unit names. */
    SYSTEMD_UNIT(true),
    /** Host aliases from the remote's own ~/.ssh/config (you ssh onward *from* it). */
    SSH_HOST(false),
    /** Local branches of the repo the shell stands in (`branch -d`): the heads ARE
     *  the universe, so a deleted branch in history must not resurface. */
    GIT_LOCAL_BRANCH(true),
    /** Branches you can switch to: the local heads plus remote-tracking names with
     *  the remote stripped (`origin/feat` → `feat`, which checkout/switch create on
     *  the spot). Open-world: tags, SHAs, `HEAD~2` and files are valid here too. */
    GIT_BRANCH(false),
    /** Any branch ref — heads plus remote-tracking `origin/x` — for merge, rebase,
     *  log, diff… Open-world for the same reason as [GIT_BRANCH]. */
    GIT_REF(false),
    ;

    /** Completed from directory listings, not from a probe's value list. */
    val isPath: Boolean get() = this == PATH || this == DIRECTORY

    /** Answered per working directory — meaningless until the shell says where it is. */
    val needsCwd: Boolean get() = this == GIT_LOCAL_BRANCH || this == GIT_BRANCH || this == GIT_REF
}

/**
 * One level of a command's grammar: its subcommands, its flags, what a positional
 * argument at this level completes to, and which flags take a typed value.
 * Deliberately shallow — this is a phone's inline nudge, not a full CLI parser.
 *
 * [paths] says whether positionals here also name files on the host ([ArgKind.PATH]
 * or [ArgKind.DIRECTORY]), beside whatever [argKind] offers — `git checkout` takes
 * a branch *or* a file. A flag whose value is a file maps to PATH in [flagArgs]
 * (`ssh -i`); a flag whose value is anything else free-form maps to NONE, so the
 * position stays quiet instead of offering files or hosts (`scp -P 2222`).
 * Commands with no spec at all take files, the shell's own default, except the
 * few in Completer's NOT_FILES that plainly don't.
 */
class Spec(
    val name: String,
    val subs: List<Spec> = emptyList(),
    val flags: List<String> = emptyList(),
    val argKind: ArgKind = ArgKind.NONE,
    val flagArgs: Map<String, ArgKind> = emptyMap(),
    val paths: ArgKind = ArgKind.NONE,
)

/**
 * The built-in grammar for the commands a homelab hand actually types. Coverage is
 * intentionally curated: each entry earns its place by being something you'd reach
 * for at a phone keyboard. Argument *values* stay dynamic (ArgKind + RemoteContext);
 * only structure lives here.
 */
object Specs {
    private val tmuxTarget = mapOf("-t" to ArgKind.TMUX_SESSION)
    private val path = ArgKind.PATH

    val all: Map<String, Spec> = listOf(
        Spec(
            "tmux",
            subs = listOf(
                Spec("new", flags = listOf("-A", "-s", "-As"), flagArgs = mapOf("-s" to ArgKind.TMUX_SESSION, "-As" to ArgKind.TMUX_SESSION)),
                Spec("attach", flags = listOf("-t"), flagArgs = tmuxTarget, argKind = ArgKind.TMUX_SESSION),
                Spec("ls"),
                Spec("kill-session", flags = listOf("-t"), flagArgs = tmuxTarget),
                Spec("kill-server"),
                Spec("detach"),
                Spec("rename-session", flags = listOf("-t"), flagArgs = tmuxTarget),
            ),
        ),
        Spec(
            "git",
            subs = listOf(
                Spec("status"), Spec("pull"), Spec("push"), Spec("fetch"),
                Spec("add", flags = listOf("-A", "-p"), paths = path),
                Spec("commit", flags = listOf("-m", "-a", "--amend")),
                // A new branch's name is yours to invent: -b/-c values stay quiet.
                Spec(
                    "checkout", flags = listOf("-b"), argKind = ArgKind.GIT_BRANCH, paths = path,
                    flagArgs = mapOf("-b" to ArgKind.NONE, "-B" to ArgKind.NONE),
                ),
                Spec(
                    "switch", flags = listOf("-c"), argKind = ArgKind.GIT_BRANCH,
                    flagArgs = mapOf("-c" to ArgKind.NONE, "-C" to ArgKind.NONE),
                ),
                Spec(
                    "branch", flags = listOf("-a", "-d", "-D", "-m"),
                    flagArgs = listOf("-d", "-D", "-m").associateWith { ArgKind.GIT_LOCAL_BRANCH },
                ),
                Spec("log", flags = listOf("--oneline", "-p"), argKind = ArgKind.GIT_REF),
                Spec("diff", flags = listOf("--staged"), argKind = ArgKind.GIT_REF, paths = path),
                Spec("stash", subs = listOf(Spec("pop"), Spec("list"))),
                Spec("clone"),
                Spec("reset", flags = listOf("--hard", "--soft"), argKind = ArgKind.GIT_REF, paths = path),
                Spec("rebase", flags = listOf("-i", "--continue", "--abort"), argKind = ArgKind.GIT_REF),
                Spec("merge", flags = listOf("--no-ff", "--abort"), argKind = ArgKind.GIT_REF),
                Spec("restore", flags = listOf("--staged"), paths = path),
                Spec("rm", flags = listOf("--cached", "-r"), paths = path),
                Spec("mv", paths = path),
                Spec("remote", subs = listOf(Spec("-v"), Spec("add"), Spec("set-url"))),
                Spec("tag"), Spec("cherry-pick", argKind = ArgKind.GIT_REF), Spec("show", argKind = ArgKind.GIT_REF),
                Spec("worktree", subs = listOf(Spec("add"), Spec("list"), Spec("remove"))),
            ),
        ),
        Spec(
            "docker",
            subs = listOf(
                Spec("ps", flags = listOf("-a")),
                Spec("images"),
                Spec("logs", flags = listOf("-f", "--tail"), argKind = ArgKind.DOCKER_CONTAINER),
                Spec("exec", flags = listOf("-it"), argKind = ArgKind.DOCKER_CONTAINER),
                Spec("restart", argKind = ArgKind.DOCKER_CONTAINER),
                Spec("stop", argKind = ArgKind.DOCKER_CONTAINER),
                Spec("start", argKind = ArgKind.DOCKER_CONTAINER),
                Spec("rm", argKind = ArgKind.DOCKER_CONTAINER),
                Spec("inspect", argKind = ArgKind.DOCKER_CONTAINER),
                Spec("stats"),
                Spec(
                    "compose",
                    subs = listOf(
                        Spec("up", flags = listOf("-d", "--build")), Spec("down"),
                        Spec("ps"), Spec("logs", flags = listOf("-f")), Spec("restart"), Spec("pull"),
                        Spec("exec"), Spec("build"),
                    ),
                ),
                Spec("pull"), Spec("build", flags = listOf("-t")), Spec("system", subs = listOf(Spec("prune"), Spec("df"))),
            ),
        ),
        Spec(
            "systemctl",
            subs = listOf(
                Spec("status", argKind = ArgKind.SYSTEMD_UNIT),
                Spec("start", argKind = ArgKind.SYSTEMD_UNIT),
                Spec("stop", argKind = ArgKind.SYSTEMD_UNIT),
                Spec("restart", argKind = ArgKind.SYSTEMD_UNIT),
                Spec("enable", argKind = ArgKind.SYSTEMD_UNIT),
                Spec("disable", argKind = ArgKind.SYSTEMD_UNIT),
                Spec("daemon-reload"),
                Spec("is-active", argKind = ArgKind.SYSTEMD_UNIT),
                Spec("cat", argKind = ArgKind.SYSTEMD_UNIT),
                Spec("list-units", flags = listOf("--failed", "--type=service")),
                Spec("list-timers"),
            ),
        ),
        Spec(
            "journalctl",
            flags = listOf("-u", "-f", "-e", "-b", "--since", "-n"),
            flagArgs = mapOf("-u" to ArgKind.SYSTEMD_UNIT),
        ),
        Spec(
            "apt",
            subs = listOf(
                Spec("update"), Spec("upgrade", flags = listOf("-y")), Spec("install", flags = listOf("-y")),
                Spec("remove"), Spec("autoremove"), Spec("search"), Spec("list", flags = listOf("--installed", "--upgradable")),
            ),
        ),
        Spec("ssh", flags = listOf("-p", "-i", "-L", "-R", "-D"), argKind = ArgKind.SSH_HOST, flagArgs = mapOf("-i" to path)),
        // scp/rsync move files between here and there: a host *or* a local path.
        Spec(
            "scp", flags = listOf("-r", "-P"), argKind = ArgKind.SSH_HOST, paths = path,
            flagArgs = mapOf("-P" to ArgKind.NONE, "-i" to path),
        ),
        Spec(
            "sftp", flags = listOf("-P", "-i"), argKind = ArgKind.SSH_HOST,
            flagArgs = mapOf("-P" to ArgKind.NONE, "-i" to path),
        ),
        Spec("rsync", flags = listOf("-avz", "-a", "--progress", "--delete", "-n"), argKind = ArgKind.SSH_HOST, paths = path),
        Spec("ping", flags = listOf("-c")),
        Spec("curl", flags = listOf("-s", "-L", "-o", "-X", "-H", "-d"), flagArgs = mapOf("-o" to path)),
        Spec("wget", flags = listOf("-O", "-q"), flagArgs = mapOf("-O" to path)),
        Spec(
            "tar", flags = listOf("-xzf", "-czf", "-tf", "-xf"), paths = path,
            flagArgs = listOf("-xzf", "-czf", "-tf", "-xf").associateWith { path },
        ),
        Spec("grep", flags = listOf("-r", "-i", "-n", "-v", "-E"), paths = path),
        Spec(
            "find", flags = listOf("-name", "-type", "-mtime"), paths = ArgKind.DIRECTORY,
            flagArgs = listOf("-name", "-type", "-mtime").associateWith { ArgKind.NONE },
        ),
        Spec("chmod", flags = listOf("-R"), paths = path),
        Spec("chown", flags = listOf("-R"), paths = path),
        Spec("du", flags = listOf("-sh"), paths = path),
        Spec("df", flags = listOf("-h")),
        Spec("free", flags = listOf("-h")),
        Spec("ls", flags = listOf("-la", "-lah", "-lt"), paths = path),
        Spec("cd", paths = ArgKind.DIRECTORY),
        Spec("ip", subs = listOf(Spec("a"), Spec("route"), Spec("link"))),
        Spec("kill", flags = listOf("-9")),
        Spec("pip", subs = listOf(Spec("install"), Spec("list"), Spec("freeze"))),
        Spec("npm", subs = listOf(Spec("install"), Spec("run"), Spec("start"), Spec("test"), Spec("ci"))),
        Spec(
            "nvidia-smi",
            flags = listOf("-l"),
        ),
    ).associateBy { it.name }
}

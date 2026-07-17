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
    /** Names of running/attachable tmux sessions on the host. */
    TMUX_SESSION(true),
    /** Names of running docker containers. */
    DOCKER_CONTAINER(true),
    /** systemd service unit names. */
    SYSTEMD_UNIT(true),
    /** Host aliases from the remote's own ~/.ssh/config (you ssh onward *from* it). */
    SSH_HOST(false),
}

/**
 * One level of a command's grammar: its subcommands, its flags, what a positional
 * argument at this level completes to, and which flags take a typed value.
 * Deliberately shallow — this is a phone's inline nudge, not a full CLI parser.
 */
class Spec(
    val name: String,
    val subs: List<Spec> = emptyList(),
    val flags: List<String> = emptyList(),
    val argKind: ArgKind = ArgKind.NONE,
    val flagArgs: Map<String, ArgKind> = emptyMap(),
)

/**
 * The built-in grammar for the commands a homelab hand actually types. Coverage is
 * intentionally curated: each entry earns its place by being something you'd reach
 * for at a phone keyboard. Argument *values* stay dynamic (ArgKind + RemoteContext);
 * only structure lives here.
 */
object Specs {
    private val tmuxTarget = mapOf("-t" to ArgKind.TMUX_SESSION)

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
                Spec("add", flags = listOf("-A", "-p")), Spec("commit", flags = listOf("-m", "-a", "--amend")),
                Spec("checkout", flags = listOf("-b")), Spec("switch", flags = listOf("-c")),
                Spec("branch", flags = listOf("-a", "-d")), Spec("log", flags = listOf("--oneline", "-p")),
                Spec("diff", flags = listOf("--staged")), Spec("stash", subs = listOf(Spec("pop"), Spec("list"))),
                Spec("clone"), Spec("reset", flags = listOf("--hard", "--soft")), Spec("rebase"),
                Spec("remote", subs = listOf(Spec("-v"), Spec("add"), Spec("set-url"))),
                Spec("tag"), Spec("cherry-pick"), Spec("show"),
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
        Spec("ssh", flags = listOf("-p", "-i", "-L", "-R", "-D"), argKind = ArgKind.SSH_HOST),
        Spec("scp", flags = listOf("-r", "-P"), argKind = ArgKind.SSH_HOST),
        Spec("sftp", flags = listOf("-P", "-i"), argKind = ArgKind.SSH_HOST),
        Spec("rsync", flags = listOf("-avz", "-a", "--progress", "--delete", "-n"), argKind = ArgKind.SSH_HOST),
        Spec("ping", flags = listOf("-c")),
        Spec("curl", flags = listOf("-s", "-L", "-o", "-X", "-H", "-d")),
        Spec("wget", flags = listOf("-O", "-q")),
        Spec("tar", flags = listOf("-xzf", "-czf", "-tf", "-xf")),
        Spec("grep", flags = listOf("-r", "-i", "-n", "-v", "-E")),
        Spec("find", flags = listOf("-name", "-type", "-mtime")),
        Spec("chmod", flags = listOf("-R")),
        Spec("chown", flags = listOf("-R")),
        Spec("du", flags = listOf("-sh")),
        Spec("df", flags = listOf("-h")),
        Spec("free", flags = listOf("-h")),
        Spec("ls", flags = listOf("-la", "-lah", "-lt")),
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

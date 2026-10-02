package com.cocakova.charon.cargo

/**
 * The lading's state, as one small machine with no Android in it — so every way a
 * crossing with cargo can end is written down and tested.
 *
 * Why it exists: the barge used to read *what was on screen*, not *what had just
 * arrived*. An install's last verb lines ("Setting up…", "Processing triggers…")
 * stay in the bottom rows after the prompt returns, so every glean found them
 * again and kept the barge sailing; the watch only ended on ^C, an alt-screen
 * flip, a new cargo command, or a full minute of a silent wire — and a wire is
 * never silent with tmux's clock, a redrawing prompt, or your own typing echoing
 * back. The barge stayed out on the water over a finished install, frozen at the
 * last percent apt had shown before it erased its own progress bar.
 *
 * Now a sighting counts only when it is **fresh**: a cargo-bearing row that was
 * not there on the previous glean. Every end leads to a dock or to nothing:
 *
 *  - the command finishes (OSC 133 D from a rigged shell): **ashore**, or
 *    **aground** with the exit code, for a beat — then gone;
 *  - OSC 9;4 progress (any program) steers the percent and its own `remove` /
 *    `error` states dock or ground it the same way;
 *  - ^C, the alternate screen, the transport dropping, a reconnect, or a new
 *    command typed after cargo stopped moving: gone at once;
 *  - nothing fresh for [SAFETY_NET_NS]: disarmed — a net under the rules above,
 *    never the rule itself.
 *
 * Not thread-safe; [com.cocakova.charon.ssh.TerminalSession] holds its lock around
 * every call. Times are nanoTime values passed in, so tests own the clock.
 */
class CargoWatch {

    enum class Phase { SAILING, ASHORE, AGROUND }

    /**
     * What the strip shows. [still] = nothing fresh lately: the water stops moving.
     * [until] is the nanoTime this view stops being true on its own (an ending's
     * beat, a sighting's freshness) — a strip read by a UI that hasn't ticked
     * lately must not show what has already expired.
     */
    data class View(
        val phase: Phase,
        val manager: String,
        val item: String?,
        val percent: Int?,
        val still: Boolean,
        val exit: Int? = null,
        val until: Long = Long.MAX_VALUE,
    )

    /** The manager whose cargo is being watched, or null when disarmed. */
    var manager: String? = null
        private set

    /** Set by the session: a rigged shell's command is still running. While it is,
     *  a quiet stretch inside the install (a long postinst) holds the barge still
     *  instead of hiding it — the D mark is coming, and it will say how it ended. */
    var voyageRunning = false

    private var item: String? = null
    private var percent: Int? = null
    private var percentAt = 0L
    private var armedAt = 0L
    private var freshAt = 0L
    /** Cargo has been sighted at least once since arming (freshAt means something). */
    private var sighted = false
    private var seen: Set<String> = emptySet()

    /** OSC 9;4 is steering: its percent is the truth until it says remove. */
    private var steered = false
    private var steeredState = 0

    private var ending: Phase? = null
    private var endAt = 0L
    private var endExit: Int? = null
    private var endManager = ""
    private var endItem: String? = null

    val armed: Boolean get() = manager != null

    /** True while the strip has anything left to show — the UI ticks only then. */
    fun awake(now: Long): Boolean = armed || endingShown(now)

    /** A submitted command invoked a package manager. */
    fun arm(manager: String, now: Long) {
        clearCargo()
        ending = null
        this.manager = manager
        armedAt = now
    }

    /**
     * One glean of the bottom rows. Only rows that were not there last time count:
     * the install's tail sitting above a returned prompt is history, not cargo.
     */
    fun glean(g: CargoLading.Glean, now: Long, seedOnly: Boolean = false) {
        if (!armed) return
        val fresh = g.rows.any { it !in seen }
        seen = g.rows.toHashSet()
        // Arming reads what is already on screen as seen, never as sighted: the last
        // install's tail must not sail the next one's barge before it has begun.
        if (!fresh || seedOnly) return
        freshAt = now
        sighted = true
        g.item?.let { item = it }
        if (!steered && g.percent != null) {
            percent = g.percent
            percentAt = now
        }
    }

    /**
     * OSC 9;4 (ConEmu/Windows Terminal progress): state 0 remove, 1 set, 2 error,
     * 3 indeterminate, 4 paused/warning. Any program may raise it, so it arms the
     * watch on its own when no package manager did.
     */
    fun progress(state: Int, value: Int?, now: Long) {
        when (state) {
            0 -> {
                if (!armed || !steered) return
                when {
                    (percent ?: 0) >= 100 -> finish(Phase.ASHORE, null, now)
                    // Removed short of full: a program's own bar we can't call done —
                    // it goes; a package manager's watch falls back to its glean.
                    manager == PROGRESS_MANAGER -> abandon()
                    else -> {
                        steered = false
                        steeredState = 0
                    }
                }
            }
            1, 2, 3, 4 -> {
                if (!armed) arm(PROGRESS_MANAGER, now)
                steered = true
                steeredState = state
                freshAt = now
                sighted = true
                if (state == 3) {
                    percent = null
                } else if (value != null) {
                    percent = value.coerceIn(0, 100)
                    percentAt = now
                }
                if (state == 2) {
                    // An error is an end in its own right: the program said so.
                    finish(Phase.AGROUND, null, now)
                }
            }
        }
    }

    /**
     * The command finished: a rigged shell's D mark. A watch whose cargo never
     * moved (a `sudo apt install` cancelled at the password) simply ends.
     */
    fun commandDone(exit: Int?, now: Long) {
        if (!armed) return
        if (!sighted) {
            disarm()
            return
        }
        finish(if (exit == null || exit == 0) Phase.ASHORE else Phase.AGROUND, exit, now)
    }

    /**
     * A new line went to the shell. Short answers are what an install asks for
     * (`[Y/n]`, a debconf number) and leave the watch alone; anything else typed
     * once the cargo has stopped moving means the install is behind us.
     */
    fun lineSubmitted(line: String, now: Long) {
        if (!armed) return
        val t = line.trim().lowercase()
        if (t.length <= 3 || t == "yes" || t == "no") return
        if (!fresh(now)) disarm()
    }

    /** ^C, the alternate screen, a dropped transport, a redial: gone at once. */
    fun abandon() {
        disarm()
        ending = null
    }

    /** Time passes: endings expire and the safety net checks for a forgotten watch. */
    fun tick(now: Long) {
        if (ending != null && !endingShown(now)) ending = null
        if (armed && now - (if (sighted) maxOf(armedAt, freshAt) else armedAt) > SAFETY_NET_NS) disarm()
    }

    fun view(now: Long): View? {
        val e = ending
        if (e != null && endingShown(now)) {
            return View(
                phase = e,
                manager = endManager,
                item = endItem,
                percent = if (e == Phase.ASHORE) 100 else percent,
                still = true,
                exit = endExit,
                until = endAt + if (e == Phase.ASHORE) ASHORE_NS else AGROUND_NS,
            )
        }
        val m = manager ?: return null
        // apt erases its progress bar when it is done; a percent no glean has
        // re-read lately is gone from the screen, so patrol instead of freezing.
        val pct = percent?.takeIf { steered || now - percentAt < PERCENT_STALE_NS }
        if (!sighted) return null
        val fresh = fresh(now)
        return when {
            fresh -> View(Phase.SAILING, m, item, pct, still = false, until = freshAt + FRESH_NS)
            voyageRunning || (steered && steeredState != 0) -> View(Phase.SAILING, m, item, pct, still = true)
            else -> null
        }
    }

    private fun fresh(now: Long) = sighted && now - freshAt < FRESH_NS

    private fun endingShown(now: Long): Boolean {
        val e = ending ?: return false
        return now - endAt < if (e == Phase.ASHORE) ASHORE_NS else AGROUND_NS
    }

    private fun finish(phase: Phase, exit: Int?, now: Long) {
        endManager = manager ?: return
        endItem = item
        endExit = exit
        ending = phase
        endAt = now
        val keep = percent
        disarm()
        percent = keep
    }

    private fun disarm() {
        manager = null
        clearCargo()
    }

    private fun clearCargo() {
        item = null
        percent = null
        percentAt = 0L
        freshAt = 0L
        sighted = false
        armedAt = 0L
        seen = emptySet()
        steered = false
        steeredState = 0
        voyageRunning = false
    }

    companion object {
        /** A sighting keeps the barge sailing this long. */
        const val FRESH_NS = 4_000_000_000L

        /** A percent not re-read for this long has left the screen. */
        const val PERCENT_STALE_NS = 3_000_000_000L

        /** How long the docked barge stays after a clean finish. */
        const val ASHORE_NS = 1_600_000_000L

        /** How long the grounded barge stays after a failure. */
        const val AGROUND_NS = 2_600_000_000L

        /** The safety net: an armed watch with nothing fresh for this long disarms. */
        const val SAFETY_NET_NS = 120_000_000_000L

        /** The manager named for progress a program reported itself (OSC 9;4). */
        const val PROGRESS_MANAGER = "progress"
    }
}

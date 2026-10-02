package com.cocakova.charon.terminal

/**
 * A seam in the wake: where a dropped crossing was re-made. Hung on the line the
 * cursor stood on when the redial landed (on its top edge when the cursor was at
 * the start of the line, its bottom edge when the old prompt shared the line), so
 * scrolling back shows exactly where the old shell ends and the new one begins.
 */
class Seam(
    /** Wall-clock millis when the crossing was re-made. */
    val atMillis: Long,
    /** How long the crossing was adrift. */
    val adriftMs: Long,
    /** Draw along the line's bottom edge rather than its top. */
    val below: Boolean,
)

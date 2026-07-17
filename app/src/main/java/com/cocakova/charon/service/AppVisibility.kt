package com.cocakova.charon.service

/**
 * Whether any Charon UI is on screen — flipped by MainActivity's onStart/onStop.
 * The horn only sounds while this is false: a push about a finished command is
 * noise when you're already looking at the terminal.
 */
object AppVisibility {
    @Volatile var visible: Boolean = false
}

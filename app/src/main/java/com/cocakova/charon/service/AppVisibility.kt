package com.cocakova.charon.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether any Charon UI is on screen — flipped by MainActivity's onStart/onStop.
 * The horn only sounds while this is false: a push about a finished command is
 * noise when you're already looking at the terminal. Live crossings also tune
 * their keepalive heartbeat off this — fast while you're watching, slow while
 * the phone is pocketed (SessionManager collects [foreground]).
 */
object AppVisibility {
    val foreground = MutableStateFlow(false)

    var visible: Boolean
        get() = foreground.value
        set(value) {
            foreground.value = value
        }
}

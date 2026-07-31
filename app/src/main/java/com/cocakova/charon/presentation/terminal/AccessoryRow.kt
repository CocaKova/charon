package com.cocakova.charon.presentation.terminal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.cocakova.charon.terminal.input.KeyEncoder
import com.cocakova.charon.theme.CharonMono
import com.cocakova.charon.theme.Styx

/** Sticky modifier state: off, armed for one keystroke, or latched on. */
enum class Sticky { OFF, ARMED, LOCKED }

/**
 * The key row terminals on phones live or die by — Charon's flagship. Beyond the
 * essentials: **sticky modifiers** (tap Ctrl/Alt to arm for one keystroke, long-
 * press to latch — teal = armed, obol-gold = locked), **long-press variants** on
 * keys (tab → back-tab, symbols → their shifted mates), **auto-repeat** arrows
 * (hold to repeat), and an **Fn page** (F1–F12) you toggle into. The row scrolls
 * horizontally; the theme carries through — JetBrains Mono, sink-and-spring press,
 * teal ripple. See docs/INPUT.md.
 */
@Composable
fun AccessoryRow(
    ctrl: Sticky,
    onCtrl: () -> Unit,
    onCtrlLock: () -> Unit,
    alt: Sticky,
    onAlt: () -> Unit,
    onAltLock: () -> Unit,
    onKey: (KeyEncoder.Key) -> Unit,
    onText: (String) -> Unit,
    onPaste: () -> Unit,
    rawInput: Boolean,
    onToggleInputMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var fnPage by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccessoryKey("esc") { onKey(KeyEncoder.Key.ESCAPE) }
        // tab; long-press = back-tab (shift-tab).
        AccessoryKey("tab", onLongPress = { onKey(KeyEncoder.Key.BACK_TAB) }) {
            onKey(KeyEncoder.Key.TAB)
        }
        StickyKey("ctrl", ctrl, onTap = onCtrl, onLock = onCtrlLock)
        StickyKey("alt", alt, onTap = onAlt, onLock = onAltLock)

        GroupGap()

        if (!fnPage) {
            // Arrows auto-repeat on hold; that reads more naturally than a variant.
            AccessoryKey("↑", repeatable = true) { onKey(KeyEncoder.Key.UP) }
            AccessoryKey("↓", repeatable = true) { onKey(KeyEncoder.Key.DOWN) }
            AccessoryKey("←", repeatable = true) { onKey(KeyEncoder.Key.LEFT) }
            AccessoryKey("→", repeatable = true) { onKey(KeyEncoder.Key.RIGHT) }
            // Symbols; long-press = the shifted mate the soft keyboard buries.
            AccessoryKey("-", onLongPress = { onText("_") }) { onText("-") }
            AccessoryKey("/", onLongPress = { onText("\\") }) { onText("/") }
            AccessoryKey("|", onLongPress = { onText("`") }) { onText("|") }
            AccessoryKey("~", onLongPress = { onText("^") }) { onText("~") }
            AccessoryKey("home") { onKey(KeyEncoder.Key.HOME) }
            AccessoryKey("end") { onKey(KeyEncoder.Key.END) }
            AccessoryKey("pgup", repeatable = true) { onKey(KeyEncoder.Key.PAGE_UP) }
            AccessoryKey("pgdn", repeatable = true) { onKey(KeyEncoder.Key.PAGE_DOWN) }
        } else {
            for ((label, key) in FUNCTION_KEYS) AccessoryKey(label) { onKey(key) }
        }

        GroupGap()

        AccessoryKey("paste") { onPaste() }
        // Fn: swaps the middle of the row to the F-key page. Gold while on it.
        AccessoryKey("fn", highlighted = fnPage, highlightColor = Styx.coin) {
            fnPage = !fnPage
        }
        // Input-mode toggle: predictive (swipe/suggestions) vs raw key events.
        // Gold when raw — you've stepped off the charted water.
        AccessoryKey(
            if (rawInput) "raw" else "abc",
            highlighted = rawInput,
            highlightColor = Styx.coin,
        ) { onToggleInputMode() }
    }
}

private val FUNCTION_KEYS = listOf(
    "F1" to KeyEncoder.Key.F1, "F2" to KeyEncoder.Key.F2, "F3" to KeyEncoder.Key.F3,
    "F4" to KeyEncoder.Key.F4, "F5" to KeyEncoder.Key.F5, "F6" to KeyEncoder.Key.F6,
    "F7" to KeyEncoder.Key.F7, "F8" to KeyEncoder.Key.F8, "F9" to KeyEncoder.Key.F9,
    "F10" to KeyEncoder.Key.F10, "F11" to KeyEncoder.Key.F11, "F12" to KeyEncoder.Key.F12,
)

/**
 * Group separation by whitespace rather than a hairline — a wider breath than the
 * gap between adjacent keys, so modifiers / navigation / actions read as clusters
 * without a rule drawn between them. Cleaner than the old 1px divider.
 */
@Composable
private fun GroupGap() {
    Spacer(Modifier.width(14.dp))
}

/**
 * A modifier key with three states. Tap cycles arm/off; long-press latches or
 * releases the lock. Teal = armed (charged, one shot), obol-gold = locked.
 */
@Composable
private fun StickyKey(
    label: String,
    state: Sticky,
    onTap: () -> Unit,
    onLock: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    KeyPill(
        label = label,
        container = when (state) {
            Sticky.OFF -> MaterialTheme.colorScheme.surfaceVariant
            Sticky.ARMED -> Styx.water
            Sticky.LOCKED -> Styx.coin
        },
        content = if (state == Sticky.OFF) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onPrimary,
        bold = state != Sticky.OFF,
        modifier = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(color = Styx.water),
            onClick = {
                // Arming reads as switching something on; clearing as off.
                haptic.performHapticFeedback(
                    if (state == Sticky.OFF) HapticFeedbackType.ToggleOn
                    else HapticFeedbackType.ToggleOff,
                )
                onTap()
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLock()
            },
        ),
    )
}

@Composable
private fun AccessoryKey(
    label: String,
    highlighted: Boolean = false,
    highlightColor: Color = Styx.water,
    repeatable: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        targetValue = if (highlighted) highlightColor else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(160),
        label = "keyColor",
    )
    val content by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(160),
        label = "keyContent",
    )

    // Repeatable keys drive the auto-repeat from a LaunchedEffect keyed on a pressed
    // flag the gesture toggles. The gesture is swipe-proof without being tap-hostile:
    // a tap fires on the up-stroke as long as the pointer stayed on the key — thumbs
    // roll 15–25px on fast taps and a keyboard that drops rolled taps feels broken,
    // so drift *within* the key never cancels. What does cancel is structural: the
    // pointer leaving the key (a glide crossing the row, or the key sliding away
    // beneath a stationary finger), or the row genuinely taking the pointer for a
    // scroll — measured as accrued consumed displacement, because merely stopping a
    // fling consumes the micro-moves of a perfectly honest tap.
    val haptic = LocalHapticFeedback.current
    var held by remember { mutableStateOf(false) }
    var holdFired by remember { mutableStateOf(false) }
    // The repeatable gesture below is keyed on Unit so it survives recomposition —
    // which means it captures its lambdas exactly once. onPress closes over the
    // active session, and a tab switch hands this key a new one: read it through
    // rememberUpdatedState or every arrow keeps steering the ferry you stepped off.
    val currentOnPress by rememberUpdatedState(onPress)
    if (repeatable) {
        LaunchedEffect(held) {
            if (held) {
                delay(400)
                // Commit point: the hold is real. One click, then silent repeats so a
                // held arrow doesn't turn the phone into a buzzer.
                holdFired = true
                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                while (true) {
                    currentOnPress()
                    delay(60)
                }
            }
        }
    }

    val press = Modifier.when_(repeatable) {
        pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                holdFired = false
                held = true
                val margin = 12.dp.toPx()
                var scrolled = 0f
                var fire = false
                while (true) {
                    // Watch in the Final pass so the scroll row's consumption is
                    // visible — a horizontal fling scrolls keys under a stationary
                    // finger without ever breaking slop locally.
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (ch.isConsumed) {
                        // Only a scroll that actually moved cancels; a fling-stop
                        // consumes a tap's micro-moves with next to no displacement.
                        scrolled += ch.positionChangeIgnoreConsumed().getDistance()
                        if (scrolled > viewConfiguration.touchSlop) break
                    }
                    // Position is in the key's own space, so this also catches the
                    // key sliding out from beneath a stationary finger.
                    val p = ch.position
                    if (p.x < -margin || p.y < -margin ||
                        p.x > size.width + margin || p.y > size.height + margin
                    ) {
                        break
                    }
                    if (!ch.pressed) {
                        fire = true
                        break
                    }
                }
                held = false
                if (fire && !holdFired) {
                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    currentOnPress()
                }
            }
        }
    }.when_(!repeatable) {
        combinedClickable(
            interactionSource = interaction,
            indication = ripple(color = Styx.water),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onPress()
            },
            onLongClick = onLongPress?.let {
                {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    it()
                }
            },
        )
    }

    KeyPill(
        label = label,
        container = container,
        content = content,
        bold = highlighted,
        interaction = interaction,
        forcePressed = held,
        modifier = press,
    )
}

/**
 * Shared pill visual: sink-and-spring scale on press, JetBrains Mono label. Every
 * pill honours a uniform [minWidth] so single-glyph keys (arrows, symbols) square up
 * into the same grid rhythm as the word keys — the row reads as one designed
 * keyboard, not a ragged scroll. Wide labels grow past it on their own.
 */
@Composable
private fun KeyPill(
    label: String,
    container: Color,
    content: Color,
    bold: Boolean,
    modifier: Modifier = Modifier,
    minWidth: Dp = 42.dp,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    forcePressed: Boolean = false,
) {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed || forcePressed) 0.88f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "keyScale",
    )
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(38.dp)
            .widthIn(min = minWidth)
            .clip(RoundedCornerShape(9.dp))
            .background(container)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = CharonMono,
            fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** Apply [block] to the modifier only when [cond]; keeps the builder readable. */
private inline fun Modifier.when_(cond: Boolean, block: Modifier.() -> Modifier): Modifier =
    if (cond) block() else this

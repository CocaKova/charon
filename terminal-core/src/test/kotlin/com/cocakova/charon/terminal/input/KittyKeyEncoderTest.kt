package com.cocakova.charon.terminal.input

import com.cocakova.charon.terminal.input.KeyEncoder.ExtraKey
import com.cocakova.charon.terminal.input.KeyEncoder.Key
import com.cocakova.charon.terminal.input.KeyEncoder.KeyAction
import com.cocakova.charon.terminal.input.KeyEncoder.MOD_ALT
import com.cocakova.charon.terminal.input.KeyEncoder.MOD_CAPS_LOCK
import com.cocakova.charon.terminal.input.KeyEncoder.MOD_CTRL
import com.cocakova.charon.terminal.input.KeyEncoder.MOD_NUM_LOCK
import com.cocakova.charon.terminal.input.KeyEncoder.MOD_SHIFT
import com.cocakova.charon.terminal.input.KeyEncoder.MOD_SUPER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kitty keyboard protocol encoding table. Expected strings come from the spec's
 * examples and kitty's own encoder (key_encoding.c), which is what client libraries
 * are tested against.
 */
class KittyKeyEncoderTest {
    private val E = "\u001B"

    private val DIS = KeyEncoder.KITTY_DISAMBIGUATE
    private val EVT = KeyEncoder.KITTY_EVENT_TYPES
    private val ALT_KEYS = KeyEncoder.KITTY_ALTERNATE_KEYS
    private val ALL = KeyEncoder.KITTY_ALL_KEYS_AS_ESCAPES
    private val TEXT = KeyEncoder.KITTY_ASSOCIATED_TEXT

    private fun fk(key: Key, mods: Int = 0, flags: Int = DIS, action: KeyAction = KeyAction.PRESS) =
        KeyEncoder.encodeKitty(key, mods, flags, action)

    private fun tk(
        ch: Char, mods: Int = 0, flags: Int = DIS, text: String? = null,
        shifted: Char? = null, base: Char? = null, action: KeyAction = KeyAction.PRESS,
    ) = KeyEncoder.encodeKittyText(
        ch.code, mods, flags, text = text,
        shifted = shifted?.code ?: 0, baseLayout = base?.code ?: 0, action = action,
    )

    // ------------------------------------------------------------- activation

    @Test
    fun onlyEncodingBitsActivateTheProtocol() {
        assertFalse(KeyEncoder.kittyActive(0))
        assertTrue(KeyEncoder.kittyActive(DIS))
        assertTrue(KeyEncoder.kittyActive(EVT))
        assertTrue(KeyEncoder.kittyActive(ALL))
        // Alternate keys / associated text only reshape escapes the others produce.
        assertFalse(KeyEncoder.kittyActive(ALT_KEYS))
        assertFalse(KeyEncoder.kittyActive(TEXT))
        assertFalse(KeyEncoder.kittyActive(ALT_KEYS or TEXT))
    }

    // ------------------------------------------------------------- flag 1

    @Test
    fun escapeIsDisambiguated() {
        assertEquals("$E[27u", fk(Key.ESCAPE))
        assertEquals("$E[27;3u", fk(Key.ESCAPE, MOD_ALT))
        assertEquals("$E[27;2u", fk(Key.ESCAPE, MOD_SHIFT))
    }

    @Test
    fun enterTabBackspaceStayLegacyUnmodifiedSoResetCanBeTyped() {
        assertEquals("\r", fk(Key.ENTER))
        assertEquals("\t", fk(Key.TAB))
        assertEquals("\u007F", fk(Key.BACKSPACE))
        // Lock modifiers don't count as modified.
        assertEquals("\r", fk(Key.ENTER, MOD_NUM_LOCK or MOD_CAPS_LOCK))
    }

    @Test
    fun enterTabBackspaceWithModifiersBecomeCsiU() {
        assertEquals("$E[13;2u", fk(Key.ENTER, MOD_SHIFT))
        assertEquals("$E[13;5u", fk(Key.ENTER, MOD_CTRL))
        assertEquals("$E[13;3u", fk(Key.ENTER, MOD_ALT))
        assertEquals("$E[9;5u", fk(Key.TAB, MOD_CTRL))
        assertEquals("$E[127;5u", fk(Key.BACKSPACE, MOD_CTRL))
        assertEquals("$E[127;3u", fk(Key.BACKSPACE, MOD_ALT))
    }

    @Test
    fun shiftTabIsTabWithShift() {
        assertEquals("$E[9;2u", fk(Key.TAB, MOD_SHIFT))
        assertEquals("$E[9;2u", fk(Key.BACK_TAB))
        assertEquals("$E[9;6u", fk(Key.BACK_TAB, MOD_CTRL))
    }

    @Test
    fun cursorKeysIgnoreDecckmOnceEnhanced() {
        assertEquals("$E[A", fk(Key.UP))
        assertEquals("$E[B", fk(Key.DOWN))
        assertEquals("$E[C", fk(Key.RIGHT))
        assertEquals("$E[D", fk(Key.LEFT))
        assertEquals("$E[H", fk(Key.HOME))
        assertEquals("$E[F", fk(Key.END))
    }

    @Test
    fun modifiedCursorKeysCarryTheOneAndTheMods() {
        assertEquals("$E[1;2A", fk(Key.UP, MOD_SHIFT))
        assertEquals("$E[1;5D", fk(Key.LEFT, MOD_CTRL))
        assertEquals("$E[1;3C", fk(Key.RIGHT, MOD_ALT))
        assertEquals("$E[1;8B", fk(Key.DOWN, MOD_SHIFT or MOD_ALT or MOD_CTRL))
        assertEquals("$E[1;9H", fk(Key.HOME, MOD_SUPER))
        assertEquals("$E[1;2F", fk(Key.END, MOD_SHIFT))
    }

    @Test
    fun tildeKeys() {
        assertEquals("$E[2~", fk(Key.INSERT))
        assertEquals("$E[3~", fk(Key.DELETE))
        assertEquals("$E[5~", fk(Key.PAGE_UP))
        assertEquals("$E[6~", fk(Key.PAGE_DOWN))
        assertEquals("$E[3;5~", fk(Key.DELETE, MOD_CTRL))
        assertEquals("$E[6;2~", fk(Key.PAGE_DOWN, MOD_SHIFT))
    }

    @Test
    fun functionKeys() {
        assertEquals("$E[P", fk(Key.F1))
        assertEquals("$E[Q", fk(Key.F2))
        assertEquals("$E[13~", fk(Key.F3)) // never CSI R: that's a cursor report
        assertEquals("$E[S", fk(Key.F4))
        assertEquals("$E[15~", fk(Key.F5))
        assertEquals("$E[17~", fk(Key.F6))
        assertEquals("$E[18~", fk(Key.F7))
        assertEquals("$E[19~", fk(Key.F8))
        assertEquals("$E[20~", fk(Key.F9))
        assertEquals("$E[21~", fk(Key.F10))
        assertEquals("$E[23~", fk(Key.F11))
        assertEquals("$E[24~", fk(Key.F12))
        assertEquals("$E[1;5P", fk(Key.F1, MOD_CTRL))
        assertEquals("$E[13;2~", fk(Key.F3, MOD_SHIFT))
        assertEquals("$E[24;3~", fk(Key.F12, MOD_ALT))
    }

    @Test
    fun lockModifiersAreReportedOnEscapes() {
        assertEquals("$E[1;133A", fk(Key.UP, MOD_CTRL or MOD_NUM_LOCK))
        assertEquals("$E[97;69u", tk('a', MOD_CTRL or MOD_CAPS_LOCK))
    }

    @Test
    fun plainTextStaysText() {
        assertEquals("a", tk('a', text = "a"))
        assertEquals("A", tk('a', MOD_SHIFT, text = "A", shifted = 'A'))
        assertEquals("A", tk('a', MOD_CAPS_LOCK, text = "A", shifted = 'A'))
        assertEquals(" ", tk(' ', text = " "))
        assertEquals("é", tk('é', text = "é"))
    }

    @Test
    fun ctrlAndAltChordsAreDisambiguated() {
        assertEquals("$E[105;5u", tk('i', MOD_CTRL)) // Ctrl+I is not Tab
        assertEquals("$E[109;5u", tk('m', MOD_CTRL)) // Ctrl+M is not Enter
        assertEquals("$E[91;5u", tk('[', MOD_CTRL))  // Ctrl+[ is not Esc
        assertEquals("$E[99;5u", tk('c', MOD_CTRL))
        assertEquals("$E[97;3u", tk('a', MOD_ALT))
        assertEquals("$E[97;7u", tk('a', MOD_CTRL or MOD_ALT))
        assertEquals("$E[97;6u", tk('a', MOD_CTRL or MOD_SHIFT, shifted = 'A'))
        assertEquals("$E[97;4u", tk('a', MOD_ALT or MOD_SHIFT, shifted = 'A'))
        assertEquals("$E[32;5u", tk(' ', MOD_CTRL))
        assertEquals("$E[97;9u", tk('a', MOD_SUPER))
    }

    @Test
    fun releasesAreSilentWithoutEventTypes() {
        assertNull(fk(Key.UP, action = KeyAction.RELEASE))
        assertNull(tk('a', MOD_CTRL, action = KeyAction.RELEASE))
        assertNull(tk('a', text = "a", action = KeyAction.RELEASE))
    }

    @Test
    fun repeatWithoutEventTypesIsAPress() {
        assertEquals("$E[1;5A", fk(Key.UP, MOD_CTRL, action = KeyAction.REPEAT))
        assertEquals("a", tk('a', text = "a", action = KeyAction.REPEAT))
    }

    // ------------------------------------------------------------- flag 2

    @Test
    fun eventTypesReportRepeatAndRelease() {
        val f = DIS or EVT
        assertEquals("$E[A", fk(Key.UP, flags = f))
        assertEquals("$E[1;1:2A", fk(Key.UP, flags = f, action = KeyAction.REPEAT))
        assertEquals("$E[1;1:3A", fk(Key.UP, flags = f, action = KeyAction.RELEASE))
        assertEquals("$E[1;5:3D", fk(Key.LEFT, MOD_CTRL, flags = f, action = KeyAction.RELEASE))
        assertEquals("$E[27;1:3u", fk(Key.ESCAPE, flags = f, action = KeyAction.RELEASE))
        assertEquals("$E[5;1:2~", fk(Key.PAGE_UP, flags = f, action = KeyAction.REPEAT))
    }

    @Test
    fun textKeysReleaseAsEscapesButPressAsText() {
        val f = DIS or EVT
        assertEquals("a", tk('a', text = "a", flags = f))
        assertEquals("a", tk('a', text = "a", flags = f, action = KeyAction.REPEAT))
        assertEquals("$E[97;1:3u", tk('a', text = null, flags = f, action = KeyAction.RELEASE))
        assertEquals("$E[97;5:3u", tk('a', MOD_CTRL, flags = f, action = KeyAction.RELEASE))
        assertEquals("$E[97;5:2u", tk('a', MOD_CTRL, flags = f, action = KeyAction.REPEAT))
    }

    @Test
    fun enterTabBackspaceNeverReleaseBelowFlag8() {
        val f = DIS or EVT
        assertNull(fk(Key.ENTER, flags = f, action = KeyAction.RELEASE))
        assertNull(fk(Key.TAB, flags = f, action = KeyAction.RELEASE))
        assertNull(fk(Key.BACKSPACE, flags = f, action = KeyAction.RELEASE))
        assertEquals("\r", fk(Key.ENTER, flags = f, action = KeyAction.REPEAT))
        // …but modified, they're escapes and do report.
        assertEquals("$E[13;5:3u", fk(Key.ENTER, MOD_CTRL, flags = f, action = KeyAction.RELEASE))
    }

    @Test
    fun eventTypesAloneKeepLegacyChordsForTextButNotEscape() {
        // Flag 2 without 1: kitty keeps legacy bytes for Esc and ctrl-letters.
        assertEquals(E, fk(Key.ESCAPE, flags = EVT))
        assertEquals("\u0001", tk('a', MOD_CTRL, flags = EVT))
        assertEquals("${E}a", tk('a', MOD_ALT, flags = EVT))
        assertEquals("$E[1;5A", fk(Key.UP, MOD_CTRL, flags = EVT))
    }

    // ------------------------------------------------------------- flag 4

    @Test
    fun alternateKeysAddTheShiftedKeyWhenShiftIsHeld() {
        val f = DIS or ALT_KEYS
        assertEquals("$E[97:65;6u", tk('a', MOD_CTRL or MOD_SHIFT, flags = f, shifted = 'A'))
        // No shift held: the shifted key isn't reported.
        assertEquals("$E[97;5u", tk('a', MOD_CTRL, flags = f, shifted = 'A'))
        // Text keys still type text; flag 4 only reshapes escapes.
        assertEquals("A", tk('a', MOD_SHIFT, flags = f, text = "A", shifted = 'A'))
    }

    @Test
    fun alternateKeysAddTheBaseLayoutKeyWhenItDiffers() {
        val f = DIS or ALT_KEYS
        // AZERTY: the key that types 'a' sits where US has 'q'.
        assertEquals("$E[97::113;5u", tk('a', MOD_CTRL, flags = f, base = 'q'))
        assertEquals("$E[97:65:113;6u", tk('a', MOD_CTRL or MOD_SHIFT, flags = f, shifted = 'A', base = 'q'))
        // Same as the key itself: omitted.
        assertEquals("$E[97;5u", tk('a', MOD_CTRL, flags = f, base = 'a'))
        // Cyrillic ф on the US 'a' position.
        assertEquals("$E[1092::97;5u", KeyEncoder.encodeKittyText(0x444, MOD_CTRL, f, baseLayout = 'a'.code))
    }

    @Test
    fun alternateKeysNeverDecorateFunctionalKeys() {
        assertEquals("$E[1;2A", fk(Key.UP, MOD_SHIFT, flags = DIS or ALT_KEYS))
    }

    // ------------------------------------------------------------- flag 8

    @Test
    fun allKeysAsEscapesReportsTextKeys() {
        val f = DIS or ALL
        assertEquals("$E[97u", tk('a', text = "a", flags = f))
        assertEquals("$E[97;2u", tk('a', MOD_SHIFT, text = "A", flags = f, shifted = 'A'))
        assertEquals("$E[97:65;2u", tk('a', MOD_SHIFT, text = "A", flags = f or ALT_KEYS, shifted = 'A'))
        assertEquals("$E[32u", tk(' ', text = " ", flags = f))
    }

    @Test
    fun allKeysAsEscapesReportsEnterTabBackspace() {
        val f = ALL
        assertEquals("$E[13u", fk(Key.ENTER, flags = f))
        assertEquals("$E[9u", fk(Key.TAB, flags = f))
        assertEquals("$E[127u", fk(Key.BACKSPACE, flags = f))
        assertEquals("$E[27u", fk(Key.ESCAPE, flags = f))
        assertEquals("$E[13;1:3u", fk(Key.ENTER, flags = f or EVT, action = KeyAction.RELEASE))
    }

    @Test
    fun modifierKeysOnlyReportUnderFlag8() {
        assertNull(KeyEncoder.encodeKitty(ExtraKey.LEFT_SHIFT, MOD_SHIFT, DIS))
        assertEquals("$E[57441;2u", KeyEncoder.encodeKitty(ExtraKey.LEFT_SHIFT, MOD_SHIFT, ALL))
        assertEquals("$E[57448;5u", KeyEncoder.encodeKitty(ExtraKey.RIGHT_CONTROL, MOD_CTRL, ALL))
        assertEquals(
            "$E[57441;1:3u",
            KeyEncoder.encodeKitty(ExtraKey.LEFT_SHIFT, 0, ALL or EVT, KeyAction.RELEASE),
        )
        assertNull(KeyEncoder.encodeKitty(ExtraKey.LEFT_SHIFT, 0, ALL, KeyAction.RELEASE))
    }

    @Test
    fun nonModifierExtraKeysReportWheneverEnhanced() {
        assertEquals("$E[57363u", KeyEncoder.encodeKitty(ExtraKey.MENU, 0, DIS))
        assertEquals("$E[57361;5u", KeyEncoder.encodeKitty(ExtraKey.PRINT_SCREEN, MOD_CTRL, DIS))
        assertNull(KeyEncoder.encodeKitty(ExtraKey.MENU, 0, 0))
    }

    // ------------------------------------------------------------- flag 16

    @Test
    fun associatedTextRidesInTheThirdField() {
        val f = ALL or TEXT
        assertEquals("$E[97;;97u", tk('a', text = "a", flags = f))
        assertEquals("$E[97;2;65u", tk('a', MOD_SHIFT, text = "A", flags = f, shifted = 'A'))
        // Chords type nothing, so there's nothing to embed.
        assertEquals("$E[97;5u", tk('a', MOD_CTRL, flags = f))
        // Releases carry no text.
        assertEquals("$E[97;1:3u", tk('a', text = "a", flags = f or EVT, action = KeyAction.RELEASE))
        // Astral text: code points, not UTF-16 halves.
        assertEquals("$E[0;;128512u", KeyEncoder.encodeKittyText(0, 0, f, text = "😀"))
    }

    // ------------------------------------------------------------- char paths

    @Test
    fun charPathFoldsStickyModifiers() {
        assertEquals("$E[99;5u", KeyEncoder.encodeKittyChar('c', MOD_CTRL, DIS))
        assertEquals("$E[99;6u", KeyEncoder.encodeKittyChar('C', MOD_CTRL, DIS))
        assertEquals("$E[120;3u", KeyEncoder.encodeKittyChar('x', MOD_ALT, DIS))
        assertEquals("$E[13;5u", KeyEncoder.encodeKittyChar('\r', MOD_CTRL, DIS))
        assertEquals("$E[127;3u", KeyEncoder.encodeKittyChar('\u007F', MOD_ALT, DIS))
        assertEquals("$E[27;3u", KeyEncoder.encodeKittyChar('\u001B', MOD_ALT, DIS))
        assertEquals("\t", KeyEncoder.encodeKittyChar('\t', 0, DIS))
        assertEquals("q", KeyEncoder.encodeKittyChar('q', 0, DIS))
        assertEquals("\u0003", KeyEncoder.encodeKittyChar('\u0003', MOD_CTRL, DIS))
    }
}

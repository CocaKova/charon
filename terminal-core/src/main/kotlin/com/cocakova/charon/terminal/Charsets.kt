package com.cocakova.charon.terminal

/**
 * Character-set designation (SCS). Charon supports the two sets that matter in a UTF-8
 * world: ASCII ('B') and DEC Special Graphics ('0') — the latter is how vim/tmux draw
 * borders on terminals without assuming UTF-8 box glyphs.
 */
object TermCharsets {
    const val ASCII = 'B'
    const val DEC_SPECIAL = '0'

    /** Map a GL code point through DEC Special Graphics; identity outside 0x5F..0x7E. */
    fun mapDecSpecial(cp: Int): Int = when (cp) {
        0x5F -> 0x00A0 // blank
        0x60 -> 0x25C6 // ◆ diamond
        0x61 -> 0x2592 // ▒ checkerboard
        0x62 -> 0x2409 // HT symbol
        0x63 -> 0x240C // FF symbol
        0x64 -> 0x240D // CR symbol
        0x65 -> 0x240A // LF symbol
        0x66 -> 0x00B0 // ° degree
        0x67 -> 0x00B1 // ± plus-minus
        0x68 -> 0x2424 // NL symbol
        0x69 -> 0x240B // VT symbol
        0x6A -> 0x2518 // ┘
        0x6B -> 0x2510 // ┐
        0x6C -> 0x250C // ┌
        0x6D -> 0x2514 // └
        0x6E -> 0x253C // ┼
        0x6F -> 0x23BA // ⎺ scan 1
        0x70 -> 0x23BB // ⎻ scan 3
        0x71 -> 0x2500 // ─
        0x72 -> 0x23BC // ⎼ scan 7
        0x73 -> 0x23BD // ⎽ scan 9
        0x74 -> 0x251C // ├
        0x75 -> 0x2524 // ┤
        0x76 -> 0x2534 // ┴
        0x77 -> 0x252C // ┬
        0x78 -> 0x2502 // │
        0x79 -> 0x2264 // ≤
        0x7A -> 0x2265 // ≥
        0x7B -> 0x03C0 // π
        0x7C -> 0x2260 // ≠
        0x7D -> 0x00A3 // £
        0x7E -> 0x00B7 // ·
        else -> cp
    }
}

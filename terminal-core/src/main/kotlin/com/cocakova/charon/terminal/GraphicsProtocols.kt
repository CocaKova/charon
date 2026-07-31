package com.cocakova.charon.terminal

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The two wire protocols for putting a picture in a terminal, read into the same
 * [ApparitionPlacement].
 *
 * - **Kitty** (`ESC _ G k=v,… ; base64 ESC \`) — the frontier standard. Chunked
 *   transmission, ids and placements, raw or encoded pixels, zlib payloads.
 * - **iTerm2** (`OSC 1337 ; File=k=v;… : base64 ST`) — what `imgcat` and most
 *   "show me a picture" scripts speak.
 *
 * Pure logic: this decides *what* rectangle of grid an image claims and *what* to
 * answer the sender. Reserving the rows and drawing the pixels belong to the
 * emulator and the renderer respectively.
 */
class ApparitionEngine(val store: ApparitionStore = ApparitionStore()) {

    /** The grid facts a placement needs; supplied by the emulator per command. */
    class Grid(
        val cellWidthPx: Int,
        val cellHeightPx: Int,
        val cursorCol: Int,
        val cols: Int,
        val rows: Int,
    )

    /** What the emulator must do once a graphics command has been read. */
    class Result(
        val response: String? = null,
        val place: ApparitionPlacement? = null,
        /** Kitty's `C=1` asks that the image not disturb the cursor. */
        val moveCursor: Boolean = true,
        val delete: Delete? = null,
    )

    sealed interface Delete {
        /** Every placement on the screen. [freeData] also drops the pixels. */
        data class All(val freeData: Boolean) : Delete

        /** Placements of one image, optionally narrowed to one placement id. */
        data class Image(val imageId: Long, val placementId: Long, val freeData: Boolean) : Delete

        /** Placements that cover the cursor's cell. */
        data object AtCursor : Delete
    }

    // ------------------------------------------------------------- Kitty graphics

    private var pendingKeys: KittyKeys? = null
    private val pendingData = StringBuilder()

    /**
     * Read one `_G` APC payload. Returns null when the command was not for us (a
     * different APC string) or when a chunk was merely accumulated.
     */
    fun kitty(payload: String, grid: Grid): Result? {
        if (payload.isEmpty() || payload[0] != 'G') return null
        val sep = payload.indexOf(';')
        val controls = if (sep >= 0) payload.substring(1, sep) else payload.substring(1)
        val data = if (sep >= 0) payload.substring(sep + 1) else ""
        val keys = KittyKeys.parse(controls)

        // Chunked transmission: the first chunk carries the controls, the rest only
        // `m` and payload. One transmission may be in flight at a time (Kitty's rule).
        if (keys.more) {
            val head = pendingKeys
            if (head == null) {
                pendingKeys = keys
                pendingData.setLength(0)
            }
            if (pendingData.length + data.length <= MAX_TRANSMISSION_B64) pendingData.append(data)
            return null
        }
        val head = pendingKeys
        val full: String
        val effective: KittyKeys
        if (head != null) {
            if (pendingData.length + data.length <= MAX_TRANSMISSION_B64) pendingData.append(data)
            full = pendingData.toString()
            effective = head
            pendingKeys = null
            pendingData.setLength(0)
        } else {
            full = data
            effective = keys
        }
        return runKitty(effective, full, grid)
    }

    private fun runKitty(k: KittyKeys, base64: String, grid: Grid): Result {
        return when (k.action) {
            'q' -> Result(response = reply(k, if (k.medium == 'd') "OK" else UNSUPPORTED_MEDIUM))
            'd' -> Result(response = null, delete = kittyDelete(k))
            't', 'T' -> {
                if (k.medium != 'd') return Result(response = reply(k, UNSUPPORTED_MEDIUM))
                val image = decodeKitty(k, base64)
                    ?: return Result(response = reply(k, "EINVAL:the shade would not hold its shape"))
                val id = if (k.id != 0L) k.id else store.nextAnonymousId()
                store.put(
                    Apparition(id, image.format, image.bytes, image.width, image.height),
                    number = k.number,
                )
                val place = if (k.action == 'T') placeFor(k, id, grid) else null
                Result(reply(k, "OK"), place, moveCursor = k.cursorMove == 0)
            }
            'p' -> {
                val id = resolveId(k) ?: return Result(response = reply(k, "ENOENT:no such shade"))
                Result(reply(k, "OK"), placeFor(k, id, grid), moveCursor = k.cursorMove == 0)
            }
            // Animation frames (a=f / a=c) are honest about not being here yet.
            'f', 'c', 'a' -> Result(response = reply(k, "ENOTSUP:animation is not aboard"))
            else -> Result(response = reply(k, "EINVAL:unknown action"))
        }
    }

    private fun kittyDelete(k: KittyKeys): Delete? {
        val free = k.delete.isUpperCase()
        return when (k.delete.lowercaseChar()) {
            'a' -> Delete.All(free)
            'i' -> Delete.Image(resolveId(k) ?: return null, k.placementId, free)
            'c' -> Delete.AtCursor
            else -> null
        }
    }

    private fun resolveId(k: KittyKeys): Long? = when {
        k.id != 0L && store[k.id] != null -> k.id
        k.number != 0L -> store.idForNumber(k.number)
        k.id != 0L -> k.id // an id we were told about but never filed: nothing to draw
        else -> null
    }

    private class Decoded(val format: ImageFormat, val bytes: ByteArray, val width: Int, val height: Int)

    private fun decodeKitty(k: KittyKeys, base64: String): Decoded? {
        var bytes = WireBase64.decode(base64) ?: return null
        if (k.compression == 'z') bytes = inflateZlib(bytes, MAX_IMAGE_BYTES) ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        return when (k.format) {
            100 -> {
                val (w, h) = ImageHeader.measure(bytes) ?: (k.width to k.height)
                if (w <= 0 || h <= 0) return null
                Decoded(ImageFormat.ENCODED, bytes, w, h)
            }
            24, 32 -> {
                val channels = if (k.format == 24) 3 else 4
                if (k.width <= 0 || k.height <= 0) return null
                if (bytes.size.toLong() < k.width.toLong() * k.height * channels) return null
                Decoded(
                    if (channels == 3) ImageFormat.RGB else ImageFormat.RGBA,
                    bytes, k.width, k.height,
                )
            }
            else -> null
        }
    }

    private fun placeFor(k: KittyKeys, imageId: Long, grid: Grid): ApparitionPlacement? {
        val image = store[imageId] ?: return null
        val srcW = if (k.srcW > 0) k.srcW else image.pixelWidth - k.srcX
        val srcH = if (k.srcH > 0) k.srcH else image.pixelHeight - k.srcY
        if (srcW <= 0 || srcH <= 0) return null
        val (cols, rows) = cellsFor(k.cols, k.rows, srcW, srcH, grid)
        if (cols <= 0 || rows <= 0) return null
        return ApparitionPlacement(
            imageId = imageId,
            placementId = k.placementId,
            startCol = grid.cursorCol,
            cols = cols,
            rows = rows,
            srcX = k.srcX, srcY = k.srcY, srcW = srcW, srcH = srcH,
            zIndex = k.zIndex,
        )
    }

    // ------------------------------------------------------------ iTerm2 imgcat

    /** Read an `OSC 1337` payload (the text after `1337;`). */
    fun iterm2(payload: String, grid: Grid): Result? {
        if (!payload.startsWith("File=")) return null
        val colon = payload.indexOf(':')
        if (colon < 0) return null
        val args = payload.substring("File=".length, colon).split(';')
        val bytes = WireBase64.decode(payload.substring(colon + 1)) ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null

        var inline = false
        var width = "auto"
        var height = "auto"
        var preserveAspect = true
        for (arg in args) {
            val eq = arg.indexOf('=')
            if (eq <= 0) continue
            val value = arg.substring(eq + 1).trim()
            when (arg.substring(0, eq).trim().lowercase()) {
                "inline" -> inline = value == "1"
                "width" -> width = value
                "height" -> height = value
                "preserveaspectratio" -> preserveAspect = value != "0"
            }
        }
        // Without inline=1 this is a file transfer, not a picture: not our cargo.
        if (!inline) return null

        val (pw, ph) = ImageHeader.measure(bytes) ?: return null
        if (pw <= 0 || ph <= 0) return null
        val id = store.nextAnonymousId()
        store.put(Apparition(id, ImageFormat.ENCODED, bytes, pw, ph))

        val wantCols = itermExtent(width, grid.cellWidthPx, grid.cols, pw)
        val wantRows = itermExtent(height, grid.cellHeightPx, grid.rows, ph)
        val (cols, rows) = if (preserveAspect) {
            cellsFor(wantCols, wantRows, pw, ph, grid)
        } else {
            val c = (if (wantCols > 0) wantCols else naturalCols(pw, grid)).coerceAtMost(grid.cols - grid.cursorCol)
            val r = (if (wantRows > 0) wantRows else naturalRows(ph, grid)).coerceAtMost(grid.rows)
            c to r
        }
        if (cols <= 0 || rows <= 0) return null
        return Result(
            place = ApparitionPlacement(
                imageId = id,
                placementId = 0,
                startCol = grid.cursorCol,
                cols = cols,
                rows = rows,
                srcW = pw, srcH = ph,
            ),
        )
    }

    /** `auto` → 0 (decide from the image), `N` cells, `Npx` pixels, `N%` of the grid. */
    private fun itermExtent(spec: String, cellPx: Int, gridCells: Int, imagePx: Int): Int {
        val s = spec.trim().lowercase()
        if (s.isEmpty() || s == "auto") return 0
        return when {
            s.endsWith("px") -> {
                val px = s.dropLast(2).toIntOrNull() ?: return 0
                ceilDiv(px, max(1, cellPx))
            }
            s.endsWith("%") -> {
                val pct = s.dropLast(1).toIntOrNull() ?: return 0
                (gridCells * pct / 100.0).roundToInt()
            }
            else -> s.toIntOrNull() ?: run {
                // Unreadable spec: fall back to the image's own size.
                ceilDiv(imagePx, max(1, cellPx))
            }
        }
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Turn a requested cell size into an actual one. Either dimension may be 0
     * ("you decide"); when exactly one is given the other follows the image's
     * aspect ratio, which is what keeps a photo from being stretched.
     */
    private fun cellsFor(wantCols: Int, wantRows: Int, srcW: Int, srcH: Int, grid: Grid): Pair<Int, Int> {
        val maxCols = (grid.cols - grid.cursorCol).coerceAtLeast(1)
        var cols = wantCols
        var rows = wantRows
        when {
            cols > 0 && rows > 0 -> {}
            cols > 0 -> rows = ceilDiv(srcH * cols * grid.cellWidthPx, max(1, srcW * grid.cellHeightPx))
            rows > 0 -> cols = ceilDiv(srcW * rows * grid.cellHeightPx, max(1, srcH * grid.cellWidthPx))
            else -> {
                cols = naturalCols(srcW, grid)
                rows = naturalRows(srcH, grid)
            }
        }
        // An image wider than the line, or taller than the screen, is scaled down to
        // fit rather than cropped — on a phone the whole point is seeing the thing.
        if (cols > maxCols) {
            rows = max(1, rows * maxCols / max(1, cols))
            cols = maxCols
        }
        if (rows > grid.rows) {
            cols = max(1, cols * grid.rows / max(1, rows))
            rows = grid.rows
        }
        return cols.coerceAtLeast(1) to rows.coerceAtLeast(1)
    }

    private fun naturalCols(srcW: Int, grid: Grid) = ceilDiv(srcW, max(1, grid.cellWidthPx))
    private fun naturalRows(srcH: Int, grid: Grid) = ceilDiv(srcH, max(1, grid.cellHeightPx))

    // ------------------------------------------------------------------ responses

    /** Kitty answers are addressed by id; an unaddressed command gets silence. */
    private fun reply(k: KittyKeys, message: String): String? {
        if (k.quiet >= 2) return null
        if (message == "OK" && k.quiet >= 1) return null
        if (k.id == 0L && k.number == 0L) return null
        val address = buildString {
            if (k.id != 0L) append("i=").append(k.id)
            if (k.number != 0L) {
                if (isNotEmpty()) append(',')
                append("I=").append(k.number)
                if (k.placementId != 0L) append(",p=").append(k.placementId)
            }
        }
        return "\u001B_G$address;$message\u001B\\"
    }

    companion object {
        private const val UNSUPPORTED_MEDIUM = "EBADF:only direct transmission crosses"

        /** A single image; anything larger is a stream doing something else. */
        const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
        private const val MAX_TRANSMISSION_B64 = 24 * 1024 * 1024

        private fun ceilDiv(a: Int, b: Int) = if (b <= 0) 0 else (a + b - 1) / b
    }
}

/**
 * The Kitty control block: `a=T,f=100,s=64,v=64,c=10,r=5`. Unknown keys are ignored
 * by design — the protocol grows, and an unrecognised key must never sink a command
 * we otherwise understand.
 */
class KittyKeys private constructor(
    val action: Char,
    val medium: Char,
    val format: Int,
    val compression: Char,
    val id: Long,
    val number: Long,
    val placementId: Long,
    val more: Boolean,
    val width: Int,
    val height: Int,
    val srcX: Int,
    val srcY: Int,
    val srcW: Int,
    val srcH: Int,
    val cols: Int,
    val rows: Int,
    val zIndex: Int,
    val cursorMove: Int,
    val quiet: Int,
    val delete: Char,
) {
    companion object {
        fun parse(controls: String): KittyKeys {
            var action = 't'
            var medium = 'd'
            var format = 32
            var compression = ' '
            var id = 0L
            var number = 0L
            var placementId = 0L
            var more = false
            var width = 0
            var height = 0
            var srcX = 0
            var srcY = 0
            var srcW = 0
            var srcH = 0
            var cols = 0
            var rows = 0
            var zIndex = 0
            var cursorMove = 0
            var quiet = 0
            var delete = 'a'

            for (pair in controls.split(',')) {
                val eq = pair.indexOf('=')
                if (eq != 1) continue // every control key is a single character
                val key = pair[0]
                val value = pair.substring(2)
                val n = value.toLongOrNull()
                when (key) {
                    'a' -> action = value.firstOrNull() ?: 't'
                    't' -> medium = value.firstOrNull() ?: 'd'
                    'o' -> compression = value.firstOrNull() ?: ' '
                    'd' -> delete = value.firstOrNull() ?: 'a'
                    'f' -> format = n?.toInt() ?: format
                    'i' -> id = n ?: id
                    'I' -> number = n ?: number
                    'p' -> placementId = n ?: placementId
                    'm' -> more = n == 1L
                    's' -> width = n?.toInt() ?: width
                    'v' -> height = n?.toInt() ?: height
                    'x' -> srcX = n?.toInt() ?: srcX
                    'y' -> srcY = n?.toInt() ?: srcY
                    'w' -> srcW = n?.toInt() ?: srcW
                    'h' -> srcH = n?.toInt() ?: srcH
                    'c' -> cols = n?.toInt() ?: cols
                    'r' -> rows = n?.toInt() ?: rows
                    'z' -> zIndex = n?.toInt() ?: zIndex
                    'C' -> cursorMove = n?.toInt() ?: cursorMove
                    'q' -> quiet = n?.toInt() ?: quiet
                    else -> {}
                }
            }
            return KittyKeys(
                action, medium, format, compression, id, number, placementId, more,
                width.coerceAtLeast(0), height.coerceAtLeast(0),
                srcX.coerceAtLeast(0), srcY.coerceAtLeast(0),
                srcW.coerceAtLeast(0), srcH.coerceAtLeast(0),
                cols.coerceAtLeast(0), rows.coerceAtLeast(0),
                zIndex, cursorMove, quiet, delete,
            )
        }
    }
}

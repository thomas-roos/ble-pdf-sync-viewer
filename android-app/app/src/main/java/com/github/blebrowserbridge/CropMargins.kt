package com.github.blebrowserbridge

/**
 * Finds the bounding box of the printed (non-white) content of a page so the
 * empty margins can be cut away and the content can use the whole screen.
 *
 * Pure logic with no Android dependencies - unit-tested in CropMarginsTest.
 */
object CropMargins {
    data class Bounds(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    fun isContent(pixel: Int): Boolean =
        (pixel ushr 24) > 0x80 &&
            (
                (pixel shr 16 and 0xFF) < 235 ||
                    (pixel shr 8 and 0xFF) < 235 ||
                    (pixel and 0xFF) < 235
            )

    /**
     * Returns the padded content bounding box, or null when the page is
     * blank or the detected box is suspiciously small (over-cropping guard).
     * `rowPixels(y)` must return the ARGB pixels of row y.
     */
    fun computeContentBounds(
        width: Int,
        height: Int,
        rowPixels: (Int) -> IntArray,
    ): Bounds? {
        val step = maxOf(1, width / 300)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1

        for (y in 0 until height step step) {
            val row = rowPixels(y)
            for (x in 0 until width step step) {
                if (isContent(row[x])) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null // blank page

        val pad = maxOf(step, width / 100)
        minX = maxOf(0, minX - pad)
        minY = maxOf(0, minY - pad)
        maxX = minOf(width - 1, maxX + pad)
        maxY = minOf(height - 1, maxY + pad)

        val boxWidth = maxX - minX + 1
        val boxHeight = maxY - minY + 1
        if (boxWidth < width / 10 || boxHeight < height / 10) return null
        return Bounds(minX, minY, boxWidth, boxHeight)
    }
}

package com.github.blebrowserbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropMarginsTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun page(
        width: Int,
        height: Int,
        ink: (x: Int, y: Int) -> Boolean,
    ) = { y: Int -> IntArray(width) { x -> if (ink(x, y)) black else white } }

    @Test
    fun blankPageIsNotCropped() {
        assertNull(CropMargins.computeContentBounds(400, 400, page(400, 400) { _, _ -> false }))
    }

    @Test
    fun contentBoundsCoverTheInkWithPadding() {
        // 300x300 page with a black box at x 100..199, y 120..179
        val bounds =
            CropMargins.computeContentBounds(
                300,
                300,
                page(300, 300) { x, y -> x in 100..199 && y in 120..179 },
            )!!
        // step is 1 at this size, pad is width/100 = 3
        assertEquals(CropMargins.Bounds(97, 117, 106, 66), bounds)
    }

    @Test
    fun boundsNeverLeaveThePage() {
        // ink touching every edge: padding must be clamped to the page
        val bounds =
            CropMargins.computeContentBounds(
                200,
                200,
                page(200, 200) { x, y -> x == 0 || y == 0 || x == 199 || y == 199 },
            )!!
        assertEquals(CropMargins.Bounds(0, 0, 200, 200), bounds)
    }

    @Test
    fun tinyContentDoesNotTriggerOverCropping() {
        // a single small dot: cropping to it would zoom into noise
        assertNull(
            CropMargins.computeContentBounds(
                400,
                400,
                page(400, 400) { x, y -> x in 200..202 && y in 200..202 },
            ),
        )
    }

    @Test
    fun nearWhitePixelsCountAsMargin() {
        assertTrue(CropMargins.isContent(black))
        // light-gray page background (values >= 235) is not content
        assertEquals(false, CropMargins.isContent(0xFFEFEFEF.toInt()))
        // transparent pixels are not content
        assertEquals(false, CropMargins.isContent(0x00000000))
    }
}

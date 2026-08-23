package cc.rocketscience.receipts.ui.crop

import androidx.compose.ui.geometry.Offset
import cc.rocketscience.receipts.image.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gesture maths, tested directly. The first version of this shipped broken because the
 * drag handler closed over a stale rectangle, which no amount of compiling would catch.
 */
class CropOverlayTest {

    private val w = 1000
    private val h = 2000
    private val crop = NormalizedRect(0.08f, 0.08f, 0.92f, 0.92f)
    private val touch = 100f

    private fun grab(x: Float, y: Float) =
        grabFor(Offset(x, y), crop, w, h, touch)

    @Test
    fun `each corner is grabbable at its own position`() {
        assertEquals(Grab.TOP_LEFT, grab(80f, 160f))
        assertEquals(Grab.TOP_RIGHT, grab(920f, 160f))
        assertEquals(Grab.BOTTOM_LEFT, grab(80f, 1840f))
        assertEquals(Grab.BOTTOM_RIGHT, grab(920f, 1840f))
    }

    @Test
    fun `a corner is grabbable from slightly outside the selection`() {
        // Fingers land outside the line as often as inside it.
        assertEquals(Grab.TOP_LEFT, grab(40f, 120f))
        assertEquals(Grab.BOTTOM_RIGHT, grab(960f, 1880f))
    }

    @Test
    fun `a touch within the touch radius counts, beyond it does not`() {
        assertEquals(Grab.TOP_LEFT, grab(80f + 90f, 160f))
        // 150px away from the corner is past the radius, but still inside the rect.
        assertEquals(Grab.MOVE, grab(80f + 150f, 160f + 150f))
    }

    @Test
    fun `the nearest corner wins when touch targets overlap`() {
        val tiny = NormalizedRect(0.4f, 0.4f, 0.5f, 0.5f)
        // Closer to the top-left of the tiny rect than to its bottom-right.
        val g = grabFor(Offset(0.41f * w, 0.41f * h), tiny, w, h, 500f)
        assertEquals(Grab.TOP_LEFT, g)
    }

    @Test
    fun `the body moves and the outside grabs nothing`() {
        assertEquals(Grab.MOVE, grab(500f, 1000f))
        assertEquals(Grab.NONE, grab(5f, 5f))
    }

    @Test
    fun `dragging a corner resizes only that corner`() {
        val out = applyGrab(crop, Grab.TOP_LEFT, -0.05f, -0.05f)
        assertEquals(0.03f, out.left, 1e-5f)
        assertEquals(0.03f, out.top, 1e-5f)
        assertEquals(crop.right, out.right, 1e-5f)
        assertEquals(crop.bottom, out.bottom, 1e-5f)
    }

    @Test
    fun `a corner cannot be dragged outside the image`() {
        val out = applyGrab(crop, Grab.TOP_LEFT, -1f, -1f)
        assertEquals(0f, out.left, 1e-5f)
        assertEquals(0f, out.top, 1e-5f)
    }

    @Test
    fun `a corner cannot be dragged past its opposite edge`() {
        val out = applyGrab(crop, Grab.TOP_LEFT, 1f, 1f)
        assertTrue("must keep a minimum width", out.right - out.left >= MIN_SIDE - 1e-5f)
        assertTrue("must keep a minimum height", out.bottom - out.top >= MIN_SIDE - 1e-5f)
    }

    @Test
    fun `moving preserves the size`() {
        val out = applyGrab(crop, Grab.MOVE, 0.05f, -0.03f)
        assertEquals(crop.right - crop.left, out.right - out.left, 1e-5f)
        assertEquals(crop.bottom - crop.top, out.bottom - out.top, 1e-5f)
        assertEquals(0.13f, out.left, 1e-5f)
        assertEquals(0.05f, out.top, 1e-5f)
    }

    @Test
    fun `moving clamps to the image without shrinking`() {
        val out = applyGrab(crop, Grab.MOVE, 1f, 1f)
        assertEquals(1f, out.right, 1e-5f)
        assertEquals(1f, out.bottom, 1e-5f)
        assertEquals(crop.right - crop.left, out.right - out.left, 1e-5f)
    }

    @Test
    fun `a drag with nothing grabbed changes nothing`() {
        assertEquals(crop, applyGrab(crop, Grab.NONE, 0.2f, 0.2f))
    }
}

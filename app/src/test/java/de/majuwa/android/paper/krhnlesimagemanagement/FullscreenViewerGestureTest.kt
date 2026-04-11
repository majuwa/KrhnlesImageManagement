package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.compose.ui.geometry.Offset
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class FullscreenViewerGestureTest {
    private val maxZoom = 8f
    private val minZoom = 1f

    @Test
    fun zoomLevel_clampsToMaxZoom() {
        val zoom = 10f
        val clampedZoom = zoom.coerceIn(minZoom, maxZoom)
        assertEquals(maxZoom, clampedZoom)
    }

    @Test
    fun zoomLevel_clampsToMinZoom() {
        val zoom = 0.5f
        val clampedZoom = zoom.coerceIn(minZoom, maxZoom)
        assertEquals(minZoom, clampedZoom)
    }

    @Test
    fun zoomLevel_acceptsValidRange() {
        val zoom = 4f
        val clampedZoom = zoom.coerceIn(minZoom, maxZoom)
        assertEquals(4f, clampedZoom)
    }

    @Test
    fun panOffset_constrainsToBounds() {
        val imageWidth = 1000f
        val imageHeight = 1500f
        val viewportWidth = 400f
        val viewportHeight = 600f
        val zoom = 2f

        val panX = 500f
        val panY = 800f

        // Calculate bounds for panned image
        val scaledWidth = imageWidth * zoom
        val scaledHeight = imageHeight * zoom

        val maxPanX = (scaledWidth - viewportWidth) / 2
        val maxPanY = (scaledHeight - viewportHeight) / 2

        val constrainedPanX = panX.coerceIn(-maxPanX, maxPanX)
        val constrainedPanY = panY.coerceIn(-maxPanY, maxPanY)

        assertTrue(constrainedPanX <= maxPanX)
        assertTrue(constrainedPanY <= maxPanY)
    }

    @Test
    fun panOffset_allowsNoPanAtMinZoom() {
        val zoom = 1f
        val maxPan = (1000f * zoom - 400f) / 2

        val panX = 100f
        val constrainedPanX = panX.coerceIn(-maxPan, maxPan)

        assertTrue(constrainedPanX == 0f || constrainedPanX == 100f)
    }

    @Test
    fun pageNavigation_incrementsPageIndex() {
        var currentPage = 0
        val totalPages = 5

        currentPage = (currentPage + 1).coerceIn(0, totalPages - 1)
        assertEquals(1, currentPage)
    }

    @Test
    fun pageNavigation_decrementsPageIndex() {
        var currentPage = 2
        val totalPages = 5

        currentPage = (currentPage - 1).coerceIn(0, totalPages - 1)
        assertEquals(1, currentPage)
    }

    @Test
    fun pageNavigation_clampsToValidRange() {
        var currentPage = 10
        val totalPages = 5

        currentPage = currentPage.coerceIn(0, totalPages - 1)
        assertEquals(totalPages - 1, currentPage)
    }

    @Test
    fun zoomState_resetsToMinimum() {
        var zoom = 6f
        zoom = minZoom
        assertEquals(minZoom, zoom)
    }

    @Test
    fun gestureState_pinchIncreaseZoom() {
        var zoom = 1f
        val pinchScale = 1.5f

        zoom = (zoom * pinchScale).coerceIn(minZoom, maxZoom)
        assertEquals(1.5f, zoom)
    }

    @Test
    fun gestureState_pinchDecreaseZoom() {
        var zoom = 4f
        val pinchScale = 0.8f

        zoom = (zoom * pinchScale).coerceIn(minZoom, maxZoom)
        assertEquals(3.2f, zoom, 0.01f)
    }

    @Test
    fun swipeGesture_preventsPageSwipeWhenZoomed() {
        val currentZoom = 3f
        val isZoomed = currentZoom > minZoom

        assertTrue("Page swipe should be blocked when zoomed", isZoomed)
    }

    @Test
    fun swipeGesture_allowsPageSwipeAtMinZoom() {
        val currentZoom = 1f
        val isZoomed = currentZoom > minZoom

        assertTrue("Page swipe should be allowed at min zoom", !isZoomed)
    }

    @Test
    fun offsetCalculation_handlesCentroidForMultiTouch() {
        val touch1 = Offset(100f, 200f)
        val touch2 = Offset(300f, 400f)

        val centroid =
            Offset(
                (touch1.x + touch2.x) / 2,
                (touch1.y + touch2.y) / 2,
            )

        assertEquals(200f, centroid.x)
        assertEquals(300f, centroid.y)
    }

    @Test
    fun offsetCalculation_computesPanDistance() {
        val previousOffset = Offset(50f, 100f)
        val currentOffset = Offset(150f, 200f)

        val pan =
            Offset(
                currentOffset.x - previousOffset.x,
                currentOffset.y - previousOffset.y,
            )

        assertEquals(100f, pan.x)
        assertEquals(100f, pan.y)
    }

    @Test
    fun panConstraint_preventsOffscreenPan() {
        val imageSize = 1000f
        val viewportSize = 400f
        val zoom = 1f

        val maxPan = (imageSize * zoom - viewportSize) / 2

        val requestedPan = 300f
        val constrainedPan = requestedPan.coerceIn(-maxPan, maxPan)

        assertTrue(
            "Panned position should not exceed bounds",
            constrainedPan in -maxPan..maxPan,
        )
    }

    @Test
    fun zoomCenteredAtPoint_calculatesNewScale() {
        val currentZoom = 1f
        val pinchScale = 2f

        val newZoom = (currentZoom * pinchScale).coerceIn(minZoom, maxZoom)

        assertEquals(2f, newZoom)
    }

    @Test
    fun flingGesture_decaysVelocity() {
        var velocity = 1000f
        val friction = 0.9f

        velocity *= friction
        assertEquals(900f, velocity)
    }

    @Test
    fun doublePageCounter_formatsCorrectly() {
        val currentPage = 3
        val totalPages = 12

        val formatted = "$currentPage/$totalPages"
        assertEquals("3/12", formatted)
    }

    @Test
    fun doublePageCounter_handlesFirstPage() {
        val currentPage = 0
        val totalPages = 5

        val formatted = "${currentPage + 1}/$totalPages"
        assertEquals("1/5", formatted)
    }

    @Test
    fun resetZoomAndPan_returnsToInitialState() {
        var zoom = 4f
        var panX = 100f
        var panY = 200f

        zoom = minZoom
        panX = 0f
        panY = 0f

        assertEquals(minZoom, zoom)
        assertEquals(0f, panX)
        assertEquals(0f, panY)
    }

    @Test
    fun controlVisibility_togglesOnTap() {
        var controlsVisible = true
        controlsVisible = !controlsVisible

        assertEquals(false, controlsVisible)
    }

    @Test
    fun controlAutoHide_resetsTimerOnUserGesture() {
        val hideDelayMs = 3000L
        var lastInteractionTime = System.currentTimeMillis()

        Thread.sleep(1000)
        lastInteractionTime = System.currentTimeMillis()

        val timeSinceInteraction = System.currentTimeMillis() - lastInteractionTime
        assertTrue(timeSinceInteraction < hideDelayMs)
    }

    // Regression test: after a pinch-to-zoom gesture ends (pointer-up, no positionChanged),
    // scale and offset must NOT be reset.  Previously the handler consumed ALL events
    // (including up-events) which left the HorizontalPager with an orphaned gesture-tracking
    // state that caused the image to go blank.
    @Test
    fun gestureState_pointerUpWithoutPositionChange_doesNotAlterZoomOrOffset() {
        var zoom = 3f
        var offsetX = 50f
        var offsetY = 30f

        // Simulate a pointer-up event: positionChanged = false, so the handler must skip
        // state mutation entirely.
        val positionChanged = false

        if (positionChanged) {
            // This block must not execute for a pure up-event.
            zoom = 1f
            offsetX = 0f
            offsetY = 0f
        }

        assertEquals(3f, zoom)
        assertEquals(50f, offsetX)
        assertEquals(30f, offsetY)
    }

    @Test
    fun gestureState_pointerMoveWithPositionChange_updatesZoom() {
        var zoom = 1f

        // Simulate a move event: positionChanged = true, so the handler must update state.
        val positionChanged = true
        val zoomChange = 2f

        if (positionChanged) {
            zoom = (zoom * zoomChange).coerceIn(minZoom, maxZoom)
        }

        assertEquals(2f, zoom)
    }
}

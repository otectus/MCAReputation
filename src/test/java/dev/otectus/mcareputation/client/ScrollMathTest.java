package dev.otectus.mcareputation.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §28.2: the scrollbar and the wheel must agree, especially at the no-scroll boundary. */
class ScrollMathTest {

    @Test
    void contentThatFitsCannotScroll() {
        assertEquals(0, ScrollMath.maxScroll(80, 100));
        assertEquals(0, ScrollMath.maxScroll(100, 100), "exactly fitting is still no scroll");
        assertEquals(0.0, ScrollMath.clampScroll(50.0, 80, 100));
        assertEquals(0, ScrollMath.thumbY(50.0, 80, 100), "no division blow-up at the boundary");
    }

    @Test
    void overflowingContentScrollsExactlyTheOverflow() {
        assertEquals(60, ScrollMath.maxScroll(160, 100));
        assertEquals(60.0, ScrollMath.clampScroll(999.0, 160, 100));
        assertEquals(0.0, ScrollMath.clampScroll(-5.0, 160, 100));
        assertEquals(30.0, ScrollMath.clampScroll(30.0, 160, 100));
    }

    /** A stale scroll after the list shrank (community cycled) clamps back into range. */
    @Test
    void staleScrollClampsAfterContentShrinks() {
        double scrolledDeep = ScrollMath.clampScroll(200.0, 400, 100);
        assertEquals(200.0, scrolledDeep, "valid while the long list was up");
        assertEquals(20.0, ScrollMath.clampScroll(scrolledDeep, 120, 100),
                "clamped to the shorter list's overflow after cycling communities");
    }

    /** Dragging the scroller must land where the painted thumb already is, at both ends. */
    @Test
    void draggingTheThumbRoundTripsThroughItsPaintedPosition() {
        int trackHeight = 100;
        int contentHeight = 400;
        assertEquals(0.0, ScrollMath.scrollForThumbTop(0, contentHeight, trackHeight),
                "the top of the track is the top of the content");
        int travel = trackHeight - ScrollMath.thumbHeight(contentHeight, trackHeight);
        assertEquals(ScrollMath.maxScroll(contentHeight, trackHeight),
                ScrollMath.scrollForThumbTop(travel, contentHeight, trackHeight),
                "the bottom of the track is the bottom of the content");
        assertEquals(0.0, ScrollMath.scrollForThumbTop(-40, contentHeight, trackHeight),
                "a drag above the track clamps rather than scrolling backwards");
        assertEquals(ScrollMath.maxScroll(contentHeight, trackHeight),
                ScrollMath.scrollForThumbTop(999, contentHeight, trackHeight));

        for (int thumbTop = 0; thumbTop <= travel; thumbTop++) {
            double scroll = ScrollMath.scrollForThumbTop(thumbTop, contentHeight, trackHeight);
            assertEquals(thumbTop, ScrollMath.thumbY(scroll, contentHeight, trackHeight),
                    "the drag and the paint disagreed at thumb offset " + thumbTop);
        }
    }

    /** Content that fits has nowhere to drag to, and must not divide by a zero-length travel. */
    @Test
    void draggingContentThatFitsDoesNothing() {
        assertEquals(0.0, ScrollMath.scrollForThumbTop(50, 80, 100));
        assertEquals(0.0, ScrollMath.scrollForThumbTop(50, 4000, 12),
                "no travel once the thumb fills the track");
    }

    @Test
    void thumbStaysGrabbableAndInTrack() {
        assertTrue(ScrollMath.thumbHeight(4000, 100) >= 16, "never too small to grab");
        assertEquals(12, ScrollMath.thumbHeight(4000, 12),
                "but never taller than the track, however small the GUI scale gets");
        int trackHeight = 100;
        int contentHeight = 400;
        int atBottom = ScrollMath.thumbY(ScrollMath.maxScroll(contentHeight, trackHeight),
                contentHeight, trackHeight);
        assertTrue(atBottom + ScrollMath.thumbHeight(contentHeight, trackHeight) <= trackHeight,
                "the thumb never leaves the track");
        assertEquals(0, ScrollMath.thumbY(0.0, contentHeight, trackHeight));
    }
}

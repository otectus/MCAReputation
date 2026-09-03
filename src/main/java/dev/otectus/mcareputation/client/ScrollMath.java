package dev.otectus.mcareputation.client;

/**
 * The standing screen's scroll arithmetic, in one pure place (spec §28.2).
 *
 * <p>Extracted so the scrollbar and the mouse-wheel handler cannot disagree at the boundaries —
 * they previously used two different clamps for "how far can this scroll", which put the thumb and
 * the content out of step exactly when the list barely fit.
 */
final class ScrollMath {

    private ScrollMath() {
    }

    /** How far the content can scroll; {@code 0} when it fits. */
    static int maxScroll(int contentHeight, int trackHeight) {
        return Math.max(0, contentHeight - trackHeight);
    }

    /** The scroll position clamped into the valid range — after a wheel event or a content change. */
    static double clampScroll(double scroll, int contentHeight, int trackHeight) {
        return Math.max(0, Math.min(maxScroll(contentHeight, trackHeight), scroll));
    }

    /**
     * Thumb height, proportional to the visible fraction, never too small to grab — and never
     * taller than the track it runs in, which a punishingly small GUI scale can otherwise force.
     */
    static int thumbHeight(int contentHeight, int trackHeight) {
        return Math.min(Math.max(0, trackHeight),
                Math.max(16, trackHeight * trackHeight / Math.max(1, contentHeight)));
    }

    /**
     * Thumb offset within the track for the current scroll position.
     *
     * <p>Rounded rather than truncated, so that it is a true inverse of
     * {@link #scrollForThumbTop}: with truncation, dragging the thumb to a given pixel repainted it
     * one pixel higher wherever the division landed just under an integer, and the scroller crept
     * upward away from the pointer as the player dragged.
     */
    static int thumbY(double scroll, int contentHeight, int trackHeight) {
        int max = maxScroll(contentHeight, trackHeight);
        if (max == 0) {
            return 0;
        }
        return (int) Math.round((trackHeight - thumbHeight(contentHeight, trackHeight))
                * (scroll / max));
    }

    /**
     * The scroll position that puts the thumb's top at {@code thumbTop} within the track — the
     * inverse of {@link #thumbY}, for dragging the scroller.
     *
     * <p>It lives here rather than in the drag handler for the same reason the rest of this class
     * does: a drag that computed its own mapping would part company with the painted thumb exactly
     * at the ends of the track, which is where a player notices.
     */
    static double scrollForThumbTop(double thumbTop, int contentHeight, int trackHeight) {
        int max = maxScroll(contentHeight, trackHeight);
        int travel = trackHeight - thumbHeight(contentHeight, trackHeight);
        if (max == 0 || travel <= 0) {
            return 0;
        }
        return clampScroll(thumbTop / travel * max, contentHeight, trackHeight);
    }
}

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

    /** Thumb height, proportional to the visible fraction, never too small to grab. */
    static int thumbHeight(int contentHeight, int trackHeight) {
        return Math.max(16, trackHeight * trackHeight / Math.max(1, contentHeight));
    }

    /** Thumb offset within the track for the current scroll position. */
    static int thumbY(double scroll, int contentHeight, int trackHeight) {
        int max = maxScroll(contentHeight, trackHeight);
        if (max == 0) {
            return 0;
        }
        return (int) ((trackHeight - thumbHeight(contentHeight, trackHeight)) * (scroll / max));
    }
}

package dev.otectus.mcareputation.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The community selector's arithmetic (§28.2), which is where a real standing became unreachable.
 *
 * <p>The server may detail a community the player has no record for — the village they are standing
 * in, or the villager they just looked at. That selection is deliberately not one of the summaries,
 * and the screen used to draw its arrows only when the summary list held more than one entry. A
 * player with exactly one real community, shown a village they were a stranger in, therefore had one
 * summary, no arrows, and no way back to the only standing they had.
 */
class SelectorMathTest {

    // ------------------------------------------------------------------
    // canCycle
    // ------------------------------------------------------------------

    @Test
    void nothingToCycleWithNoCommunities() {
        assertFalse(SelectorMath.canCycle(0, false));
        assertFalse(SelectorMath.canCycle(0, true));
    }

    @Test
    void oneCommunityWhichIsTheSelectionHasNowhereToGo() {
        assertFalse(SelectorMath.canCycle(1, true));
    }

    /** The regression: one real community plus an off-list selection is two places, not one. */
    @Test
    void oneCommunityIsStillReachableFromAnOffListSelection() {
        assertTrue(SelectorMath.canCycle(1, false));
    }

    @Test
    void severalCommunitiesAlwaysCycle() {
        assertTrue(SelectorMath.canCycle(3, true));
        assertTrue(SelectorMath.canCycle(3, false));
    }

    // ------------------------------------------------------------------
    // nextIndex
    // ------------------------------------------------------------------

    @Test
    void cyclingWrapsInsideTheList() {
        assertEquals(1, SelectorMath.nextIndex(3, 0, 1));
        assertEquals(0, SelectorMath.nextIndex(3, 2, 1));
        assertEquals(2, SelectorMath.nextIndex(3, 0, -1));
    }

    /**
     * From the off-list selection, forward enters at the front and backward at the back. Rounding the
     * off-list selection to index 0 — which is what the screen used to do — made a forward cycle land
     * on index 1 and skip the first community entirely.
     */
    @Test
    void anOffListSelectionEntersTheListWithoutSkippingAnEntry() {
        assertEquals(0, SelectorMath.nextIndex(3, SelectorMath.NOT_IN_LIST, 1));
        assertEquals(2, SelectorMath.nextIndex(3, SelectorMath.NOT_IN_LIST, -1));
        assertEquals(0, SelectorMath.nextIndex(1, SelectorMath.NOT_IN_LIST, 1));
        assertEquals(0, SelectorMath.nextIndex(1, SelectorMath.NOT_IN_LIST, -1));
    }

    /** A stale index left over from a shorter list must not throw or land out of bounds. */
    @Test
    void anIndexPastTheEndIsTreatedAsOffList() {
        assertEquals(0, SelectorMath.nextIndex(2, 9, 1));
        assertEquals(1, SelectorMath.nextIndex(2, 9, -1));
    }

    @Test
    void anEmptyListNeverProducesAnIndex() {
        assertEquals(SelectorMath.NOT_IN_LIST, SelectorMath.nextIndex(0, 0, 1));
    }
}

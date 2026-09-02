package dev.otectus.mcareputation.client;

/**
 * The community selector's two arithmetic decisions, as pure functions (spec §28.2).
 *
 * <p>No Minecraft types on purpose, for the same reason {@link ScrollMath} has none:
 * {@code SelectorMathTest} exercises every boundary with no game running.
 *
 * <h2>Why the selection can sit outside the list</h2>
 *
 * <p>The server may detail a community the player has <em>no record</em> for — a villager they just
 * looked at, or the village they happen to be standing in when they have no standing anywhere. That
 * selection is deliberately absent from the summary list, which only carries communities with a
 * record. The list and the selection are therefore not the same set, and both of these functions exist
 * because the screen used to assume they were: it showed the arrows only when the list held more than
 * one entry, so a player with exactly one real community, shown a village they were a stranger in,
 * had no way to reach the one place they had actually earned something.
 */
public final class SelectorMath {

    /** The index meaning "the selected community is not one of the summaries". */
    public static final int NOT_IN_LIST = -1;

    private SelectorMath() {
    }

    /**
     * Whether the selector arrows are worth drawing: is there anywhere else to go?
     *
     * <p>Counts the off-list selection as a place. One known community plus a village the player is a
     * stranger in is two destinations, not one.
     */
    public static boolean canCycle(int communityCount, boolean selectionInList) {
        if (communityCount <= 0) {
            return false;
        }
        return communityCount > 1 || !selectionInList;
    }

    /**
     * The list index a cycle lands on.
     *
     * <p>From {@link #NOT_IN_LIST} — the off-list selection — forward enters the list at the front and
     * backward at the back, so neither direction skips an entry. Inside the list it wraps, which is
     * what {@code floorMod} is doing and why the direction may be any integer.
     */
    public static int nextIndex(int size, int currentIndex, int direction) {
        if (size <= 0) {
            return NOT_IN_LIST;
        }
        if (currentIndex < 0 || currentIndex >= size) {
            return direction >= 0 ? 0 : size - 1;
        }
        return Math.floorMod(currentIndex + direction, size);
    }
}

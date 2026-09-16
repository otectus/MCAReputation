package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.network.SnapshotPaging;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The community list's paging arithmetic (§28.2, §5 F16 row 3), in the style of
 * {@link SelectorMathTest}: no server, no screen, no font.
 *
 * <p>The case that matters is one community past the per-page cap. The list used to stop at 64 with
 * nothing said about it, so a player with a 65th village — very plausibly the negative one they were
 * looking for — simply could not reach it.
 */
class PaginationMathTest {

    private static final int PER_PAGE = ReputationBounds.MAX_SYNCED_COMMUNITIES;

    /** A list of deliberately long names: paging must not depend on how wide an entry draws. */
    private static List<String> villages(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add("Ashfordbury-under-the-Long-Hill Crossing, Eastern Reach #" + i);
        }
        return names;
    }

    // ------------------------------------------------------------------
    // pageCount
    // ------------------------------------------------------------------

    @Test
    void anEmptyOrSinglePageListIsStillOnePage() {
        assertEquals(1, SnapshotPaging.pageCount(0, PER_PAGE));
        assertEquals(1, SnapshotPaging.pageCount(1, PER_PAGE));
        assertEquals(1, SnapshotPaging.pageCount(PER_PAGE, PER_PAGE));
        assertFalse(SnapshotPaging.hasMultiplePages(SnapshotPaging.pageCount(PER_PAGE, PER_PAGE)));
    }

    /** The regression: the 65th community is on a second page rather than silently missing. */
    @Test
    void oneCommunityPastTheCapEarnsASecondPage() {
        assertEquals(2, SnapshotPaging.pageCount(PER_PAGE + 1, PER_PAGE));
        assertTrue(SnapshotPaging.hasMultiplePages(SnapshotPaging.pageCount(PER_PAGE + 1, PER_PAGE)));
        assertEquals(PER_PAGE, SnapshotPaging.pageEnd(0, PER_PAGE, PER_PAGE + 1));
        assertEquals(PER_PAGE, SnapshotPaging.pageStart(1, PER_PAGE, PER_PAGE + 1));
        assertEquals(PER_PAGE + 1, SnapshotPaging.pageEnd(1, PER_PAGE, PER_PAGE + 1));
    }

    @Test
    void aFullLastPageDoesNotProduceAnEmptyOneAfterIt() {
        assertEquals(2, SnapshotPaging.pageCount(PER_PAGE * 2, PER_PAGE));
        assertEquals(3, SnapshotPaging.pageCount(PER_PAGE * 2 + 1, PER_PAGE));
    }

    // ------------------------------------------------------------------
    // clampPage
    // ------------------------------------------------------------------

    @Test
    void aPageOutsideTheRangeIsClampedRatherThanWrapped() {
        assertEquals(0, SnapshotPaging.clampPage(-4, 3));
        assertEquals(2, SnapshotPaging.clampPage(9, 3));
        assertEquals(0, SnapshotPaging.clampPage(5, 1));
    }

    // ------------------------------------------------------------------
    // Slicing
    // ------------------------------------------------------------------

    @Test
    void everyCommunityAppearsOnExactlyOnePageWhateverItsNameIsCalled() {
        List<String> all = villages(PER_PAGE * 2 + 7);
        int pages = SnapshotPaging.pageCount(all.size(), PER_PAGE);
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < pages; page++) {
            seen.addAll(all.subList(SnapshotPaging.pageStart(page, PER_PAGE, all.size()),
                    SnapshotPaging.pageEnd(page, PER_PAGE, all.size())));
        }
        assertEquals(all, seen, "paging must partition the list, not sample it");
    }

    @Test
    void aPagePastTheEndSlicesNothingRatherThanThrowing() {
        List<String> all = villages(3);
        assertEquals(0, SnapshotPaging.pageStart(7, PER_PAGE, all.size()));
        assertEquals(3, SnapshotPaging.pageEnd(7, PER_PAGE, all.size()));
        assertEquals(0, SnapshotPaging.pageStart(0, PER_PAGE, 0));
        assertEquals(0, SnapshotPaging.pageEnd(0, PER_PAGE, 0));
    }

    // ------------------------------------------------------------------
    // nextPage
    // ------------------------------------------------------------------

    @Test
    void turningPagesWrapsLikeTheVillageSelectorDoes() {
        assertEquals(1, SnapshotPaging.nextPage(0, 3, 1));
        assertEquals(0, SnapshotPaging.nextPage(2, 3, 1));
        assertEquals(2, SnapshotPaging.nextPage(0, 3, -1));
    }

    @Test
    void aSinglePageHasNowhereToTurnTo() {
        assertEquals(0, SnapshotPaging.nextPage(0, 1, 1));
        assertEquals(0, SnapshotPaging.nextPage(0, 1, -1));
        assertEquals(0, SnapshotPaging.nextPage(4, 1, 1));
    }
}

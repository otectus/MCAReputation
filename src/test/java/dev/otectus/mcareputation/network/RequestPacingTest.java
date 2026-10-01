package dev.otectus.mcareputation.network;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server-side snapshot pacing (§27.2): one answer per player per cooldown, and a request inside
 * the window deferred to its end rather than dropped.
 *
 * <p>The drop is the regression. The client paces to the same cooldown on its own clock, so jitter or
 * a server below 20 TPS delivered an honestly paced request inside the server's window; it was
 * discarded, nothing replied, and the standing screen kept showing the previous village.
 */
class RequestPacingTest {

    private static final String PLAYER = "player";
    private static final String OTHER = "other";

    @Test
    void theFirstRequestIsAnsweredAtOnce() {
        RequestPacing<String, String> pacing = new RequestPacing<>(10);
        assertTrue(pacing.offer(PLAYER, "home", 100L));
        assertFalse(pacing.hasDeferred());
    }

    /** The regression: a request eight ticks after the last one — jitter, or 15 TPS — is kept. */
    @Test
    void aRequestInsideTheWindowIsDeferredNotDropped() {
        RequestPacing<String, String> pacing = new RequestPacing<>(10);
        pacing.offer(PLAYER, "home", 100L);

        assertFalse(pacing.offer(PLAYER, "nether", 108L), "inside the window: not answered yet");
        assertTrue(pacing.hasDeferred(), "but parked, not discarded");
        assertEquals(List.of(), pacing.due(109L), "still inside the window");
        assertEquals(List.of(Map.entry(PLAYER, "nether")), pacing.due(110L),
                "answered the moment the window closes");
        assertFalse(pacing.hasDeferred());
        assertEquals(List.of(), pacing.due(111L), "and only once");
    }

    /** Two fast selector clicks inside one window: only the village the player ended on is answered. */
    @Test
    void theNewestDeferredRequestWins() {
        RequestPacing<String, String> pacing = new RequestPacing<>(10);
        pacing.offer(PLAYER, "first", 100L);
        pacing.offer(PLAYER, "second", 102L);
        pacing.offer(PLAYER, "third", 105L);
        assertEquals(List.of(Map.entry(PLAYER, "third")), pacing.due(110L));
    }

    /** The cost bound the old drop existed for still holds: a deferred answer restarts the window. */
    @Test
    void aDeferredAnswerStartsTheNextWindow() {
        RequestPacing<String, String> pacing = new RequestPacing<>(10);
        pacing.offer(PLAYER, "first", 100L);
        pacing.offer(PLAYER, "second", 105L);
        pacing.due(110L);

        assertFalse(pacing.offer(PLAYER, "third", 115L), "115 is inside the window opened at 110");
        assertEquals(List.of(Map.entry(PLAYER, "third")), pacing.due(120L));
    }

    @Test
    void anAnswerAfterTheWindowNeedsNoDeferral() {
        RequestPacing<String, String> pacing = new RequestPacing<>(10);
        pacing.offer(PLAYER, "first", 100L);
        assertTrue(pacing.offer(PLAYER, "second", 110L));
        assertFalse(pacing.hasDeferred());
    }

    /** An answered request clears any older parked wish: it was superseded by the one just answered. */
    @Test
    void anImmediateAnswerDiscardsAStaleParkedWish() {
        RequestPacing<String, String> pacing = new RequestPacing<>(10);
        pacing.offer(PLAYER, "first", 100L);
        pacing.offer(PLAYER, "parked", 105L);
        assertTrue(pacing.offer(PLAYER, "newest", 130L));
        assertEquals(List.of(), pacing.due(200L), "the parked wish must not be answered after a newer one");
    }

    @Test
    void playersArePacedIndependently() {
        RequestPacing<String, String> pacing = new RequestPacing<>(10);
        pacing.offer(PLAYER, "home", 100L);
        assertTrue(pacing.offer(OTHER, "home", 101L), "one player's window never delays another's");
    }

    @Test
    void forgettingAPlayerDropsTheirParkedRequest() {
        RequestPacing<String, String> pacing = new RequestPacing<>(10);
        pacing.offer(PLAYER, "home", 100L);
        pacing.offer(PLAYER, "nether", 105L);
        pacing.forget(PLAYER);
        assertFalse(pacing.hasDeferred());
        assertTrue(pacing.offer(PLAYER, "again", 106L), "a returning player starts with no window");
    }

    /** A second world in the same JVM, before clear(): a clock that went backwards answers now. */
    @Test
    void aClockThatWentBackwardsNeverWaits() {
        RequestPacing<String, String> pacing = new RequestPacing<>(10);
        pacing.offer(PLAYER, "home", 50_000L);
        assertTrue(pacing.offer(PLAYER, "home", 20L));
    }

    @Test
    void clearDropsEverything() {
        RequestPacing<String, String> pacing = new RequestPacing<>(10);
        pacing.offer(PLAYER, "home", 100L);
        pacing.offer(PLAYER, "nether", 105L);
        pacing.clear();
        assertFalse(pacing.hasDeferred());
        assertTrue(pacing.offer(PLAYER, "home", 106L));
    }
}

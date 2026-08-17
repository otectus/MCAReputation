package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client-side request pacing that keeps the standing screen honest (§27.2, §28.2): requests
 * inside the server's silent cooldown are parked instead of vanishing, the newest wish wins, and a
 * reply that never comes times out into the retryable empty state instead of an eternal spinner.
 */
class RequestThrottleTest {

    private static final RequestThrottle.Request ANY =
            new RequestThrottle.Request(0, Optional.empty());
    private static final RequestThrottle.Request HOME =
            new RequestThrottle.Request(0, Optional.of(TestFixtures.OVERWORLD_3));
    private static final RequestThrottle.Request NETHER =
            new RequestThrottle.Request(0, Optional.of(TestFixtures.NETHER_3));

    @Test
    void theFirstRequestGoesOutImmediately() {
        RequestThrottle throttle = new RequestThrottle(10, 60);
        assertEquals(Optional.of(ANY), throttle.offer(ANY, 100L));
        assertTrue(throttle.awaiting(100L));
    }

    @Test
    void aRequestInsideTheCooldownIsParkedNotDropped() {
        RequestThrottle throttle = new RequestThrottle(10, 60);
        throttle.offer(ANY, 100L);
        assertTrue(throttle.offer(HOME, 102L).isEmpty(),
                "sending now would land in the server's silent drop window");
        assertEquals(Optional.of(HOME), throttle.due(110L),
                "the parked wish goes out the moment the cooldown allows");
        assertTrue(throttle.due(111L).isEmpty(), "and only once");
    }

    /** Two fast selector clicks: only the village the player ended on matters. */
    @Test
    void theNewestParkedWishWins() {
        RequestThrottle throttle = new RequestThrottle(10, 60);
        throttle.offer(ANY, 100L);
        throttle.offer(HOME, 101L);
        throttle.offer(NETHER, 103L);
        assertEquals(Optional.of(NETHER), throttle.due(110L));
    }

    @Test
    void aReplyClearsAwaiting() {
        RequestThrottle throttle = new RequestThrottle(10, 60);
        throttle.offer(ANY, 100L);
        throttle.onReply();
        assertFalse(throttle.awaiting(101L));
    }

    /** The regression that motivated the class: no reply must not mean "asking around…" forever. */
    @Test
    void anUnansweredRequestTimesOutIntoTheRetryableState() {
        RequestThrottle throttle = new RequestThrottle(10, 60);
        throttle.offer(ANY, 100L);
        assertTrue(throttle.awaiting(159L), "still inside the timeout");
        assertFalse(throttle.awaiting(161L), "timed out: the screen may show its empty state");
    }

    @Test
    void sendingIsAllowedAgainAfterTheCooldown() {
        RequestThrottle throttle = new RequestThrottle(10, 60);
        throttle.offer(ANY, 100L);
        assertEquals(Optional.of(HOME), throttle.offer(HOME, 110L));
    }

    @Test
    void resetForgetsEverything() {
        RequestThrottle throttle = new RequestThrottle(10, 60);
        throttle.offer(ANY, 100L);
        throttle.offer(HOME, 101L);
        throttle.reset();
        assertFalse(throttle.awaiting(101L));
        assertTrue(throttle.due(200L).isEmpty(), "nothing stays parked across a disconnect");
        assertEquals(Optional.of(NETHER), throttle.offer(NETHER, 102L),
                "a fresh world may ask immediately");
    }
}

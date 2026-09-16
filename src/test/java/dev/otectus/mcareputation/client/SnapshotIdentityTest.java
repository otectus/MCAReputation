package dev.otectus.mcareputation.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stale-reply rule (§18.3), with no client running.
 *
 * <p>The failure this guards is a late reply applying the wrong village's or villager's profile after
 * the player has moved on — invisible in play, because the wrong answer looks exactly like a right
 * one, and trivial here.
 */
class SnapshotIdentityTest {

    @Test
    void theReplyToTheNewestRequestIsAccepted() {
        SnapshotIdentity identity = new SnapshotIdentity();
        int first = identity.stamp();
        assertTrue(identity.accepts(first));
    }

    @Test
    void aReplyToASupersededRequestIsRejected() {
        SnapshotIdentity identity = new SnapshotIdentity();
        int first = identity.stamp();
        int second = identity.stamp();

        assertNotEquals(first, second, "every request gets its own stamp");
        assertFalse(identity.accepts(first),
                "the earlier question is no longer on screen, so its answer must not be applied");
        assertTrue(identity.accepts(second));
    }

    /** A server push answers nobody's question and is a fresh answer by definition. */
    @Test
    void anUnsolicitedSnapshotIsAlwaysAccepted() {
        SnapshotIdentity identity = new SnapshotIdentity();
        assertTrue(identity.accepts(SnapshotIdentity.UNSOLICITED),
                "before any request: a pushed snapshot is the only thing that can arrive");
        identity.stamp();
        identity.stamp();
        assertTrue(identity.accepts(SnapshotIdentity.UNSOLICITED),
                "and with two requests outstanding it is still a push, not a stale reply");
    }

    /** Nothing sent, nothing outstanding: a reply carrying a stamp cannot be ours. */
    @Test
    void aStampedReplyWithNothingOutstandingIsRejected() {
        SnapshotIdentity identity = new SnapshotIdentity();
        assertEquals(SnapshotIdentity.UNSOLICITED, identity.outstanding());
        assertFalse(identity.accepts(1));
        assertFalse(identity.accepts(-4), "and a forged stamp is not a push either");
    }

    /**
     * The counter never returns to zero by wrapping, because zero means "nobody asked" — a wrapped
     * stamp of zero would make every stale reply acceptable exactly once per cycle.
     */
    @Test
    void stampsWrapWithoutEverReachingTheUnsolicitedValue() {
        SnapshotIdentity identity = new SnapshotIdentity();
        for (int i = 0; i < SnapshotIdentity.MAX_ID + 2; i++) {
            assertNotEquals(SnapshotIdentity.UNSOLICITED, identity.stamp(),
                    "stamp " + i + " collided with the reserved push value");
        }
        assertEquals(2, identity.outstanding(), "and the counter is back near the start, not at zero");
    }

    /** A reply in flight across a disconnect must not be accepted in the next world. */
    @Test
    void aResetRejectsTheReplyToTheRequestItForgot() {
        SnapshotIdentity identity = new SnapshotIdentity();
        int outstanding = identity.stamp();
        identity.reset();

        assertFalse(identity.accepts(outstanding));
        assertTrue(identity.accepts(SnapshotIdentity.UNSOLICITED));
        assertEquals(outstanding, identity.stamp(), "and the next world starts counting from one");
    }
}

package dev.otectus.mcareputation.event;

import dev.otectus.mcareputation.event.ScoreboardOwnership.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scoreboard ownership decision table (DD13, §5 F16 "Respect scoreboard ownership").
 *
 * <p>Pure booleans in, one decision out, so every case an operator can actually create is pinned
 * without a server: the objective that is ours, the objective an admin made by hand with the same
 * name, and the objective another mod drives off a real criteria.
 */
class ScoreboardOwnershipTest {

    @Test
    void nothingThereAndTheFeatureOnCreatesOurs() {
        assertEquals(Decision.CREATE, ScoreboardOwnership.decide(false, false, false, true));
        assertTrue(ScoreboardOwnership.mayWrite(Decision.CREATE));
    }

    @Test
    void nothingThereAndTheFeatureOffDoesNothing() {
        assertEquals(Decision.NOTHING, ScoreboardOwnership.decide(false, false, false, false));
        assertFalse(ScoreboardOwnership.mayWrite(Decision.NOTHING));
    }

    @Test
    void bothMarksTogetherProveItIsOurs() {
        assertEquals(Decision.ADOPT, ScoreboardOwnership.decide(true, true, true, true));
        assertTrue(ScoreboardOwnership.mayWrite(Decision.ADOPT));
    }

    /**
     * Either mark alone is not enough. A hand-made dummy objective with the configured name is an
     * operator's display, and a display name that matches on a real criteria is not something we could
     * have created at all.
     */
    @Test
    void eitherMarkAloneIsRefused() {
        assertEquals(Decision.REFUSE, ScoreboardOwnership.decide(true, true, false, true));
        assertEquals(Decision.REFUSE, ScoreboardOwnership.decide(true, false, true, true));
        assertEquals(Decision.REFUSE, ScoreboardOwnership.decide(true, false, false, true));
        assertFalse(ScoreboardOwnership.mayWrite(Decision.REFUSE));
    }

    /** A foreign objective is never removed either — refusing is smaller than deleting. */
    @Test
    void aForeignObjectiveIsRefusedRatherThanRemovedWhenTheFeatureIsOff() {
        assertEquals(Decision.REFUSE, ScoreboardOwnership.decide(true, true, false, false));
        assertEquals(Decision.REFUSE, ScoreboardOwnership.decide(true, false, true, false));
    }

    /** Turning the display off takes down only what we put up. */
    @Test
    void ourOwnObjectiveIsRemovedWhenTheFeatureIsOff() {
        assertEquals(Decision.REMOVE, ScoreboardOwnership.decide(true, true, true, false));
        assertFalse(ScoreboardOwnership.mayWrite(Decision.REMOVE));
    }
}

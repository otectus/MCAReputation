package dev.otectus.mcareputation.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §12.2's bounded policy epochs on their own: what the log claims was frozen, and what it does when it
 * runs out of room or is asked about a clock that went backwards.
 *
 * <p>Every bound here is checked in the direction that keeps evidence rather than the direction that
 * hands back skipped aging. Over-reporting a freeze costs a little accuracy; under-reporting one is the
 * catch-up burst this whole mechanism exists to refuse (I13).
 */
class ProfileFreezeLogTest {

    private static final long DAY = 24_000L;

    @Test
    void anUnobservedLogSubtractsNothing() {
        ProfileFreezeLog log = new ProfileFreezeLog();

        assertFalse(log.observed());
        assertEquals(0L, log.frozenTicksBetween(0L, 10 * DAY),
                "with nothing observed, the honest answer is that nothing is known to be frozen");
    }

    @Test
    void oneClosedIntervalIsSubtractedAndNothingElseIs() {
        ProfileFreezeLog log = new ProfileFreezeLog();
        log.observe(false, 0L);
        log.observe(true, 2 * DAY);
        log.observe(false, 12 * DAY);

        assertEquals(10 * DAY, log.frozenTicksBetween(0L, 12 * DAY));
        assertEquals(0L, log.frozenTicksBetween(0L, 2 * DAY), "the active prefix is not frozen");
        assertEquals(0L, log.frozenTicksBetween(12 * DAY, 20 * DAY), "nor the active suffix");
        assertEquals(DAY, log.frozenTicksBetween(DAY, 3 * DAY), "and a straddling query overlaps once");
        assertEquals(1, log.intervalCount());
    }

    @Test
    void aFreezeStillInProgressCountsUpToTheEvaluationTime() {
        ProfileFreezeLog log = new ProfileFreezeLog();
        log.observe(false, 0L);
        log.observe(true, 2 * DAY);

        assertTrue(log.frozen());
        assertEquals(3 * DAY, log.frozenTicksBetween(0L, 5 * DAY),
                "the state is frozen at the evaluation time, so the interval is frozen at its end");
    }

    @Test
    void aFreezeAlreadyInProgressAtTheFirstObservationSwallowsEverythingBeforeIt() {
        ProfileFreezeLog log = new ProfileFreezeLog();

        // A server that loaded with profiles switched off: there is no observed start.
        log.observe(true, 5 * DAY);

        assertEquals(5 * DAY, log.frozenTicksBetween(0L, 5 * DAY),
                "an interval nobody could have observed is treated as frozen, never as catch-up");
        log.observe(false, 6 * DAY);
        assertEquals(6 * DAY, log.frozenTicksBetween(0L, 6 * DAY));
        assertEquals(0L, log.frozenTicksBetween(6 * DAY, 8 * DAY));
    }

    @Test
    void observationIsMonotonicSoARewoundClockOpensNoInterval() {
        ProfileFreezeLog log = new ProfileFreezeLog();
        log.observe(false, 10 * DAY);
        log.observe(true, 0L);
        log.observe(false, DAY);

        assertEquals(0L, log.frozenTicksBetween(0L, 10 * DAY),
                "/time set into the past cannot manufacture a frozen interval behind us");
        assertEquals(0L, log.frozenTicksBetween(10 * DAY, 11 * DAY));
    }

    @Test
    void backToBackIntervalsCoalesceRatherThanConsumingSlots() {
        ProfileFreezeLog log = new ProfileFreezeLog();
        log.observe(false, 0L);
        for (int i = 0; i < 5; i++) {
            log.observe(true, DAY);
            log.observe(false, DAY);
        }

        assertEquals(0, log.intervalCount(),
                "a freeze that started and ended at one game time froze no ticks and takes no slot");
        assertEquals(0L, log.frozenTicksBetween(0L, 10 * DAY));
    }

    @Test
    void theLogIsBoundedAndOverflowsTowardMoreFreezingNotLess() {
        ProfileFreezeLog log = new ProfileFreezeLog();
        log.observe(false, 0L);
        long time = DAY;
        int cycles = ProfileFreezeLog.MAX_INTERVALS + 8;
        for (int i = 0; i < cycles; i++) {
            log.observe(true, time);
            log.observe(false, time + DAY);
            time += 2 * DAY;
        }

        assertTrue(log.intervalCount() <= ProfileFreezeLog.MAX_INTERVALS,
                "growing structures have hard bounds (I13)");
        long frozen = log.frozenTicksBetween(0L, time);
        assertTrue(frozen >= cycles * DAY,
                "merging the oldest pair reports more frozen time, never less: " + frozen);
        assertTrue(frozen <= time, "and never more than the interval asked about");
    }

    @Test
    void clearForgetsEverything() {
        ProfileFreezeLog log = new ProfileFreezeLog();
        log.observe(true, 0L);
        log.observe(false, DAY);
        log.clear();

        assertFalse(log.observed());
        assertFalse(log.frozen());
        assertEquals(0, log.intervalCount());
        assertEquals(0L, log.frozenTicksBetween(0L, 10 * DAY));
    }
}

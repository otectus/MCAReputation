package dev.otectus.mcareputation.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §16.3 #7, as a unit test rather than a GameTest: the post-mitigation damage number, and only
 * it, decides whether a swing becomes a deed.
 *
 * <p>A GameTest would need a live MCA villager, so the decision itself is extracted into
 * {@link ReputationGameplayEvents#isRecordableAssault} and pinned here. What is being defended is a
 * silent regression: swap {@code getNewDamage()} back for a pre-mitigation number, or drop the zero
 * check, and every armoured chip hit starts filing assault charges — with nothing in the build going
 * red.
 *
 * <p>The coalescing window is a separate decision and is checked directly against
 * {@link AssaultTracker}, which is the only part of that path with no server in it.
 */
class DamageHookTest {

    @BeforeEach
    void reset() {
        AssaultTracker.clear();
    }

    /** A swing that cost no health is never a deed, whatever the threshold is set to. */
    @Test
    void zeroDamageIsNeverRecordable() {
        assertFalse(ReputationGameplayEvents.isRecordableAssault(0.0F, 1.0D));
        // The zero check is independent of the threshold: min=0 must still reject a costless hit.
        assertFalse(ReputationGameplayEvents.isRecordableAssault(0.0F, 0.0D));
        assertFalse(ReputationGameplayEvents.isEffectiveHit(0.0F));
    }

    /** Chip damage under {@code minimumIncidentDamage} is what the option exists to ignore. */
    @Test
    void subThresholdDamageIsRejected() {
        assertFalse(ReputationGameplayEvents.isRecordableAssault(0.5F, 1.0D));
        assertFalse(ReputationGameplayEvents.isRecordableAssault(0.99F, 1.0D));
        // It still counted as a hit, though — that is what opens the self-defence window.
        assertTrue(ReputationGameplayEvents.isEffectiveHit(0.5F));
    }

    /** The threshold is inclusive, and anything above it obviously qualifies. */
    @Test
    void thresholdAndAboveAreRecordable() {
        assertTrue(ReputationGameplayEvents.isRecordableAssault(1.0F, 1.0D));
        assertTrue(ReputationGameplayEvents.isRecordableAssault(4.0F, 1.0D));
        assertTrue(ReputationGameplayEvents.isRecordableAssault(0.25F, 0.25D));
    }

    /**
     * §20.1's self-defence window: the villager-hit-first fact the handler records is only a defence
     * inside the window, and a wound-back clock is not one.
     */
    @Test
    void selfDefenceWindowBoundsTheRememberedHit() {
        UUID villager = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        AssaultTracker.recordVillagerHitPlayer(villager, player, 1_000L);

        assertTrue(AssaultTracker.villagerStruckFirst(villager, player, 1_000L, 100),
                "the same tick is inside the window");
        assertTrue(AssaultTracker.villagerStruckFirst(villager, player, 1_100L, 100),
                "the window is inclusive at its far edge");
        assertFalse(AssaultTracker.villagerStruckFirst(villager, player, 1_101L, 100),
                "one tick past the window is not a defence");
        assertFalse(AssaultTracker.villagerStruckFirst(villager, player, 900L, 100),
                "\"the villager hit me in the future\" is not a defence");
        assertFalse(AssaultTracker.villagerStruckFirst(villager, player, 1_000L, 0),
                "a window of zero disables self-defence entirely");
        assertFalse(AssaultTracker.villagerStruckFirst(UUID.randomUUID(), player, 1_000L, 100),
                "another villager's swing is not this one's defence");
    }

    /** Stale pairs are dropped, so a long session does not carry a defence forward forever. */
    @Test
    void sweepDropsEntriesOutsideTheWindow() {
        UUID villager = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        AssaultTracker.recordVillagerHitPlayer(villager, player, 1_000L);
        assertEquals(1, AssaultTracker.size());

        AssaultTracker.sweep(1_050L, 100);
        assertEquals(1, AssaultTracker.size(), "still inside the window");

        AssaultTracker.sweep(2_000L, 100);
        assertEquals(0, AssaultTracker.size());
    }
}

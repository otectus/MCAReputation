package dev.otectus.mcareputation.event;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The anti-farm arithmetic behind the rescue, cure, raid and PvP deeds (§14.2, §20.1).
 *
 * <p>These keys are the only thing standing between a rescue worth +6 and a player kiting the same
 * skeleton back to the same villager all afternoon, so the bucket boundaries are worth pinning
 * exactly. {@link DeedKeys} takes UUIDs and tick counts and nothing else, which is what lets this run
 * without a server, a level, or a registry.
 */
class DeedKeysTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID VILLAGER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OTHER_VILLAGER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    void everyTickInsideOneBucketShareItsIndex() {
        assertEquals(0L, DeedKeys.bucket(0L, 6000));
        assertEquals(0L, DeedKeys.bucket(5999L, 6000));
        assertEquals(1L, DeedKeys.bucket(6000L, 6000));
        assertEquals(1L, DeedKeys.bucket(11999L, 6000));
        assertEquals(2L, DeedKeys.bucket(12000L, 6000));
    }

    /**
     * {@code floorDiv}, not {@code /}. A game time can be negative in a world whose clock has been
     * set backwards, and integer division would fold ticks -5999..5999 into one bucket around zero.
     */
    @Test
    void bucketsDoNotWidenAroundZero() {
        assertEquals(-1L, DeedKeys.bucket(-1L, 6000));
        assertEquals(-1L, DeedKeys.bucket(-6000L, 6000));
        assertEquals(-2L, DeedKeys.bucket(-6001L, 6000));
    }

    /** {@code rescueCoalesceTicks = 0} is the documented way to switch coalescing off entirely. */
    @Test
    void aZeroBucketMakesEveryTickItsOwnDeed() {
        assertEquals(4321L, DeedKeys.bucket(4321L, 0));
        assertNotEquals(DeedKeys.bucket(4321L, 0), DeedKeys.bucket(4322L, 0));
        assertEquals(4321L, DeedKeys.bucket(4321L, -1));
    }

    @Test
    void twoRescuesInOneBucketShareAKey() {
        assertEquals(DeedKeys.rescue(PLAYER, VILLAGER, DeedKeys.bucket(100L, 6000)),
                DeedKeys.rescue(PLAYER, VILLAGER, DeedKeys.bucket(5900L, 6000)));
    }

    @Test
    void crossingABucketBoundaryEarnsAgain() {
        assertNotEquals(DeedKeys.rescue(PLAYER, VILLAGER, DeedKeys.bucket(5999L, 6000)),
                DeedKeys.rescue(PLAYER, VILLAGER, DeedKeys.bucket(6000L, 6000)));
    }

    /** Saving two different neighbours in one bucket is two deeds, not one. */
    @Test
    void rescuesOfDifferentVillagersNeverCollide() {
        assertNotEquals(DeedKeys.rescue(PLAYER, VILLAGER, 0L),
                DeedKeys.rescue(PLAYER, OTHER_VILLAGER, 0L));
        assertNotEquals(DeedKeys.rescue(PLAYER, VILLAGER, 0L),
                DeedKeys.rescue(OTHER_PLAYER, VILLAGER, 0L));
    }

    /** A cure has no time component: one villager can only be brought back once by one player. */
    @Test
    void aCureKeyIsTheCurerAndTheVillagerAlone() {
        assertEquals(DeedKeys.cure(PLAYER, VILLAGER), DeedKeys.cure(PLAYER, VILLAGER));
        assertNotEquals(DeedKeys.cure(PLAYER, VILLAGER), DeedKeys.cure(OTHER_PLAYER, VILLAGER));
        assertNotEquals(DeedKeys.cure(PLAYER, VILLAGER), DeedKeys.cure(PLAYER, OTHER_VILLAGER));
    }

    /** Hero of the village is re-applied on refresh; every one of those firings is one victory. */
    @Test
    void aRaidKeyIgnoresWhenTheEffectWasApplied() {
        assertEquals(DeedKeys.raid(PLAYER, "minecraft:overworld/3", 7),
                DeedKeys.raid(PLAYER, "minecraft:overworld/3", 7));
        assertNotEquals(DeedKeys.raid(PLAYER, "minecraft:overworld/3", 7),
                DeedKeys.raid(PLAYER, "minecraft:overworld/3", 8));
        assertNotEquals(DeedKeys.raid(PLAYER, "minecraft:overworld/3", 7),
                DeedKeys.raid(PLAYER, "minecraft:the_nether/3", 7));
    }

    /** The PvP key is the exact tick, so only a duplicated death event is absorbed. */
    @Test
    void aPvpKeyIsExactToTheTick() {
        assertEquals(DeedKeys.pvp(PLAYER, OTHER_PLAYER, 500L),
                DeedKeys.pvp(PLAYER, OTHER_PLAYER, 500L));
        assertNotEquals(DeedKeys.pvp(PLAYER, OTHER_PLAYER, 500L),
                DeedKeys.pvp(PLAYER, OTHER_PLAYER, 501L));
        assertNotEquals(DeedKeys.pvp(PLAYER, OTHER_PLAYER, 500L),
                DeedKeys.pvp(OTHER_PLAYER, PLAYER, 500L));
    }

    /** No two families may collide, or a cure could be swallowed as a duplicate rescue. */
    @Test
    void theFourFamiliesDoNotOverlap() {
        assertNotEquals(DeedKeys.rescue(PLAYER, VILLAGER, 0L), DeedKeys.cure(PLAYER, VILLAGER));
        assertNotEquals(DeedKeys.rescue(PLAYER, VILLAGER, 0L),
                DeedKeys.pvp(PLAYER, VILLAGER, 0L));
        assertNotEquals(DeedKeys.cure(PLAYER, VILLAGER), DeedKeys.raid(PLAYER, VILLAGER.toString(), 0));
    }
}

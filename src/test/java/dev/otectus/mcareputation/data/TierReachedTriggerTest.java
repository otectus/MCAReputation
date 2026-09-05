package dev.otectus.mcareputation.data;

import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §36.1 group 14: the truth table for {@code mcareputation:tier_reached}.
 *
 * <p>The test goes through the pure {@code TierReachedTrigger.matches} rather than through an
 * {@code Instance}, because building one needs a {@code ContextAwarePredicate} and that class
 * initialises against the loot registries — which a plain unit test must not load.
 */
class TierReachedTriggerTest {

    private static final CommunityKey OVERWORLD_3 =
            new CommunityKey(ResourceLocation.parse("minecraft:overworld"), 3);
    private static final CommunityKey NETHER_3 =
            new CommunityKey(ResourceLocation.parse("minecraft:the_nether"), 3);

    private static final Optional<String> ANY_TIER = Optional.empty();
    private static final Optional<CommunityKey> ANY_COMMUNITY = Optional.empty();

    @Test
    void anyTierAnywhereMatchesAnyUpwardCrossing() {
        assertTrue(TierReachedTrigger.matches(ANY_TIER, ANY_COMMUNITY, true, "friend", OVERWORLD_3, true));
        assertTrue(TierReachedTrigger.matches(ANY_TIER, ANY_COMMUNITY, true, "stranger", NETHER_3, true));
    }

    @Test
    void specificTierIgnoresEveryOtherRung() {
        Optional<String> friend = Optional.of("friend");
        assertTrue(TierReachedTrigger.matches(friend, ANY_COMMUNITY, true, "friend", OVERWORLD_3, true));
        assertFalse(TierReachedTrigger.matches(friend, ANY_COMMUNITY, true, "honored", OVERWORLD_3, true));
    }

    @Test
    void specificCommunityIgnoresOtherVillages() {
        Optional<CommunityKey> here = Optional.of(OVERWORLD_3);
        assertTrue(TierReachedTrigger.matches(ANY_TIER, here, true, "friend", OVERWORLD_3, true));
        assertFalse(TierReachedTrigger.matches(ANY_TIER, here, true, "friend", NETHER_3, true));
    }

    @Test
    void downwardIsSuppressedByDefault() {
        assertFalse(TierReachedTrigger.matches(ANY_TIER, ANY_COMMUNITY, true, "stranger", OVERWORLD_3, false));
    }

    @Test
    void downwardMatchesWhenUpwardOnlyIsOff() {
        assertTrue(TierReachedTrigger.matches(ANY_TIER, ANY_COMMUNITY, false, "stranger", OVERWORLD_3, false));
        assertTrue(TierReachedTrigger.matches(ANY_TIER, ANY_COMMUNITY, false, "friend", OVERWORLD_3, true));
    }

    @Test
    void everyFieldMustAgree() {
        Optional<String> honored = Optional.of("honored");
        Optional<CommunityKey> here = Optional.of(OVERWORLD_3);
        assertTrue(TierReachedTrigger.matches(honored, here, true, "honored", OVERWORLD_3, true));
        assertFalse(TierReachedTrigger.matches(honored, here, true, "honored", NETHER_3, true));
        assertFalse(TierReachedTrigger.matches(honored, here, true, "friend", OVERWORLD_3, true));
        assertFalse(TierReachedTrigger.matches(honored, here, true, "honored", OVERWORLD_3, false));
    }
}

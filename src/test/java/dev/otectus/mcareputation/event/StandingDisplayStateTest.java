package dev.otectus.mcareputation.event;

import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.event.StandingDisplay.Shown;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decisions behind the scoreboard row and the tab-list suffix (§28.4).
 *
 * <p>Everything worth pinning here is a comparison between two cached values, so it is kept out of the
 * event handlers and tested with no server, no level and no scoreboard. The distinction that matters
 * is the one between the two questions: the scoreboard follows the score, the tab list follows only
 * the tier, and conflating them would resend a player-info packet on every point earned.
 */
class StandingDisplayStateTest {

    private static final CommunityKey VILLAGE =
            new CommunityKey(new ResourceLocation("minecraft", "overworld"), 3);
    private static final CommunityKey OTHER_VILLAGE =
            new CommunityKey(new ResourceLocation("minecraft", "overworld"), 4);

    @Test
    void anUnchangedValueRefreshesNothing() {
        Shown shown = new Shown(VILLAGE, 40, "acquaintance");
        assertFalse(StandingDisplay.shouldRefresh(shown, new Shown(VILLAGE, 40, "acquaintance")));
        assertFalse(StandingDisplay.tierChanged(shown, new Shown(VILLAGE, 40, "acquaintance")));
    }

    @Test
    void aScoreThatMovesWithinOneTierRefreshesTheScoreboardOnly() {
        Shown before = new Shown(VILLAGE, 40, "acquaintance");
        Shown after = new Shown(VILLAGE, 55, "acquaintance");
        assertTrue(StandingDisplay.shouldRefresh(before, after));
        assertFalse(StandingDisplay.tierChanged(before, after));
    }

    @Test
    void crossingATierRefreshesBoth() {
        Shown before = new Shown(VILLAGE, 70, "acquaintance");
        Shown after = new Shown(VILLAGE, 80, "friend");
        assertTrue(StandingDisplay.shouldRefresh(before, after));
        assertTrue(StandingDisplay.tierChanged(before, after));
    }

    /** Walking into the next village over changes what is shown even at the same score and tier. */
    @Test
    void anotherVillageAtTheSameStandingIsStillAChange() {
        Shown before = new Shown(VILLAGE, 40, "acquaintance");
        Shown after = new Shown(OTHER_VILLAGE, 40, "acquaintance");
        assertTrue(StandingDisplay.shouldRefresh(before, after));
        assertFalse(StandingDisplay.tierChanged(before, after));
    }

    /** Having nothing to show is a state of its own: appearing and disappearing both count. */
    @Test
    void appearingAndDisappearingAreChanges() {
        Shown shown = new Shown(VILLAGE, 40, "acquaintance");
        assertTrue(StandingDisplay.shouldRefresh(null, shown));
        assertTrue(StandingDisplay.shouldRefresh(shown, null));
        assertTrue(StandingDisplay.tierChanged(null, shown));
        assertTrue(StandingDisplay.tierChanged(shown, null));
        assertFalse(StandingDisplay.shouldRefresh(null, null));
        assertFalse(StandingDisplay.tierChanged(null, null));
    }

    /**
     * The suffix is appended, never substituted: a name another mod already decorated survives, which
     * is the whole reason the hook runs at {@code LOW} priority.
     */
    @Test
    void theSuffixIsAppendedToWhateverNameWasAlreadyBuilt() {
        Component decorated = Component.literal("[Admin] Steve");
        Component result = StandingDisplay.withSuffix(decorated, Component.literal("Friend"));

        assertEquals("[Admin] Steve", result.getString().substring(0, "[Admin] Steve".length()));
        assertEquals(1, result.getSiblings().size());
        assertEquals(Component.translatable("mcareputation.tablist.suffix", Component.literal("Friend")),
                result.getSiblings().get(0));
    }
}

package dev.otectus.mcareputation.network;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.event.ReputationChangedEvent;
import dev.otectus.mcareputation.api.event.ReputationTierChangedEvent;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §27.3 merging, per community. The buffer must never mix one village's label with another
 * village's score — the bug this guards against had the action-bar tier computed from whichever
 * community's change happened to land last in the shared slot.
 */
class FeedbackMergeTest {

    @AfterEach
    void tearDown() {
        ReputationFeedback.clearAll();
    }

    private static ReputationChangedEvent changed(CommunityKey community, int oldScore, int newScore) {
        return new ReputationChangedEvent(TestFixtures.PLAYER_A, null, community, oldScore, newScore,
                newScore - oldScore, null, null, TestFixtures.SOURCE);
    }

    private static ReputationTierChangedEvent tierChanged(CommunityKey community, String from, String to,
                                                          int fromIndex, int toIndex, boolean firstTime) {
        return new ReputationTierChangedEvent(TestFixtures.PLAYER_A, null, community,
                ReputationTiers.DEFAULT_ID, from, to, fromIndex, toIndex, firstTime);
    }

    @Test
    void sameCommunityDeltasSumIntoOneMessage() {
        ReputationFeedback.onChanged(changed(TestFixtures.OVERWORLD_3, 0, 10));
        ReputationFeedback.onChanged(changed(TestFixtures.OVERWORLD_3, 10, 18));
        ReputationFeedback.onChanged(changed(TestFixtures.OVERWORLD_3, 18, 30));

        Map<CommunityKey, ReputationFeedback.Pending> pending =
                ReputationFeedback.pendingForTest(TestFixtures.PLAYER_A);
        assertEquals(1, pending.size());
        ReputationFeedback.Pending merged = pending.get(TestFixtures.OVERWORLD_3);
        assertEquals(30, merged.totalDelta);
        assertEquals(30, merged.newScore, "the newest score for THIS community");
    }

    @Test
    void twoCommunitiesInOneTickStaySeparate() {
        ReputationFeedback.onChanged(changed(TestFixtures.OVERWORLD_3, 140, 160));
        ReputationFeedback.onTierChanged(tierChanged(TestFixtures.OVERWORLD_3, "friend", "honored",
                6, 7, true));
        ReputationFeedback.onChanged(changed(TestFixtures.NETHER_3, 0, -8));

        Map<CommunityKey, ReputationFeedback.Pending> pending =
                ReputationFeedback.pendingForTest(TestFixtures.PLAYER_A);
        assertEquals(2, pending.size(), "one message per community, never a blend");

        ReputationNetwork.ChangeS2C home = ReputationFeedback.toChangePacket(
                Component.literal("Riverbend"), pending.get(TestFixtures.OVERWORLD_3));
        assertEquals(20, home.delta());
        assertTrue(home.tierChanged());
        assertTrue(home.firstTime());
        assertEquals("honored", tierKey(home.tierName()));

        ReputationNetwork.ChangeS2C nether = ReputationFeedback.toChangePacket(
                Component.literal("Emberfall"), pending.get(TestFixtures.NETHER_3));
        assertEquals(-8, nether.delta());
        assertFalse(nether.tierChanged());
        assertEquals("wary", tierKey(nether.tierName()),
                "the nether village's tier comes from ITS score (-8 => wary), not the overworld's 160");
    }

    @Test
    void aTierOnlyTickCarriesTheTierEventsOwnLabel() {
        ReputationFeedback.onTierChanged(tierChanged(TestFixtures.OVERWORLD_3, "acquaintance", "friend",
                5, 6, false));
        ReputationNetwork.ChangeS2C packet = ReputationFeedback.toChangePacket(
                Component.literal("Riverbend"),
                ReputationFeedback.pendingForTest(TestFixtures.PLAYER_A).get(TestFixtures.OVERWORLD_3));
        assertEquals(0, packet.delta());
        assertTrue(packet.tierChanged());
        assertFalse(packet.firstTime());
        assertEquals("friend", tierKey(packet.tierName()),
                "no score landed this tick; the label must come from the tier event itself");
    }

    @Test
    void zeroDeltaChangesAreNotNews() {
        ReputationFeedback.onChanged(changed(TestFixtures.OVERWORLD_3, 10, 10));
        assertTrue(ReputationFeedback.pendingForTest(TestFixtures.PLAYER_A).isEmpty());
    }

    /** The builtin tier names are translatable components keyed {@code mcareputation.tier.<id>}. */
    private static String tierKey(Component tierName) {
        if (tierName.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) {
            return t.getKey().replace("mcareputation.tier.", "");
        }
        return tierName.getString();
    }
}

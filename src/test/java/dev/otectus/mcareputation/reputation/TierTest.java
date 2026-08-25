package dev.otectus.mcareputation.reputation;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §36.1 group 8. */
class TierTest {

    private static final ReputationTierSet LADDER = ReputationTiers.BUILTIN_DEFAULT;

    /**
     * A tier owns the half-open range {@code [its threshold, the next threshold)}, and the floor
     * additionally catches everything beneath it. Both ends of every band are checked here, because
     * an off-by-one at a threshold is exactly the kind of thing nobody notices until a player is one
     * point short of a title.
     */
    @Test
    void everyDefaultBoundaryResolves() {
        assertEquals("infamous", LADDER.tierFor(Integer.MIN_VALUE).id());
        assertEquals("infamous", LADDER.tierFor(-1000).id());
        assertEquals("infamous", LADDER.tierFor(-300).id());
        assertEquals("infamous", LADDER.tierFor(-151).id());
        assertEquals("hated", LADDER.tierFor(-150).id());
        assertEquals("hated", LADDER.tierFor(-76).id());
        assertEquals("distrusted", LADDER.tierFor(-75).id());
        assertEquals("distrusted", LADDER.tierFor(-26).id());
        assertEquals("wary", LADDER.tierFor(-25).id());
        assertEquals("wary", LADDER.tierFor(-1).id());
        assertEquals("stranger", LADDER.tierFor(0).id());
        assertEquals("stranger", LADDER.tierFor(24).id());
        assertEquals("acquaintance", LADDER.tierFor(25).id());
        assertEquals("acquaintance", LADDER.tierFor(74).id());
        assertEquals("friend", LADDER.tierFor(75).id());
        assertEquals("friend", LADDER.tierFor(149).id());
        assertEquals("honored", LADDER.tierFor(150).id());
        assertEquals("honored", LADDER.tierFor(299).id());
        assertEquals("revered", LADDER.tierFor(300).id());
        assertEquals("revered", LADDER.tierFor(1000).id());
    }

    /** §32.4: the positive thresholds MCA: Quests already shipped must not move. */
    @Test
    void positiveThresholdsMatchTheLegacyQuestsLadderExactly() {
        assertEquals(0, LADDER.byId("stranger").orElseThrow().threshold());
        assertEquals(25, LADDER.byId("acquaintance").orElseThrow().threshold());
        assertEquals(75, LADDER.byId("friend").orElseThrow().threshold());
        assertEquals(150, LADDER.byId("honored").orElseThrow().threshold());
        assertEquals(300, LADDER.byId("revered").orElseThrow().threshold());
    }

    /** §32.4: the title ids players already hold must be preserved. */
    @Test
    void legacyTitleIdsArePreserved() {
        assertEquals("mcaquests:honored_of_village",
                LADDER.byId("honored").orElseThrow().grantsTitle().orElseThrow().toString());
        assertEquals("mcaquests:revered_of_village",
                LADDER.byId("revered").orElseThrow().grantsTitle().orElseThrow().toString());
    }

    @Test
    void theFloorTierCatchesEverythingBelowIt() {
        assertEquals("infamous", LADDER.tierFor(Integer.MIN_VALUE).id());
    }

    @Test
    void transitionsReportDirection() {
        assertTrue(LADDER.transition(70, 80).upward());
        assertTrue(LADDER.transition(80, 70).downward());
        assertFalse(LADDER.transition(76, 80).changed());
        assertEquals("friend", LADDER.transition(70, 80).to().id());
        assertEquals("acquaintance", LADDER.transition(70, 80).from().id());
    }

    @Test
    void transitionsWorkAcrossZeroAndIntoNegativeTiers() {
        ReputationTierSet.Transition fall = LADDER.transition(5, -30);
        assertTrue(fall.downward());
        assertEquals("stranger", fall.from().id());
        assertEquals("distrusted", fall.to().id(), "-30 is below wary's -25 threshold");

        ReputationTierSet.Transition smallerFall = LADDER.transition(5, -10);
        assertTrue(smallerFall.downward());
        assertEquals("wary", smallerFall.to().id());
    }

    /** §17.3: the celebration fires once, and re-entry after a fall does not replay it. */
    @Test
    void highWaterFiresOnceAndDoesNotReplay() {
        assertTrue(LADDER.isNewHighWater("friend", null));
        assertTrue(LADDER.isNewHighWater("friend", "acquaintance"));
        assertFalse(LADDER.isNewHighWater("friend", "friend"));
        assertFalse(LADDER.isNewHighWater("acquaintance", "friend"),
                "dropping back and re-earning a lower tier is not a new best");
        assertTrue(LADDER.isNewHighWater("honored", "friend"));
    }

    @Test
    void unknownHighWaterCountsAsNeverReached() {
        assertTrue(LADDER.isNewHighWater("friend", "a_tier_a_datapack_removed"));
    }

    @Test
    void unknownNewTierIsNeverAHighWater() {
        assertFalse(LADDER.isNewHighWater("no_such_tier", null));
    }

    @Test
    void nextAndPreviousTier() {
        assertEquals("friend", LADDER.nextTier(30).orElseThrow().id());
        assertEquals("acquaintance", LADDER.previousTier(80).orElseThrow().id());
        assertTrue(LADDER.nextTier(1000).isEmpty(), "nothing above the top rung");
        assertTrue(LADDER.previousTier(-1000).isEmpty(), "nothing below the floor");
    }

    /** §30.3: the bias must stay under the 15-point tier margin Conversations relies on. */
    @Test
    void biasesAreClampedAndOnlyApplyToTrustAndRespect() {
        ReputationTier revered = LADDER.byId("revered").orElseThrow();
        assertEquals(4, revered.biasFor("trust"));
        assertEquals(8, revered.biasFor("respect"));
        assertEquals(0, revered.biasFor("warmth"));
        assertEquals(0, revered.biasFor("attraction"));
        assertEquals(0, revered.biasFor("tension"));
        assertEquals(0, revered.biasFor("familiarity"));
        assertEquals(0, revered.biasFor(null));

        for (ReputationTier tier : LADDER.tiers()) {
            assertTrue(Math.abs(tier.biasFor("trust")) <= ReputationTier.BIAS_SHIPPED_LIMIT);
            assertTrue(Math.abs(tier.biasFor("respect")) <= ReputationTier.BIAS_SHIPPED_LIMIT);
            assertTrue(Math.abs(tier.biasFor("respect")) < 15,
                    "must stay under the tier margin so standing colours a check but cannot decide it");
        }
    }

    @Test
    void biasIsHardClampedEvenIfADatapackSlipsThrough() {
        ReputationTier rogue = new ReputationTier("rogue", 0,
                net.minecraft.network.chat.Component.literal("Rogue"), java.util.Optional.empty(),
                14, -14, java.util.Optional.empty());
        assertEquals(ReputationTier.BIAS_SHIPPED_LIMIT, rogue.biasFor("trust"));
        assertEquals(-ReputationTier.BIAS_SHIPPED_LIMIT, rogue.biasFor("respect"));
    }

    // ------------------------------------------------------------------
    // Ladder validation
    // ------------------------------------------------------------------

    @Test
    void nonAscendingThresholdsAreRejected() {
        assertTrue(parseLadder("""
                {"tiers":[
                  {"id":"a","threshold":10,"name":"A"},
                  {"id":"b","threshold":5,"name":"B"}
                ]}""").error().isPresent());
    }

    @Test
    void equalThresholdsAreRejected() {
        assertTrue(parseLadder("""
                {"tiers":[
                  {"id":"a","threshold":10,"name":"A"},
                  {"id":"b","threshold":10,"name":"B"}
                ]}""").error().isPresent());
    }

    @Test
    void duplicateTierIdsAreRejected() {
        assertTrue(parseLadder("""
                {"tiers":[
                  {"id":"a","threshold":0,"name":"A"},
                  {"id":"a","threshold":10,"name":"A again"}
                ]}""").error().isPresent());
    }

    @Test
    void emptyLadderIsRejected() {
        assertTrue(parseLadder("{\"tiers\":[]}").error().isPresent());
    }

    @Test
    void outOfRangeBiasIsRejected() {
        assertTrue(parseLadder("""
                {"tiers":[{"id":"a","threshold":0,"name":"A","respect_bias":15}]}""")
                .error().isPresent());
    }

    /** §22.1: legacy MCA: Quests ladders use a plain string name and must still load. */
    @Test
    void legacyPlainStringNameStillParses() {
        var result = parseLadder("""
                {"tiers":[
                  {"id":"stranger","threshold":0,"name":"Stranger"},
                  {"id":"honored","threshold":150,"name":"Honored","grants_title":"mcaquests:honored_of_village"}
                ]}""");
        assertTrue(result.error().isEmpty(), () -> "should parse: " + result.error());
        ReputationTierSet ladder = result.result().orElseThrow();
        assertEquals("Stranger", ladder.tierFor(0).name().getString());
        assertEquals("mcaquests:honored_of_village",
                ladder.byId("honored").orElseThrow().grantsTitle().orElseThrow().toString());
    }

    @Test
    void componentNameAlsoParses() {
        var result = parseLadder("""
                {"tiers":[{"id":"a","threshold":0,"name":{"translate":"mcareputation.tier.stranger"}}]}""");
        assertTrue(result.error().isEmpty(), () -> "should parse: " + result.error());
    }

    private static com.mojang.serialization.DataResult<ReputationTierSet> parseLadder(String json) {
        return ReputationTierSet.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }
}

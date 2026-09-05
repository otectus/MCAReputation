package dev.otectus.mcareputation.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.otectus.mcareputation.api.ReputationQuery;
import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §36.1 group 14: every {@code mcareputation:standing} block the documentation shows must parse
 * to the query a pack author would expect, and the two fields that cannot fail silently — community
 * and player source — must be rejected loudly when they are wrong.
 *
 * <p>1.21 loot conditions are codec-based, so "rejected" is an error {@code DataResult} rather than a
 * {@code JsonSyntaxException}; the outcomes asserted are otherwise exactly the Forge build's. Parsing
 * touches no registry, and the condition's own {@code test} needs a level and belongs to the manual
 * matrix.
 */
class StandingConditionTest {

    private static DataResult<StandingCondition> parse(String raw) {
        JsonElement json = JsonParser.parseString(raw);
        return StandingCondition.CODEC.codec().parse(JsonOps.INSTANCE, json);
    }

    private static StandingCondition accepted(String raw) {
        DataResult<StandingCondition> result = parse(raw);
        assertTrue(result.result().isPresent(), () -> "expected '" + raw + "' to parse: "
                + result.error().map(DataResult.Error::message).orElse(""));
        return result.result().orElseThrow();
    }

    private static void rejected(String raw) {
        assertTrue(parse(raw).error().isPresent(), () -> "expected '" + raw + "' to be rejected");
    }

    @Test
    void scoreBoundsOnly() {
        ReputationQuery query = accepted("{\"min\": 20, \"max\": 80}").query();
        assertEquals(20, query.min().getAsInt());
        assertEquals(80, query.max().getAsInt());
        assertTrue(query.minTier().isEmpty());
        assertTrue(query.hasTitle().isEmpty());
    }

    @Test
    void tierBounds() {
        ReputationQuery query = accepted("{\"min_tier\": \"friend\", \"max_tier\": \"revered\"}").query();
        assertEquals(Optional.of("friend"), query.minTier());
        assertEquals(Optional.of("revered"), query.maxTier());
        assertTrue(query.min().isEmpty());
    }

    @Test
    void title() {
        ReputationQuery query = accepted("{\"has_title\": \"mcareputation:village_hero\"}").query();
        assertEquals(Optional.of(ResourceLocation.parse("mcareputation:village_hero")), query.hasTitle());
    }

    @Test
    void emptyBlockIsANoOpRatherThanAnError() {
        assertTrue(accepted("{}").query().isEmpty());
    }

    @Test
    void communityDefaultsToHere() {
        assertTrue(accepted("{}").community().isEmpty());
        assertTrue(accepted("{\"community\": \"here\"}").community().isEmpty());
    }

    @Test
    void explicitCommunityUsesTheCommandForm() {
        assertEquals(Optional.of(new CommunityKey(ResourceLocation.parse("minecraft:overworld"), 3)),
                accepted("{\"community\": \"minecraft:overworld/3\"}").community());
    }

    @Test
    void malformedCommunityIsRejected() {
        rejected("{\"community\": \"overworld\"}");
        rejected("{\"community\": \"minecraft:overworld/-1\"}");
    }

    @Test
    void playerSourceDefaultsToThis() {
        assertFalse(accepted("{}").usesKiller());
        assertFalse(accepted("{\"player\": \"this\"}").usesKiller());
    }

    @Test
    void killerPlayerSource() {
        assertTrue(accepted("{\"player\": \"killer\"}").usesKiller());
    }

    @Test
    void unknownPlayerSourceIsRejected() {
        rejected("{\"player\": \"victim\"}");
    }
}

package dev.otectus.mcareputation.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.otectus.mcareputation.api.ReputationQuery;
import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §36.1 group 14: every {@code mcareputation:standing} block the documentation shows must parse
 * to the query a pack author would expect, and the two fields that cannot fail silently — community
 * and player source — must be rejected loudly when they are wrong.
 *
 * <p>These go through the pure parsers rather than the serializer so no registry is touched; the
 * condition's own {@code test} needs a level and belongs to the manual matrix.
 */
class StandingConditionTest {

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    @Test
    void scoreBoundsOnly() {
        ReputationQuery query = StandingCondition.parseQuery(json("{\"min\": 20, \"max\": 80}"));
        assertEquals(20, query.min().getAsInt());
        assertEquals(80, query.max().getAsInt());
        assertTrue(query.minTier().isEmpty());
        assertTrue(query.hasTitle().isEmpty());
    }

    @Test
    void tierBounds() {
        ReputationQuery query = StandingCondition.parseQuery(
                json("{\"min_tier\": \"friend\", \"max_tier\": \"revered\"}"));
        assertEquals(Optional.of("friend"), query.minTier());
        assertEquals(Optional.of("revered"), query.maxTier());
        assertTrue(query.min().isEmpty());
    }

    @Test
    void title() {
        ReputationQuery query = StandingCondition.parseQuery(
                json("{\"has_title\": \"mcareputation:village_hero\"}"));
        assertEquals(Optional.of(new ResourceLocation("mcareputation:village_hero")), query.hasTitle());
    }

    @Test
    void emptyBlockIsANoOpRatherThanAnError() {
        assertTrue(StandingCondition.parseQuery(json("{}")).isEmpty());
    }

    @Test
    void communityDefaultsToHere() {
        assertTrue(StandingCondition.parseCommunity(json("{}")).isEmpty());
        assertTrue(StandingCondition.parseCommunity(json("{\"community\": \"here\"}")).isEmpty());
    }

    @Test
    void explicitCommunityUsesTheCommandForm() {
        assertEquals(Optional.of(new CommunityKey(new ResourceLocation("minecraft:overworld"), 3)),
                StandingCondition.parseCommunity(json("{\"community\": \"minecraft:overworld/3\"}")));
    }

    @Test
    void malformedCommunityIsRejected() {
        assertThrows(JsonSyntaxException.class,
                () -> StandingCondition.parseCommunity(json("{\"community\": \"overworld\"}")));
        assertThrows(JsonSyntaxException.class,
                () -> StandingCondition.parseCommunity(json("{\"community\": \"minecraft:overworld/-1\"}")));
    }

    @Test
    void playerSourceDefaultsToThis() {
        assertFalse(StandingCondition.parsePlayerSource(json("{}")));
        assertFalse(StandingCondition.parsePlayerSource(json("{\"player\": \"this\"}")));
    }

    @Test
    void killerPlayerSource() {
        assertTrue(StandingCondition.parsePlayerSource(json("{\"player\": \"killer\"}")));
    }

    @Test
    void unknownPlayerSourceIsRejected() {
        assertThrows(JsonSyntaxException.class,
                () -> StandingCondition.parsePlayerSource(json("{\"player\": \"victim\"}")));
    }
}

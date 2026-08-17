package dev.otectus.mcareputation.incident;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §36.1 group 3. */
class IncidentCodecTest {

    private static DataResult<IncidentDefinition> parse(String json) {
        return IncidentDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    private static final String MINIMAL = """
            {"display":{"translate":"x"},"default_delta":-8,"visibility":"witnessed","severity":"major"}""";

    @Test
    void minimalDefinitionParsesWithDocumentedDefaults() {
        IncidentDefinition def = parse(MINIMAL).result().orElseThrow();
        assertEquals(-8, def.defaultDelta());
        assertEquals(IncidentVisibility.WITNESSED, def.visibility());
        assertEquals(IncidentSeverity.MAJOR, def.severity());
        assertEquals(DecayPolicy.NONE, def.decay());
        assertEquals(ResolutionPolicy.DEFAULT, def.resolution());
        assertEquals(GossipSpec.NONE, def.gossip());
        assertFalse(def.pinned());
        assertFalse(def.retainUnwitnessed());
        assertFalse(def.allowPrivateScore());
        assertTrue(def.tags().isEmpty());
        assertTrue(def.retentionTicks().isEmpty());
        assertEquals(IncidentDefinition.DEFAULT_MAX_OVERRIDE_ABS, def.effectiveMaxOverrideAbs());
    }

    @Test
    void everyFieldRoundTrips() {
        IncidentDefinition def = parse("""
                {
                  "display": {"translate":"mcareputation.incident.villager_assaulted"},
                  "default_delta": -8,
                  "visibility": "witnessed",
                  "severity": "major",
                  "tags": ["crime","violence"],
                  "retention_ticks": 336000,
                  "decay": {"type":"linear_to_zero","delay_ticks":48000,"amount_per_day":2},
                  "resolution": {"apologized":0.75,"atoned":0.25,"forgiven":0.0,"disproven":0.0},
                  "gossip": {"tone":"condemnation","phrase":"p","with":["player","subject"]},
                  "pinned": true,
                  "max_override_abs": 20,
                  "retain_unwitnessed": true
                }""").result().orElseThrow();

        assertEquals(java.util.List.of("crime", "violence"), def.tags());
        assertEquals(Optional.of(336000L), def.retentionTicks());
        assertEquals(DecayPolicy.Type.LINEAR_TO_ZERO, def.decay().type());
        assertEquals(48000L, def.decay().delayTicks());
        assertEquals(2, def.decay().amountPerDay());
        assertEquals(0.75f, def.resolution().multiplierFor(IncidentStatus.APOLOGIZED));
        assertEquals("condemnation", def.gossip().tone().orElseThrow());
        assertTrue(def.pinned());
        assertTrue(def.retainUnwitnessed());
        assertEquals(20, def.effectiveMaxOverrideAbs());
    }

    @Test
    void enumsAreCaseInsensitive() {
        assertTrue(parse("""
                {"display":"x","default_delta":0,"visibility":"VILLAGE","severity":"Trivial"}""")
                .result().isPresent());
    }

    @Test
    void globalIsSpelledGlobalInJsonAndMeansGlobalReserved() {
        IncidentDefinition def = parse("""
                {"display":"x","default_delta":0,"visibility":"global","severity":"minor"}""")
                .result().orElseThrow();
        assertEquals(IncidentVisibility.GLOBAL_RESERVED, def.visibility());
        assertEquals("global", def.visibility().jsonName());
    }

    @Test
    void unknownEnumValueIsAnError() {
        DataResult<IncidentDefinition> result = parse("""
                {"display":"x","default_delta":0,"visibility":"whispered","severity":"minor"}""");
        assertTrue(result.error().isPresent());
        assertTrue(result.error().orElseThrow().message().contains("whispered"),
                "the error must name the offending token");
    }

    /** §14.1: a private incident must not carry score without the development override. */
    @Test
    void privateWithNonZeroDeltaIsRejected() {
        assertTrue(parse("""
                {"display":"x","default_delta":-5,"visibility":"private","severity":"minor"}""")
                .error().isPresent());
    }

    @Test
    void privateWithZeroDeltaIsFine() {
        assertTrue(parse("""
                {"display":"x","default_delta":0,"visibility":"private","severity":"minor"}""")
                .result().isPresent());
    }

    @Test
    void theDevelopmentOverrideExistsButIsFlaggedByValidation() {
        IncidentDefinition def = parse("""
                {"display":"x","default_delta":-5,"visibility":"private","severity":"minor",
                 "allow_private_score":true}""").result().orElseThrow();
        assertTrue(def.allowPrivateScore());
    }

    @Test
    void outOfRangeResolutionMultipliersAreRejected() {
        assertTrue(parse("""
                {"display":"x","default_delta":0,"visibility":"village","severity":"minor",
                 "resolution":{"atoned":1.5}}""").error().isPresent());
        assertTrue(parse("""
                {"display":"x","default_delta":0,"visibility":"village","severity":"minor",
                 "resolution":{"atoned":-0.5}}""").error().isPresent());
    }

    @Test
    void resolutionCannotDefineActive() {
        assertTrue(parse("""
                {"display":"x","default_delta":0,"visibility":"village","severity":"minor",
                 "resolution":{"active":0.5}}""").error().isPresent());
    }

    @Test
    void absurdDeltasAreRejected() {
        assertTrue(parse("""
                {"display":"x","default_delta":999999999,"visibility":"village","severity":"minor"}""")
                .error().isPresent());
        assertTrue(parse("""
                {"display":"x","default_delta":0,"visibility":"village","severity":"minor",
                 "max_override_abs":999999999}""").error().isPresent());
    }

    @Test
    void tagsAreNormalisedAndBounded() {
        IncidentDefinition def = parse("""
                {"display":"x","default_delta":0,"visibility":"village","severity":"minor",
                 "tags":["  Crime  ","CRIME","violence","a","b","c","d","e","f","g","h","i","j","k","l","m","n","o"]}""")
                .result().orElseThrow();
        assertTrue(def.tags().contains("crime"));
        assertEquals(1, def.tags().stream().filter("crime"::equals).count(), "duplicates collapse");
        assertTrue(def.tags().size() <= IncidentDefinition.MAX_TAGS);
        assertTrue(def.hasTag("CRIME"), "tag matching is case-insensitive");
    }

    @Test
    void gossipArgumentsAreCappedAtFour() {
        IncidentDefinition def = parse("""
                {"display":"x","default_delta":0,"visibility":"village","severity":"minor",
                 "gossip":{"phrase":"p","with":["a","b","c","d","e","f"]}}""").result().orElseThrow();
        assertEquals(GossipSpec.MAX_ARGUMENTS, def.gossip().with().size());
    }

    /** §15: an invalid definition is reported and skipped, never fatal. */
    @Test
    void structurallyBrokenJsonIsAnErrorNotAnException() {
        assertTrue(parse("{}").error().isPresent());
        assertTrue(parse("""
                {"default_delta":0,"visibility":"village","severity":"minor"}""").error().isPresent());
        assertTrue(parse("[]").error().isPresent());
    }

    // ------------------------------------------------------------------
    // Delta resolution
    // ------------------------------------------------------------------

    @Test
    void callerOverrideIsClampedNotRejected() {
        IncidentDefinition def = parse("""
                {"display":"x","default_delta":0,"visibility":"village","severity":"minor",
                 "max_override_abs":20}""").result().orElseThrow();
        assertEquals(12, def.resolveDelta(OptionalInt.of(12)));
        assertEquals(20, def.resolveDelta(OptionalInt.of(500)), "a generous reward degrades to the cap");
        assertEquals(-20, def.resolveDelta(OptionalInt.of(-500)));
        assertEquals(0, def.resolveDelta(OptionalInt.empty()), "no override means the default");
    }

    @Test
    void privateDefinitionsAlwaysResolveToZeroWhateverTheCallerAsks() {
        IncidentDefinition def = parse("""
                {"display":"x","default_delta":0,"visibility":"private","severity":"minor"}""")
                .result().orElseThrow();
        assertEquals(0, def.resolveDelta(OptionalInt.of(50)));
    }

    @Test
    void unknownDefinitionIsInertButUsable() {
        IncidentDefinition unknown = IncidentDefinition.unknown(
                new net.minecraft.resources.ResourceLocation("somemod:gone"));
        assertEquals(0, unknown.defaultDelta());
        assertEquals(0, unknown.resolveDelta(OptionalInt.of(100)),
                "a vanished definition must not let a caller inject score");
        assertTrue(unknown.retainUnwitnessed(), "existing records must survive their definition");
    }
}

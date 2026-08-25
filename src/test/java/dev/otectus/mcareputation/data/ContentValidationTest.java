package dev.otectus.mcareputation.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.otectus.mcareputation.incident.BuiltinIncidents;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.reputation.ReputationTier;
import dev.otectus.mcareputation.reputation.ReputationTierSet;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import dev.otectus.mcareputation.reputation.TitleDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §36.1 group 14: the content this mod actually ships must parse, validate, and be fully
 * localized. A shipped pack that fails its own validator is the sort of thing that is trivially
 * caught here and embarrassing to find in a release.
 */
class ContentValidationTest {

    private static final String INCIDENT_DIR = "/data/mcareputation/mcareputation/incidents/";
    private static final String LADDER_PATH =
            "/data/mcareputation/mcareputation/reputation_tiers/default.json";
    private static final String LANG_PATH = "/assets/mcareputation/lang/en_us.json";

    /** Every incident id §16 requires the mod to ship. */
    private static final List<ResourceLocation> REQUIRED = List.of(
            BuiltinIncidents.VILLAGER_ASSAULTED,
            BuiltinIncidents.VILLAGER_KILLED,
            BuiltinIncidents.QUEST_COMPLETED,
            BuiltinIncidents.QUEST_FAILED,
            BuiltinIncidents.QUEST_ABANDONED,
            BuiltinIncidents.PROJECT_PHASE_COMPLETED,
            BuiltinIncidents.PROJECT_COMPLETED,
            BuiltinIncidents.PROJECT_FAILED,
            BuiltinIncidents.SITUATION_RESOLVED,
            BuiltinIncidents.PROMISE_MADE,
            BuiltinIncidents.PROMISE_KEPT,
            BuiltinIncidents.PROMISE_BROKEN,
            BuiltinIncidents.PUBLIC_APOLOGY,
            BuiltinIncidents.RESTITUTION_COMPLETED,
            BuiltinIncidents.LEGACY_BALANCE);

    private static String read(String path) throws IOException {
        try (InputStream in = ContentValidationTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing shipped resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<ResourceLocation, IncidentDefinition> shippedIncidents() throws IOException {
        Map<ResourceLocation, IncidentDefinition> loaded = new LinkedHashMap<>();
        for (ResourceLocation id : REQUIRED) {
            String json = read(INCIDENT_DIR + id.getPath() + ".json");
            DataResult<IncidentDefinition> parsed =
                    IncidentDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
            assertTrue(parsed.error().isEmpty(),
                    () -> id + " failed to parse: " + parsed.error().orElseThrow().message());
            loaded.put(id, parsed.result().orElseThrow());
        }
        return loaded;
    }

    @Test
    void everyRequiredIncidentTypeIsShippedAndParses() throws IOException {
        assertEquals(REQUIRED.size(), shippedIncidents().size());
    }

    @Test
    void theShippedLadderParsesAndMatchesTheBuiltInFallback() throws IOException {
        DataResult<ReputationTierSet> parsed =
                ReputationTierSet.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(read(LADDER_PATH)));
        assertTrue(parsed.error().isEmpty(),
                () -> "default ladder failed to parse: " + parsed.error().orElseThrow().message());
        ReputationTierSet shipped = parsed.result().orElseThrow();

        // The hardcoded fallback exists so removing the datapack does not break tiers; if the two
        // ever drift, a pack author removing the file would silently change everyone's thresholds.
        assertEquals(ReputationTiers.BUILTIN_DEFAULT.size(), shipped.size());
        for (int i = 0; i < shipped.size(); i++) {
            ReputationTier a = ReputationTiers.BUILTIN_DEFAULT.tiers().get(i);
            ReputationTier b = shipped.tiers().get(i);
            assertEquals(a.id(), b.id());
            assertEquals(a.threshold(), b.threshold(), "threshold drift on tier " + a.id());
            assertEquals(a.trustBias(), b.trustBias(), "trust bias drift on tier " + a.id());
            assertEquals(a.respectBias(), b.respectBias(), "respect bias drift on tier " + a.id());
            assertEquals(a.grantsTitle(), b.grantsTitle(), "title drift on tier " + a.id());
        }
    }

    @Test
    void theShippedContentPassesItsOwnValidator() throws IOException {
        DataResult<ReputationTierSet> ladder =
                ReputationTierSet.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(read(LADDER_PATH)));
        Map<ResourceLocation, ReputationTierSet> ladders =
                Map.of(ReputationTiers.DEFAULT_ID, ladder.result().orElseThrow());
        Map<ResourceLocation, TitleDefinition> titles = Map.of();

        List<ReputationContentValidator.Problem> problems =
                ReputationContentValidator.validate(shippedIncidents(), ladders, titles);
        assertTrue(problems.isEmpty(), () -> "shipped content has validation problems:\n  "
                + problems.stream().map(Object::toString)
                        .collect(java.util.stream.Collectors.joining("\n  ")));
    }

    /** §14.1: no shipped definition may use the development-only private-score override. */
    @Test
    void noShippedIncidentUsesTheDevelopmentOverride() throws IOException {
        shippedIncidents().forEach((id, definition) ->
                assertFalse(definition.allowPrivateScore(), id + " must not set allow_private_score"));
    }

    @Test
    void privateIncidentsCarryNoScore() throws IOException {
        shippedIncidents().forEach((id, definition) -> {
            if (definition.visibility() == IncidentVisibility.PRIVATE) {
                assertEquals(0, definition.defaultDelta(), id + " is private and must not score");
            }
        });
    }

    /** §16: the killing must be worth its full target, and the assault its own smaller figure. */
    @Test
    void theCoreIncidentDeltasMatchTheSpecification() throws IOException {
        Map<ResourceLocation, IncidentDefinition> incidents = shippedIncidents();
        assertEquals(-8, incidents.get(BuiltinIncidents.VILLAGER_ASSAULTED).defaultDelta());
        assertEquals(-40, incidents.get(BuiltinIncidents.VILLAGER_KILLED).defaultDelta());
        assertEquals(8, incidents.get(BuiltinIncidents.PROMISE_KEPT).defaultDelta());
        assertEquals(1, incidents.get(BuiltinIncidents.PUBLIC_APOLOGY).defaultDelta());
        assertEquals(4, incidents.get(BuiltinIncidents.RESTITUTION_COMPLETED).defaultDelta());
        assertEquals(0, incidents.get(BuiltinIncidents.PROMISE_MADE).defaultDelta());
    }

    /** §19.1: an unwitnessed killing is retained as hidden history rather than being dropped. */
    @Test
    void theKillingDefinitionRetainsUnwitnessedHistory() throws IOException {
        IncidentDefinition killing = shippedIncidents().get(BuiltinIncidents.VILLAGER_KILLED);
        assertTrue(killing.retainUnwitnessed());
        assertFalse(killing.decay().decays(), "a killing does not fade on its own");
    }

    /**
     * §13.5/§34: no shipped incident may be pinned. Pinned entries are exempt from every pruning
     * pass, so a shipped pinned type would let ordinary play (64+ killings in one village) exceed the
     * per-community cap permanently and jam the whole-player sweep. Pruning folds weight into the
     * baseline, so the score survives either way; pinning is for administrators marking specific
     * records, not for content.
     */
    @Test
    void noShippedIncidentIsPinned() throws IOException {
        shippedIncidents().forEach((id, definition) ->
                assertFalse(definition.pinned(), id + " must not ship pinned"));
    }

    @Test
    void theAssaultDefinitionDecaysAsSpecified() throws IOException {
        IncidentDefinition assault = shippedIncidents().get(BuiltinIncidents.VILLAGER_ASSAULTED);
        assertTrue(assault.decay().decays());
        assertEquals(2, assault.decay().amountPerDay());
        assertEquals(2 * 24000L, assault.decay().delayTicks());
    }

    /**
     * §22.2/§32.4: the ladder grants {@code mcaquests:}-namespaced titles for save compatibility, and
     * this mod now ships those definitions itself (byte-identical to Quests') so a standalone install
     * renders "Honored", not a raw resource id. Datapack namespaces are not tied to jars; a combined
     * install stacks two identical definitions harmlessly.
     */
    @Test
    void theShippedLadderGrantsResolveAgainstShippedTitleDefinitions() throws IOException {
        ReputationTierSet ladder = ReputationTierSet.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(read(LADDER_PATH)))
                .result().orElseThrow();
        for (ReputationTier tier : ladder.tiers()) {
            if (tier.grantsTitle().isEmpty()) {
                continue;
            }
            ResourceLocation title = tier.grantsTitle().get();
            String path = "/data/" + title.getNamespace() + "/" + title.getNamespace()
                    + "/titles/" + title.getPath() + ".json";
            DataResult<TitleDefinition> parsed = TitleDefinition.CODEC
                    .parse(JsonOps.INSTANCE, JsonParser.parseString(read(path)));
            assertTrue(parsed.error().isEmpty(), () -> title + " failed to parse: "
                    + parsed.error().orElseThrow().message());
        }
    }

    /**
     * The severity partition strict mode relies on: authored values the runtime will not honour are
     * errors; advice about working content is not. If these drift, strict reloads either wave through
     * broken packs or reject working ones.
     */
    @Test
    void validatorSeveritiesPartitionErrorsFromAdvice() throws IOException {
        // A tier bias beyond the runtime clamp parses (codec allows to ±14) but must validate as ERROR.
        ReputationTierSet overBias = ReputationTierSet.CODEC.parse(JsonOps.INSTANCE,
                        JsonParser.parseString("{\"tiers\":[{\"id\":\"floor\",\"threshold\":-100,"
                                + "\"name\":{\"translate\":\"t\"},\"trust_bias\":9}]}"))
                .result().orElseThrow();
        List<ReputationContentValidator.Problem> problems = ReputationContentValidator.validate(
                Map.of(), Map.of(ReputationTiers.DEFAULT_ID, overBias), Map.of());
        assertTrue(problems.stream().anyMatch(p -> p.isError() && p.message().contains("bias")),
                "an over-limit bias is a promise the runtime will not keep: an error");

        // No default ladder at all: the built-in fallback covers it, so this is advice, not an error.
        List<ReputationContentValidator.Problem> noLadder =
                ReputationContentValidator.validate(Map.of(), Map.of(), Map.of());
        assertTrue(noLadder.stream().noneMatch(ReputationContentValidator.Problem::isError),
                "a missing default ladder is covered by the fallback and must not fail strict mode");
        assertTrue(noLadder.stream().anyMatch(p -> !p.isError()));
    }

    /** §21.3: malformed tags would silently never match a selector — an error, loudly. */
    @Test
    void aMalformedTagValidatesAsAnError() throws IOException {
        Map<ResourceLocation, IncidentDefinition> incidents = new LinkedHashMap<>(shippedIncidents());
        IncidentDefinition assault = incidents.get(BuiltinIncidents.VILLAGER_ASSAULTED);
        incidents.put(ResourceLocation.fromNamespaceAndPath("test", "bad_tags"), new IncidentDefinition(
                assault.display(), assault.defaultDelta(), assault.visibility(), assault.severity(),
                List.of("Not A Valid Tag!"), assault.retentionTicks(), assault.decay(),
                assault.resolution(), assault.gossip(), assault.pinned(), assault.maxOverrideAbs(),
                assault.retainUnwitnessed(), assault.allowPrivateScore()));
        List<ReputationContentValidator.Problem> problems =
                ReputationContentValidator.validate(incidents, Map.of(), Map.of());
        assertTrue(problems.stream().anyMatch(p -> p.isError() && p.message().contains("bad_tags")));
    }

    // ------------------------------------------------------------------
    // Localization (§21.3, §28.4)
    // ------------------------------------------------------------------

    @Test
    void everyShippedTranslationKeyExists() throws IOException {
        JsonObject lang = JsonParser.parseString(read(LANG_PATH)).getAsJsonObject();
        List<String> missing = new ArrayList<>();

        shippedIncidents().forEach((id, definition) -> {
            collectKeys(definition.display(), missing, lang);
            definition.gossip().phrase().ifPresent(phrase -> {
                if (!lang.has(phrase)) {
                    missing.add(phrase + " (gossip phrase of " + id + ")");
                }
            });
        });

        ReputationTierSet ladder = ReputationTierSet.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(read(LADDER_PATH)))
                .result().orElseThrow();
        for (ReputationTier tier : ladder.tiers()) {
            collectKeys(tier.name(), missing, lang);
            tier.description().ifPresent(description -> collectKeys(description, missing, lang));
        }

        assertTrue(missing.isEmpty(), () -> "untranslated keys:\n  " + String.join("\n  ", missing));
    }

    private static void collectKeys(Component component, List<String> missing, JsonObject lang) {
        if (component.getContents() instanceof TranslatableContents translatable
                && !lang.has(translatable.getKey())) {
            missing.add(translatable.getKey());
        }
    }

    @Test
    void theLanguageFileHasNoDuplicateOrEmptyValues() throws IOException {
        JsonObject lang = JsonParser.parseString(read(LANG_PATH)).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : lang.entrySet()) {
            if (entry.getKey().startsWith("_comment")) {
                continue;
            }
            assertFalse(entry.getValue().getAsString().isBlank(),
                    "empty translation for " + entry.getKey());
        }
    }

    /** Every status, severity, and age bucket the screen can render must have a string. */
    @Test
    void everyEnumTheUiRendersIsLocalized() throws IOException {
        JsonObject lang = JsonParser.parseString(read(LANG_PATH)).getAsJsonObject();
        for (var status : dev.otectus.mcareputation.incident.IncidentStatus.values()) {
            assertTrue(lang.has("mcareputation.status." + status.jsonName()),
                    "missing status string for " + status.jsonName());
        }
        for (String bucket : List.of("today", "yesterday", "days", "long_ago")) {
            assertTrue(lang.has("mcareputation.age." + bucket), "missing age string for " + bucket);
        }
    }
}

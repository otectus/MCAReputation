package dev.otectus.mcareputation.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.otectus.mcareputation.credit.CreditPolicy;
import dev.otectus.mcareputation.incident.BuiltinIncidents;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.profile.FacetDefinition;
import dev.otectus.mcareputation.profile.IncidentProfileDefinition;
import dev.otectus.mcareputation.profile.ProfileMath;
import dev.otectus.mcareputation.profile.RecognitionTierSet;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The profile content this mod actually ships, held to its own validator (§9.6) and to §17's tuning
 * table.
 *
 * <p>Every file is read off disk and parsed through the reload's strict path, so a shipped file with a
 * duplicate key or an unresolvable reference fails here rather than in somebody's world. The
 * cross-registry cases at the bottom cover the two publication behaviours §9.6 requires: strict mode
 * rejects a generation outright, and lenient mode keeps a working scalar incident whose optional
 * profile attachment is broken.
 */
class ProfileContentValidationTest {

    private static final String DATA = "src/main/resources/data/mcareputation/mcareputation/";
    private static final String LANG = "src/main/resources/assets/mcareputation/lang/en_us.json";

    private static Path projectRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("src/main/java/dev/otectus/mcareputation"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return fail("could not locate the project root from " + Paths.get("").toAbsolutePath());
    }

    /** Loads a whole shipped directory exactly as the reload would, id-keyed. */
    private static <T> Map<ResourceLocation, T> loadDirectory(String directory, Codec<T> codec)
            throws IOException {
        Path root = projectRoot().resolve(DATA + directory);
        assertTrue(Files.isDirectory(root), "missing shipped directory: " + root);
        Map<ResourceLocation, T> loaded = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - ".json".length());
                DataResult<T> parsed = ReputationReloadListener.parseStrictForTest(
                        codec, Files.readString(file));
                assertTrue(parsed.error().isEmpty(), () -> directory + "/" + name
                        + " failed to parse: " + parsed.error().orElseThrow().message());
                loaded.put(ResourceLocation.fromNamespaceAndPath("mcareputation", id), parsed.result().orElseThrow());
            }
        }
        return loaded;
    }

    private static Map<ResourceLocation, FacetDefinition> facets() throws IOException {
        return loadDirectory("facets", FacetDefinition.CODEC);
    }

    private static Map<ResourceLocation, IncidentProfileDefinition> profiles() throws IOException {
        return loadDirectory("incident_profiles", IncidentProfileDefinition.CODEC);
    }

    private static Map<ResourceLocation, CreditPolicy> creditPolicies() throws IOException {
        return loadDirectory("credit_policies", CreditPolicy.CODEC);
    }

    private static Map<ResourceLocation, RecognitionTierSet> recognitionLadders() throws IOException {
        return loadDirectory("recognition_tiers", RecognitionTierSet.CODEC);
    }

    /** The incident definitions as shipped, including the new {@code social_profile} attachments. */
    private static Map<ResourceLocation, IncidentDefinition> incidents() throws IOException {
        return loadDirectory("incidents", IncidentDefinition.CODEC);
    }

    private static ReputationContentValidator.ProfileContent shippedContent() throws IOException {
        return new ReputationContentValidator.ProfileContent(facets(), recognitionLadders(),
                profiles(), creditPolicies());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mcareputation", path);
    }

    // ------------------------------------------------------------------
    // Shipped content
    // ------------------------------------------------------------------

    /** §8.1 ships exactly seven facets; the vocabulary is the contract companions author against. */
    @Test
    void theSevenFacetsAreShippedAndParse() throws IOException {
        assertEquals(List.of(id("bravery"), id("compassion"), id("generosity"), id("lawfulness"),
                        id("mercy"), id("reliability"), id("violence")),
                facets().keySet().stream().sorted(java.util.Comparator.comparing(ResourceLocation::toString))
                        .toList());
    }

    /** §8.1: bravery, generosity, mercy and violence are unipolar — zero is not the opposite trait. */
    @Test
    void theFacetRangesMatchTheSpecification() throws IOException {
        Map<ResourceLocation, FacetDefinition> facets = facets();
        assertEquals(-100, facets.get(id("reliability")).range().min());
        assertEquals(-100, facets.get(id("compassion")).range().min());
        assertEquals(-100, facets.get(id("lawfulness")).range().min());
        for (String unipolar : List.of("bravery", "generosity", "mercy", "violence")) {
            assertEquals(0, facets.get(id(unipolar)).range().min(), unipolar + " must be unipolar");
            assertTrue(facets.get(id(unipolar)).negativeLabel().isEmpty(),
                    unipolar + " has no negative half to label");
        }
        facets.forEach((id, facet) -> assertEquals(100, facet.range().max(), id + " max"));
    }

    /** Known violence must lower an observer's opinion rather than raise it. */
    @Test
    void violenceCarriesANegativeInterpretationWeight() throws IOException {
        assertTrue(facets().get(id("violence")).opinionWeightBp() < 0);
    }

    @Test
    void theShippedRecognitionLadderMatchesTheBuiltInFallback() throws IOException {
        RecognitionTierSet shipped = recognitionLadders().get(RecognitionTierSet.DEFAULT_ID);
        assertTrue(shipped != null, "mcareputation:default recognition ladder must be shipped");
        assertEquals(RecognitionTierSet.BUILTIN_DEFAULT.size(), shipped.size());
        for (int i = 0; i < shipped.size(); i++) {
            RecognitionTierSet.Tier a = RecognitionTierSet.BUILTIN_DEFAULT.tiers().get(i);
            RecognitionTierSet.Tier b = shipped.tiers().get(i);
            assertEquals(a.id(), b.id());
            assertEquals(a.threshold(), b.threshold(), "threshold drift on recognition tier " + a.id());
        }
    }

    /** §7.2's six tiers and their exact inclusive thresholds. */
    @Test
    void theRecognitionThresholdsMatchTheSpecification() throws IOException {
        RecognitionTierSet ladder = recognitionLadders().get(RecognitionTierSet.DEFAULT_ID);
        assertEquals(List.of("unknown", "noticed", "recognized", "well_known", "renowned", "famous"),
                ladder.tiers().stream().map(RecognitionTierSet.Tier::id).toList());
        assertEquals(List.of(0, 5, 15, 40, 90, 180),
                ladder.tiers().stream().map(RecognitionTierSet.Tier::threshold).toList());
    }

    /** §17's tuning table, as shipped values (decision D7). */
    @Test
    void theShippedProfilesMatchTheTuningTable() throws IOException {
        Map<ResourceLocation, IncidentProfileDefinition> profiles = profiles();

        assertProfile(profiles, "rescued_villager", 6, Map.of("bravery", 8, "compassion", 5));
        assertProfile(profiles, "cured_villager", 10, Map.of("compassion", 10));
        assertProfile(profiles, "repelled_raid", 14, Map.of("bravery", 12, "reliability", 4));
        assertProfile(profiles, "assaulted_villager", 4, Map.of("violence", 8, "compassion", -4));
        assertProfile(profiles, "killed_villager", 18, Map.of("violence", 20, "compassion", -12));
        assertProfile(profiles, "kept_commitment", 3, Map.of("reliability", 4));
        assertProfile(profiles, "broken_commitment", 3, Map.of("reliability", -6));
        assertProfile(profiles, "donation_project", 5, Map.of("generosity", 6));
        assertProfile(profiles, "spared_outcome", 4, Map.of("mercy", 6));
    }

    private static void assertProfile(Map<ResourceLocation, IncidentProfileDefinition> profiles,
                                      String name, int recognition, Map<String, Integer> facetPoints) {
        IncidentProfileDefinition profile = profiles.get(id(name));
        assertTrue(profile != null, "missing shipped profile " + name);
        assertEquals(recognition, profile.recognition().orElseThrow().points(),
                name + " recognition");
        assertEquals(facetPoints.size(), profile.facets().size(), name + " facet count");
        facetPoints.forEach((facet, points) -> assertEquals(points,
                profile.facets().get(id(facet)).points(), name + " " + facet));
    }

    /** §17: roughly 28 in-game days for facet evidence and 56 for recognition, in whole-day steps. */
    @Test
    void theShippedLifetimesAreWholeDayMultiplesOfTheDocumentedDefaults() throws IOException {
        profiles().forEach((id, profile) -> {
            assertEquals(ProfileMath.DEFAULT_DECAY_STEP_TICKS, profile.effectiveDecayStepTicks(),
                    id + " should use the default whole-day step");
            profile.recognition().ifPresent(recognition -> assertEquals(56 * 24_000L,
                    recognition.lifetimeTicks(), id + " recognition lifetime"));
            profile.facets().forEach((facet, contribution) -> assertEquals(28 * 24_000L,
                    contribution.lifetimeTicks(), id + " " + facet + " lifetime"));
        });
    }

    /** §7.3: recognition survives apology and atonement, so it is never authored as evaluative. */
    @Test
    void recognitionIsNeverAuthoredAsEvaluative() throws IOException {
        profiles().forEach((id, profile) -> profile.recognition().ifPresent(recognition ->
                assertEquals(IncidentProfileDefinition.ResolutionMode.RECOGNITION,
                        recognition.resolutionMode(), id + " recognition mode")));
    }

    /** I07: no adverse profile may carry a repeat-credit policy that discounts a second offence. */
    @Test
    void noAdverseProfileIsDiscounted() throws IOException {
        profiles().forEach((id, profile) -> {
            boolean adverse = profile.facets().values().stream().anyMatch(c -> c.points() < 0);
            if (adverse) {
                assertFalse(profile.creditClass().discountable(),
                        id + " has adverse effects and must not be commendable");
                assertTrue(profile.creditPolicy().isEmpty(),
                        id + " must not carry a reward-discount policy");
            }
        });
    }

    /** §10.2: a nonzero tail permits indefinite farming; shipped policies do not. */
    @Test
    void everyShippedCreditPolicyEndsAtZero() throws IOException {
        creditPolicies().forEach((id, policy) -> {
            assertEquals(0, policy.tailBp(), id + " must ship a zero tail");
            assertEquals(0, policy.creditScheduleBp().get(policy.creditScheduleBp().size() - 1),
                    id + " schedule must end at zero");
            policy.subjectLimit().ifPresent(limit ->
                    assertEquals(0, limit.tailBp(), id + " subject tail"));
        });
    }

    /** The group is the accounting key; a file whose group disagrees with its id is a trap. */
    @Test
    void everyCreditPolicyGroupMatchesItsFileId() throws IOException {
        creditPolicies().forEach((id, policy) -> assertEquals(id, policy.group()));
    }

    /** The spec's own example policy, shipped verbatim (§10.2). */
    @Test
    void theRescueServicePolicyMatchesTheSpecification() throws IOException {
        CreditPolicy rescue = creditPolicies().get(id("rescue_service"));
        assertEquals(336_000L, rescue.windowTicks());
        assertEquals(List.of(10_000, 10_000, 5_000, 2_500, 0), rescue.creditScheduleBp());
        assertEquals(CreditPolicy.Scope.PLAYER_COMMUNITY, rescue.scope());
        CreditPolicy.SubjectLimit limit = rescue.subjectLimit().orElseThrow();
        assertEquals("beneficiary", limit.role());
        assertEquals(List.of(10_000, 5_000, 0), limit.creditScheduleBp());
    }

    /** §17: each of the five core deeds attaches its authored profile, and the allowlist agrees. */
    @Test
    void theShippedIncidentsAttachTheirProfiles() throws IOException {
        Map<ResourceLocation, IncidentDefinition> incidents = incidents();
        Map<ResourceLocation, IncidentProfileDefinition> profiles = profiles();
        Map<ResourceLocation, ResourceLocation> expected = Map.of(
                BuiltinIncidents.VILLAGER_RESCUED, id("rescued_villager"),
                BuiltinIncidents.VILLAGER_CURED, id("cured_villager"),
                BuiltinIncidents.RAID_REPELLED, id("repelled_raid"),
                BuiltinIncidents.VILLAGER_ASSAULTED, id("assaulted_villager"),
                BuiltinIncidents.VILLAGER_KILLED, id("killed_villager"),
                BuiltinIncidents.PROMISE_KEPT, id("kept_commitment"),
                BuiltinIncidents.PROMISE_BROKEN, id("broken_commitment"));
        expected.forEach((incident, profileId) -> {
            assertEquals(Optional.of(profileId), incidents.get(incident).socialProfile(),
                    incident + " social_profile");
            assertTrue(profiles.get(profileId).permits(incident),
                    profileId + " must allow " + incident);
        });
    }

    /** §7.1: an incident with no profile contributes no recognition, never a severity-derived bonus. */
    @Test
    void theGenericQuestIncidentsCarryNoProfileByDefault() throws IOException {
        Map<ResourceLocation, IncidentDefinition> incidents = incidents();
        for (ResourceLocation generic : List.of(BuiltinIncidents.QUEST_COMPLETED,
                BuiltinIncidents.QUEST_FAILED, BuiltinIncidents.QUEST_ABANDONED,
                BuiltinIncidents.PROJECT_COMPLETED, BuiltinIncidents.SITUATION_RESOLVED,
                BuiltinIncidents.PUBLIC_APOLOGY, BuiltinIncidents.RESTITUTION_COMPLETED)) {
            assertTrue(incidents.get(generic).socialProfile().isEmpty(),
                    generic + " must not attach a profile by default; a specific outcome selects one");
        }
    }

    @Test
    void theShippedProfileContentPassesItsOwnValidator() throws IOException {
        ReputationContentValidator.ProfileValidation validation =
                ReputationContentValidator.validateProfileContent(incidents(), shippedContent());
        assertTrue(validation.problems().isEmpty(), () -> "shipped profile content has problems:\n  "
                + validation.problems().stream().map(Object::toString)
                        .collect(java.util.stream.Collectors.joining("\n  ")));
        assertFalse(validation.requiresSanitisation());
    }

    // ------------------------------------------------------------------
    // Localization
    // ------------------------------------------------------------------

    @Test
    void everyFacetAndRecognitionLabelIsTranslated() throws IOException {
        JsonObject lang = JsonParser.parseString(
                Files.readString(projectRoot().resolve(LANG))).getAsJsonObject();
        List<String> missing = new ArrayList<>();
        facets().forEach((id, facet) -> {
            collectKey(facet.name(), missing, lang, id + " name");
            facet.description().ifPresent(d -> collectKey(d, missing, lang, id + " description"));
            collectKey(facet.positiveLabel(), missing, lang, id + " positive_label");
            facet.negativeLabel().ifPresent(l -> collectKey(l, missing, lang, id + " negative_label"));
        });
        recognitionLadders().forEach((id, ladder) -> ladder.tiers().forEach(tier -> {
            collectKey(tier.name(), missing, lang, id + " " + tier.id() + " name");
            tier.description().ifPresent(d ->
                    collectKey(d, missing, lang, id + " " + tier.id() + " description"));
        }));
        assertTrue(missing.isEmpty(), () -> "untranslated profile keys:\n  "
                + String.join("\n  ", missing));
    }

    private static void collectKey(Component component, List<String> missing, JsonObject lang,
                                   String where) {
        if (component.getContents() instanceof TranslatableContents translatable
                && !lang.has(translatable.getKey())) {
            missing.add(translatable.getKey() + " (" + where + ")");
        }
    }

    // ------------------------------------------------------------------
    // Cross-registry rejection (§9.6)
    // ------------------------------------------------------------------

    private static IncidentProfileDefinition profile(Map<String, Integer> facetPoints,
                                                     IncidentProfileDefinition.CreditClass creditClass,
                                                     ResourceLocation creditPolicy) {
        Map<ResourceLocation, IncidentProfileDefinition.Contribution> facets = new LinkedHashMap<>();
        facetPoints.forEach((facet, points) -> facets.put(id(facet),
                new IncidentProfileDefinition.Contribution(points, 672_000L,
                        IncidentProfileDefinition.ResolutionMode.HISTORICAL,
                        IncidentProfileDefinition.ResolutionMultipliers.DEFAULT)));
        return new IncidentProfileDefinition(List.of(), Optional.empty(), facets, creditClass,
                Optional.ofNullable(creditPolicy), false, Optional.empty());
    }

    @Test
    void aProfileNamingAnUndefinedFacetIsRejected() throws IOException {
        Map<ResourceLocation, IncidentProfileDefinition> profiles = new LinkedHashMap<>(profiles());
        profiles.put(id("broken"), profile(Map.of("charisma", 5),
                IncidentProfileDefinition.CreditClass.COMMENDABLE, null));
        ReputationContentValidator.ProfileValidation validation =
                ReputationContentValidator.validateProfileContent(incidents(),
                        new ReputationContentValidator.ProfileContent(facets(), recognitionLadders(),
                                profiles, creditPolicies()));
        assertTrue(validation.hasErrors());
        assertTrue(validation.rejectedProfiles().contains(id("broken")));
    }

    @Test
    void aProfileOutsideItsFacetsRangeIsRejected() throws IOException {
        Map<ResourceLocation, IncidentProfileDefinition> profiles = new LinkedHashMap<>(profiles());
        // Bravery is unipolar 0..100; a negative contribution is a claim it cannot express.
        profiles.put(id("cowardice"), profile(Map.of("bravery", -20),
                IncidentProfileDefinition.CreditClass.ADVERSE, null));
        ReputationContentValidator.ProfileValidation validation =
                ReputationContentValidator.validateProfileContent(incidents(),
                        new ReputationContentValidator.ProfileContent(facets(), recognitionLadders(),
                                profiles, creditPolicies()));
        assertTrue(validation.rejectedProfiles().contains(id("cowardice")));
        assertTrue(validation.problems().stream()
                .anyMatch(p -> p.isError() && p.message().contains("outside that facet's")));
    }

    @Test
    void aProfileNamingAnUndefinedCreditPolicyIsRejected() throws IOException {
        Map<ResourceLocation, IncidentProfileDefinition> profiles = new LinkedHashMap<>(profiles());
        profiles.put(id("unpoliced"), profile(Map.of("bravery", 5),
                IncidentProfileDefinition.CreditClass.COMMENDABLE, id("no_such_policy")));
        ReputationContentValidator.ProfileValidation validation =
                ReputationContentValidator.validateProfileContent(incidents(),
                        new ReputationContentValidator.ProfileContent(facets(), recognitionLadders(),
                                profiles, creditPolicies()));
        assertTrue(validation.rejectedProfiles().contains(id("unpoliced")));
    }

    /** §9.6: two files defining one credit group with different schedules is a contradiction. */
    @Test
    void contradictoryPoliciesSharingAGroupAreRejected() throws IOException {
        Map<ResourceLocation, CreditPolicy> policies = new LinkedHashMap<>(creditPolicies());
        policies.put(id("rescue_service_alias"), new CreditPolicy(id("rescue_service"), 336_000L,
                List.of(10_000, 10_000, 10_000), 10_000, CreditPolicy.Scope.PLAYER_COMMUNITY,
                Optional.empty()));
        ReputationContentValidator.ProfileValidation validation =
                ReputationContentValidator.validateProfileContent(incidents(),
                        new ReputationContentValidator.ProfileContent(facets(), recognitionLadders(),
                                profiles(), policies));
        assertTrue(validation.rejectedCreditPolicies().contains(id("rescue_service_alias")));
        assertTrue(validation.rejectedCreditPolicies().contains(id("rescue_service")));
    }

    /** An identical alias is harmless: one group, one definition, two file names. */
    @Test
    void anIdenticalPolicyAliasIsAccepted() throws IOException {
        Map<ResourceLocation, CreditPolicy> policies = new LinkedHashMap<>(creditPolicies());
        policies.put(id("rescue_service_alias"), policies.get(id("rescue_service")));
        ReputationContentValidator.ProfileValidation validation =
                ReputationContentValidator.validateProfileContent(incidents(),
                        new ReputationContentValidator.ProfileContent(facets(), recognitionLadders(),
                                profiles(), policies));
        assertTrue(validation.rejectedCreditPolicies().isEmpty());
        assertFalse(validation.hasErrors());
    }

    /** §9.5: a generic incident may not borrow a profile whose allowlist does not name it. */
    @Test
    void anAttachmentOutsideTheAllowlistIsRejected() throws IOException {
        Map<ResourceLocation, IncidentDefinition> incidents = new LinkedHashMap<>(incidents());
        IncidentDefinition quest = incidents.get(BuiltinIncidents.QUEST_COMPLETED);
        incidents.put(BuiltinIncidents.QUEST_COMPLETED,
                quest.withSocialProfile(Optional.of(id("killed_villager"))));
        ReputationContentValidator.ProfileValidation validation =
                ReputationContentValidator.validateProfileContent(incidents, shippedContent());
        assertTrue(validation.incidentsWithUnusableProfile().contains(BuiltinIncidents.QUEST_COMPLETED));
        assertTrue(validation.problems().stream()
                .anyMatch(p -> p.isError() && p.message().contains("allowed_incidents")));
    }

    /**
     * I03/§13.1: a private deed has no public audience. The reference is legal — §23.1's "private
     * harmful event" fixture requires it to parse and contribute nothing — so this is advice, not an
     * error that would refuse the pack.
     */
    @Test
    void aPrivateIncidentWithAProfileIsAdviceRatherThanAnError() throws IOException {
        Map<ResourceLocation, IncidentDefinition> incidents = new LinkedHashMap<>(incidents());
        Map<ResourceLocation, IncidentProfileDefinition> profiles = new LinkedHashMap<>(profiles());
        IncidentDefinition promise = incidents.get(BuiltinIncidents.PROMISE_MADE);
        profiles.put(id("private_test"), new IncidentProfileDefinition(
                List.of(BuiltinIncidents.PROMISE_MADE),
                Optional.of(new IncidentProfileDefinition.Contribution(4, 672_000L,
                        IncidentProfileDefinition.ResolutionMode.RECOGNITION,
                        IncidentProfileDefinition.ResolutionMultipliers.DEFAULT)),
                Map.of(), IncidentProfileDefinition.CreditClass.NEUTRAL, Optional.empty(), false,
                Optional.empty()));
        incidents.put(BuiltinIncidents.PROMISE_MADE,
                promise.withSocialProfile(Optional.of(id("private_test"))));

        ReputationContentValidator.ProfileValidation validation =
                ReputationContentValidator.validateProfileContent(incidents,
                        new ReputationContentValidator.ProfileContent(facets(), recognitionLadders(),
                                profiles, creditPolicies()));
        assertFalse(validation.hasErrors(), () -> validation.problems().toString());
        assertTrue(validation.problems().stream()
                .anyMatch(p -> !p.isError() && p.message().contains("private")));
    }

    // ------------------------------------------------------------------
    // Lenient publication (§9.6)
    // ------------------------------------------------------------------

    private static ReputationReloadListener.Prepared prepared(
            Map<ResourceLocation, IncidentDefinition> incidents,
            ReputationContentValidator.ProfileContent content) {
        ReputationContentValidator.ProfileValidation validation =
                ReputationContentValidator.validateProfileContent(incidents, content);
        return new ReputationReloadListener.Prepared(incidents, Map.of(), Map.of(), content.facets(),
                content.recognitionLadders(), content.profiles(), content.creditPolicies(), validation,
                List.copyOf(validation.problems()));
    }

    /**
     * The case §9.6 calls out by name: a working incident whose optional profile reference is
     * unusable keeps its whole scalar definition and loses only the attachment.
     */
    @Test
    void lenientModeKeepsAValidIncidentWhoseProfileIsMissing() throws IOException {
        Map<ResourceLocation, IncidentDefinition> incidents = new LinkedHashMap<>(incidents());
        IncidentDefinition assault = incidents.get(BuiltinIncidents.VILLAGER_ASSAULTED);
        incidents.put(BuiltinIncidents.VILLAGER_ASSAULTED,
                assault.withSocialProfile(Optional.of(id("deleted_profile"))));

        ReputationReloadListener.Prepared sanitised =
                ReputationReloadListener.sanitise(prepared(incidents, shippedContent()));

        IncidentDefinition kept = sanitised.incidents().get(BuiltinIncidents.VILLAGER_ASSAULTED);
        assertEquals(assault.defaultDelta(), kept.defaultDelta(), "the crime definition keeps scoring");
        assertEquals(assault.decay(), kept.decay());
        assertEquals(assault.resolution(), kept.resolution());
        assertTrue(kept.socialProfile().isEmpty(), "only the unusable attachment is dropped");
        // Everything else is still published: one broken reference is not a reason to lose the
        // working profile content.
        assertEquals(7, sanitised.facets().size());
        assertTrue(sanitised.profiles().containsKey(id("killed_villager")));
    }

    /** A rejected facet takes the profiles that referenced it, and the incidents that named those. */
    @Test
    void aRejectedFacetCascadesIntoItsProfilesAndAttachments() throws IOException {
        Map<ResourceLocation, FacetDefinition> facets = new LinkedHashMap<>(facets());
        facets.remove(id("violence"));

        ReputationReloadListener.Prepared sanitised = ReputationReloadListener.sanitise(
                prepared(incidents(), new ReputationContentValidator.ProfileContent(facets,
                        recognitionLadders(), profiles(), creditPolicies())));

        assertFalse(sanitised.profiles().containsKey(id("killed_villager")),
                "a profile whose facet vanished cannot be published");
        assertFalse(sanitised.profiles().containsKey(id("assaulted_villager")));
        assertTrue(sanitised.incidents().get(BuiltinIncidents.VILLAGER_KILLED).socialProfile().isEmpty());
        assertEquals(-40, sanitised.incidents().get(BuiltinIncidents.VILLAGER_KILLED).defaultDelta(),
                "the killing still costs what it costs");
        assertTrue(sanitised.profiles().containsKey(id("rescued_villager")),
                "unrelated profiles are untouched");
    }

    /** §9.6: the sanitised bundle is itself consistent, or nothing profile-related is published. */
    @Test
    void theSanitisedBundleIsAlwaysSelfConsistent() throws IOException {
        Map<ResourceLocation, FacetDefinition> facets = new LinkedHashMap<>(facets());
        facets.remove(id("bravery"));
        facets.remove(id("compassion"));
        Map<ResourceLocation, CreditPolicy> policies = new LinkedHashMap<>(creditPolicies());
        policies.remove(id("commission_work"));

        ReputationReloadListener.Prepared sanitised = ReputationReloadListener.sanitise(
                prepared(incidents(), new ReputationContentValidator.ProfileContent(facets,
                        recognitionLadders(), profiles(), policies)));

        ReputationContentValidator.ProfileValidation recheck =
                ReputationContentValidator.validateProfileContent(sanitised.incidents(),
                        new ReputationContentValidator.ProfileContent(sanitised.facets(),
                                sanitised.recognitionLadders(), sanitised.profiles(),
                                sanitised.creditPolicies()));
        assertFalse(recheck.hasErrors(), () -> "a published bundle must have no dangling references:\n"
                + recheck.problems());
        sanitised.profiles().forEach((id, profile) -> {
            profile.facets().keySet().forEach(facet -> assertTrue(
                    sanitised.facets().containsKey(facet), id + " references missing facet " + facet));
            profile.creditPolicy().ifPresent(policy -> assertTrue(
                    sanitised.creditPolicies().containsKey(policy),
                    id + " references missing credit policy " + policy));
        });
    }

    @Test
    void cleanContentIsPublishedUnchanged() throws IOException {
        ReputationReloadListener.Prepared clean = prepared(incidents(), shippedContent());
        assertEquals(clean, ReputationReloadListener.sanitise(clean));
    }
}

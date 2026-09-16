package dev.otectus.mcareputation.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.credit.CreditPolicy;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.profile.FacetDefinition;
import dev.otectus.mcareputation.profile.IncidentProfileDefinition;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.profile.RecognitionTierSet;
import dev.otectus.mcareputation.reputation.ReputationTierSet;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import dev.otectus.mcareputation.reputation.TitleDefinition;
import dev.otectus.mcareputation.reputation.Titles;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import dev.otectus.mcareputation.util.StrictJson;

import java.io.BufferedReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Loads incident, tier, title, facet, recognition-ladder, incident-profile and credit-policy
 * definitions from datapacks and swaps them into the live registries in one step (spec §21, §9.1).
 *
 * <h2>Why one listener for all seven registries</h2>
 *
 * <p>Because they reference each other. A tier may grant a title; validation has to check that the
 * title exists. An incident may name a social profile, which names facets and a credit policy, whose
 * ranges decide whether the profile's authored numbers are even legal. Loading them as independent
 * listeners would make those checks depend on reload ordering, and a pack that is internally
 * consistent could fail or pass depending on accident. Preparing them all, validating across them,
 * and swapping them together removes the question — which is exactly why §9.1 requires the new
 * profile directories to extend this single prepared reload rather than add listeners of their own.
 *
 * <h2>Atomicity</h2>
 *
 * <p>Everything is parsed into temporary maps first. The live registries are replaced only after the
 * whole reload has been assessed, and in {@code strictJsonValidation} mode a single error means the
 * swap does not happen at all — <b>the previously loaded registries stay live and usable</b> (§21.2).
 * A broken datapack must never leave a running server with no tier ladder.
 *
 * <h2>Legacy paths</h2>
 *
 * <p>{@code mcaquests/reputation_tiers} and {@code mcaquests/titles} are read as well as the canonical
 * {@code mcareputation/…} paths, so a pack written for MCA: Quests keeps working unchanged (§21.1,
 * §32.4). Where both define the same id the canonical path wins and one warning names both sources.
 */
public final class ReputationReloadListener extends SimplePreparableReloadListener<ReputationReloadListener.Prepared> {

    private static final Gson GSON = new GsonBuilder().setLenient().create();

    private static final String INCIDENTS = "mcareputation/incidents";
    private static final String TIERS = "mcareputation/reputation_tiers";
    private static final String TITLES = "mcareputation/titles";
    private static final String LEGACY_TIERS = "mcaquests/reputation_tiers";
    private static final String LEGACY_TITLES = "mcaquests/titles";

    // §9.1: the four new profile directories. They are prepared by this same listener, not by
    // listeners of their own, because they cross-reference each other and the incident definitions —
    // see the class javadoc above for why independent listeners would make validity depend on
    // reload order.
    private static final String FACETS = "mcareputation/facets";
    private static final String RECOGNITION_TIERS = "mcareputation/recognition_tiers";
    private static final String INCIDENT_PROFILES = "mcareputation/incident_profiles";
    private static final String CREDIT_POLICIES = "mcareputation/credit_policies";

    /** Everything parsed off-thread, before anything live is touched. */
    public record Prepared(
            Map<ResourceLocation, IncidentDefinition> incidents,
            Map<ResourceLocation, ReputationTierSet> ladders,
            Map<ResourceLocation, TitleDefinition> titles,
            Map<ResourceLocation, FacetDefinition> facets,
            Map<ResourceLocation, RecognitionTierSet> recognitionLadders,
            Map<ResourceLocation, IncidentProfileDefinition> profiles,
            Map<ResourceLocation, CreditPolicy> creditPolicies,
            ReputationContentValidator.ProfileValidation profileValidation,
            List<ReputationContentValidator.Problem> problems) {

        ReputationContentValidator.ProfileContent profileContent() {
            return new ReputationContentValidator.ProfileContent(facets, recognitionLadders, profiles,
                    creditPolicies);
        }
    }

    @Override
    protected Prepared prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, IncidentDefinition> incidents = new LinkedHashMap<>();
        Map<ResourceLocation, ReputationTierSet> ladders = new LinkedHashMap<>();
        Map<ResourceLocation, TitleDefinition> titles = new LinkedHashMap<>();
        Map<ResourceLocation, FacetDefinition> facets = new LinkedHashMap<>();
        Map<ResourceLocation, RecognitionTierSet> recognitionLadders = new LinkedHashMap<>();
        Map<ResourceLocation, IncidentProfileDefinition> profiles = new LinkedHashMap<>();
        Map<ResourceLocation, CreditPolicy> creditPolicies = new LinkedHashMap<>();
        List<ReputationContentValidator.Problem> problems = new java.util.ArrayList<>();

        load(resourceManager, INCIDENTS, IncidentDefinition.CODEC, problems, incidents::put);
        // Legacy first so the canonical path overwrites it and can report the shadowing.
        load(resourceManager, LEGACY_TIERS, ReputationTierSet.CODEC, problems, ladders::put);
        loadWithShadowWarning(resourceManager, TIERS, LEGACY_TIERS, ReputationTierSet.CODEC, problems, ladders);
        load(resourceManager, LEGACY_TITLES, TitleDefinition.CODEC, problems, titles::put);
        loadWithShadowWarning(resourceManager, TITLES, LEGACY_TITLES, TitleDefinition.CODEC, problems, titles);

        // §9.6: the new schema rejects duplicate JSON keys instead of taking the last value. The
        // pre-existing directories above keep Gson's behaviour, because tightening them would start
        // rejecting packs that load today.
        loadStrict(resourceManager, FACETS, FacetDefinition.CODEC, problems, facets::put);
        loadStrict(resourceManager, RECOGNITION_TIERS, RecognitionTierSet.CODEC, problems,
                recognitionLadders::put);
        loadStrict(resourceManager, INCIDENT_PROFILES, IncidentProfileDefinition.CODEC, problems,
                profiles::put);
        loadStrict(resourceManager, CREDIT_POLICIES, CreditPolicy.CODEC, problems, creditPolicies::put);

        problems.addAll(ReputationContentValidator.validate(incidents, ladders, titles));
        ReputationContentValidator.ProfileValidation profileValidation =
                ReputationContentValidator.validateProfileContent(incidents,
                        new ReputationContentValidator.ProfileContent(facets, recognitionLadders,
                                profiles, creditPolicies));
        problems.addAll(profileValidation.problems());
        return new Prepared(incidents, ladders, titles, facets, recognitionLadders, profiles,
                creditPolicies, profileValidation, problems);
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        // All problems are reported together (§21.2) so a pack author fixes a pass at a time rather
        // than rediscovering the next error on every reload. Severity decides the log level — and,
        // in strict mode, the outcome: only genuine ERRORs may refuse the swap. Advisory warnings
        // ("it will be clamped", "the built-in ladder will be used") describe content that works,
        // and rejecting a working pack over advice would be strict mode misfiring.
        long errors = prepared.problems().stream()
                .filter(ReputationContentValidator.Problem::isError).count();
        for (ReputationContentValidator.Problem problem : prepared.problems()) {
            if (problem.isError()) {
                McaReputation.LOGGER.error("[MCA: Reputation] datapack: {}", problem);
            } else {
                McaReputation.LOGGER.warn("[MCA: Reputation] datapack: {}", problem);
            }
        }
        if (McaReputationConfig.strictJsonValidation() && errors > 0) {
            McaReputation.LOGGER.error("[MCA: Reputation] strictJsonValidation is on and {} error(s) "
                            + "were found; the reload is rejected and the previously loaded definitions "
                            + "remain live ({} incident(s), {} ladder(s), {} title(s)).",
                    errors, IncidentRegistry.size(), ReputationTiers.ids().size(),
                    Titles.ids().size());
            return;
        }

        // §9.6, lenient mode: keep the working content and disable only the invalid new profile
        // attachment. A crime definition whose optional facet reference is malformed must keep
        // scoring, so the scalar registries are published either way; the profile bundle is published
        // only once it is wholly self-consistent.
        Prepared publishable = sanitise(prepared);

        IncidentRegistry.replaceAll(publishable.incidents());
        ReputationTiers.replaceAll(publishable.ladders());
        Titles.replaceAll(publishable.titles());
        ProfileRegistryBundle bundle = ProfileRegistryBundle.publish(publishable.facets(),
                publishable.recognitionLadders(), publishable.profiles(), publishable.creditPolicies());

        McaReputation.LOGGER.info("[MCA: Reputation] loaded {} incident type(s), {} tier ladder(s), and "
                        + "{} title(s){}",
                publishable.incidents().size(), publishable.ladders().size(), publishable.titles().size(),
                prepared.problems().isEmpty() ? "" : " (" + prepared.problems().size() + " skipped or warned)");
        McaReputation.LOGGER.info("[MCA: Reputation] profile generation {}: {} facet(s), {} recognition "
                        + "ladder(s), {} incident profile(s), {} credit polic(y/ies)",
                bundle.generation(), bundle.facets().size(), bundle.recognitionLadders().size(),
                bundle.profiles().size(), bundle.creditPolicies().size());
    }

    /**
     * Removes exactly the profile content that cannot be published, and nothing else.
     *
     * <p>Three rules, in order (§9.6):
     *
     * <ol>
     *   <li>Rejected facets, ladders and credit policies are dropped, and every profile that
     *       referenced one is dropped with them — validation has already cascaded the rejection, so a
     *       surviving profile never points at a missing dependency.</li>
     *   <li>An incident whose {@code social_profile} is unusable keeps its whole scalar definition and
     *       loses only that reference. This is the case the spec calls out by name: a working crime
     *       definition must not be discarded because its optional profile attachment is malformed.</li>
     *   <li>If anything still fails to validate after that, <b>no</b> profile content is published.
     *       Half a bundle is worse than none: a profile whose facet quietly vanished would freeze
     *       different quantities onto deeds accepted before and after the reload.</li>
     * </ol>
     */
    static Prepared sanitise(Prepared prepared) {
        ReputationContentValidator.ProfileValidation validation = prepared.profileValidation();
        if (!validation.requiresSanitisation()) {
            return prepared;
        }

        Map<ResourceLocation, FacetDefinition> facets = new LinkedHashMap<>(prepared.facets());
        validation.rejectedFacets().forEach(facets::remove);
        Map<ResourceLocation, RecognitionTierSet> ladders =
                new LinkedHashMap<>(prepared.recognitionLadders());
        validation.rejectedRecognitionLadders().forEach(ladders::remove);
        Map<ResourceLocation, IncidentProfileDefinition> profiles =
                new LinkedHashMap<>(prepared.profiles());
        validation.rejectedProfiles().forEach(profiles::remove);
        Map<ResourceLocation, CreditPolicy> policies = new LinkedHashMap<>(prepared.creditPolicies());
        validation.rejectedCreditPolicies().forEach(policies::remove);

        Map<ResourceLocation, IncidentDefinition> incidents = new LinkedHashMap<>(prepared.incidents());
        for (ResourceLocation id : validation.incidentsWithUnusableProfile()) {
            IncidentDefinition definition = incidents.get(id);
            if (definition != null) {
                incidents.put(id, definition.withSocialProfile(Optional.empty()));
                McaReputation.LOGGER.warn("[MCA: Reputation] incident {} keeps working with its "
                        + "social_profile attachment disabled", id);
            }
        }

        ReputationContentValidator.ProfileContent sanitised =
                new ReputationContentValidator.ProfileContent(facets, ladders, profiles, policies);
        ReputationContentValidator.ProfileValidation recheck =
                ReputationContentValidator.validateProfileContent(incidents, sanitised);
        if (recheck.hasErrors()) {
            McaReputation.LOGGER.error("[MCA: Reputation] profile content is still inconsistent after "
                            + "disabling the invalid attachments ({}); no profile content is published "
                            + "this reload and the scalar definitions load normally",
                    recheck.problems().stream().filter(ReputationContentValidator.Problem::isError)
                            .map(Object::toString).toList());
            return new Prepared(incidents, prepared.ladders(), prepared.titles(), Map.of(), Map.of(),
                    Map.of(), Map.of(), ReputationContentValidator.ProfileValidation.CLEAN,
                    prepared.problems());
        }
        return new Prepared(incidents, prepared.ladders(), prepared.titles(), facets, ladders, profiles,
                policies, recheck, prepared.problems());
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** §9.6: the new schema's loader — identical, except that duplicate JSON keys are rejected. */
    private static <T> void loadStrict(ResourceManager resourceManager, String directory, Codec<T> codec,
                                       List<ReputationContentValidator.Problem> problems,
                                       BiConsumer<ResourceLocation, T> sink) {
        load(resourceManager, directory, codec, problems, sink, true);
    }

    private static <T> void load(ResourceManager resourceManager, String directory, Codec<T> codec,
                                 List<ReputationContentValidator.Problem> problems,
                                 BiConsumer<ResourceLocation, T> sink) {
        load(resourceManager, directory, codec, problems, sink, false);
    }

    private static <T> void load(ResourceManager resourceManager, String directory, Codec<T> codec,
                                 List<ReputationContentValidator.Problem> problems,
                                 BiConsumer<ResourceLocation, T> sink, boolean rejectDuplicateKeys) {
        for (Map.Entry<ResourceLocation, Resource> entry
                : resourceManager.listResources(directory, path -> path.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation file = entry.getKey();
            ResourceLocation id = toDefinitionId(file, directory);
            if (id == null) {
                problems.add(ReputationContentValidator.Problem.error("could not derive an id from " + file));
                continue;
            }
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                DataResult<JsonElement> json = rejectDuplicateKeys
                        ? StrictJson.parse(reader)
                        : DataResult.success(JsonParser.parseReader(GSON.newJsonReader(reader)));
                DataResult<T> parsed = json.flatMap(element -> codec.parse(JsonOps.INSTANCE, element));
                parsed.resultOrPartial(error -> problems.add(
                                ReputationContentValidator.Problem.error(file + ": " + error)))
                        .ifPresent(value -> sink.accept(id, value));
            } catch (Throwable t) {
                // §15: an invalid definition is skipped and reported; it never crashes /reload or
                // world creation, and never costs us the files that parsed correctly.
                problems.add(ReputationContentValidator.Problem.error(
                        file + ": " + t.getClass().getSimpleName() + " " + t.getMessage()));
            }
        }
    }

    /**
     * Loads a canonical directory over a legacy one, warning once per id that exists in both. The
     * canonical definition wins (§21.1); the warning names both sources so a pack author can tell
     * which file is actually in effect.
     */
    private static <T> void loadWithShadowWarning(ResourceManager resourceManager, String directory,
                                                  String legacyDirectory, Codec<T> codec,
                                                  List<ReputationContentValidator.Problem> problems,
                                                  Map<ResourceLocation, T> target) {
        Map<ResourceLocation, T> canonical = new LinkedHashMap<>();
        load(resourceManager, directory, codec, problems, canonical::put);
        canonical.forEach((id, value) -> {
            if (target.containsKey(id)) {
                McaReputation.LOGGER.warn("[MCA: Reputation] '{}' is defined in both {} and {}; the {} "
                        + "definition wins", id, directory, legacyDirectory, directory);
            }
            target.put(id, value);
        });
    }

    /**
     * {@code mcareputation:mcareputation/incidents/crime/assault.json} →
     * {@code mcareputation:crime/assault}. Returns null when the path does not sit under the
     * directory, which cannot normally happen but is cheaper to check than to debug.
     */
    private static ResourceLocation toDefinitionId(ResourceLocation file, String directory) {
        String path = file.getPath();
        String prefix = directory + "/";
        if (!path.startsWith(prefix) || !path.endsWith(".json")) {
            return null;
        }
        String trimmed = path.substring(prefix.length(), path.length() - ".json".length());
        return trimmed.isEmpty() ? null : ResourceLocation.tryParse(file.getNamespace() + ":" + trimmed);
    }

    /** Test seam: parse a single definition from raw JSON exactly as the loader would. */
    public static <T> DataResult<T> parseForTest(Codec<T> codec, String json) {
        return codec.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    /**
     * Test seam for the new schema: parse exactly as {@link #loadStrict} would, so a duplicate-key
     * rejection is asserted through the same path the reload uses rather than a re-implementation.
     */
    public static <T> DataResult<T> parseStrictForTest(Codec<T> codec, String json) {
        return StrictJson.parse(json).flatMap(element -> codec.parse(JsonOps.INSTANCE, element));
    }
}

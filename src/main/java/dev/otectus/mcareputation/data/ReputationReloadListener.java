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
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.reputation.ReputationTierSet;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import dev.otectus.mcareputation.reputation.TitleDefinition;
import dev.otectus.mcareputation.reputation.Titles;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.BufferedReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Loads incident, tier, and title definitions from datapacks and swaps them into the live registries
 * in one step (spec §21).
 *
 * <h2>Why one listener for three registries</h2>
 *
 * <p>Because they reference each other. A tier may grant a title; validation has to check that the
 * title exists. Loading them as three independent listeners would make that check depend on
 * reload ordering, and a pack that is internally consistent could fail or pass depending on
 * accident. Preparing all three, validating across them, and swapping all three together removes the
 * question.
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

    /** Everything parsed off-thread, before anything live is touched. */
    public record Prepared(
            Map<ResourceLocation, IncidentDefinition> incidents,
            Map<ResourceLocation, ReputationTierSet> ladders,
            Map<ResourceLocation, TitleDefinition> titles,
            List<ReputationContentValidator.Problem> problems) {
    }

    @Override
    protected Prepared prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, IncidentDefinition> incidents = new LinkedHashMap<>();
        Map<ResourceLocation, ReputationTierSet> ladders = new LinkedHashMap<>();
        Map<ResourceLocation, TitleDefinition> titles = new LinkedHashMap<>();
        List<ReputationContentValidator.Problem> problems = new java.util.ArrayList<>();

        load(resourceManager, INCIDENTS, IncidentDefinition.CODEC, problems, incidents::put);
        // Legacy first so the canonical path overwrites it and can report the shadowing.
        load(resourceManager, LEGACY_TIERS, ReputationTierSet.CODEC, problems, ladders::put);
        loadWithShadowWarning(resourceManager, TIERS, LEGACY_TIERS, ReputationTierSet.CODEC, problems, ladders);
        load(resourceManager, LEGACY_TITLES, TitleDefinition.CODEC, problems, titles::put);
        loadWithShadowWarning(resourceManager, TITLES, LEGACY_TITLES, TitleDefinition.CODEC, problems, titles);

        problems.addAll(ReputationContentValidator.validate(incidents, ladders, titles));
        return new Prepared(incidents, ladders, titles, problems);
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

        IncidentRegistry.replaceAll(prepared.incidents());
        ReputationTiers.replaceAll(prepared.ladders());
        Titles.replaceAll(prepared.titles());

        McaReputation.LOGGER.info("[MCA: Reputation] loaded {} incident type(s), {} tier ladder(s), and "
                        + "{} title(s){}",
                prepared.incidents().size(), prepared.ladders().size(), prepared.titles().size(),
                prepared.problems().isEmpty() ? "" : " (" + prepared.problems().size() + " skipped or warned)");
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private static <T> void load(ResourceManager resourceManager, String directory, Codec<T> codec,
                                 List<ReputationContentValidator.Problem> problems,
                                 BiConsumer<ResourceLocation, T> sink) {
        for (Map.Entry<ResourceLocation, Resource> entry
                : resourceManager.listResources(directory, path -> path.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation file = entry.getKey();
            ResourceLocation id = toDefinitionId(file, directory);
            if (id == null) {
                problems.add(ReputationContentValidator.Problem.error("could not derive an id from " + file));
                continue;
            }
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(GSON.newJsonReader(reader));
                DataResult<T> parsed = codec.parse(JsonOps.INSTANCE, json);
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
}

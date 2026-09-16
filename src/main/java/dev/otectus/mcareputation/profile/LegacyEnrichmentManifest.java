package dev.otectus.mcareputation.profile;

import dev.otectus.mcareputation.McaReputation;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The frozen mapping the §19.2 migration is allowed to use: which pre-upgrade incident types may be
 * given profile evidence, and under which profile.
 *
 * <h2>Why this is code and not a datapack</h2>
 *
 * <p>§19.2 requires a <b>versioned, fixed migration manifest</b>. If enrichment read the live
 * {@code social_profile} field instead, every {@code /reload} would silently rewrite history: a pack
 * that repoints {@code villager_rescued} at a more generous profile would retroactively award the
 * difference to deeds done years earlier. The mapping below is therefore fixed at
 * {@link #VERSION} and changes only with an explicit, documented bump.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>Only the built-in types that are <em>unambiguously</em> one kind of event appear here. The
 * generic completion incidents ({@code quest_completed}, {@code project_completed},
 * {@code situation_resolved}) do not: §9.5 exists precisely because one such incident can mean a
 * humanitarian rescue, a crafting commission or a dangerous contract, and the old save recorded
 * nothing that distinguishes them. Custom and unknown types stay explicitly unenriched, which §19.2
 * requires and §19.3 reports as {@code PARTIAL_LEGACY} coverage rather than as a clean record.
 *
 * <p>Enrichment is further narrowed at the point of use: only {@code recognition} and
 * {@code historical} channels are reconstructed. An {@code evaluative} channel is a moral judgement
 * about how a wrong reflects on the player <em>now</em>, and §19.2 forbids inferring culpability for
 * an old assault or killing whose authoritative context was never stored. An old killing can
 * therefore contribute the recognition and the violence it factually demonstrated, and nothing about
 * how sorry anybody was.
 */
public final class LegacyEnrichmentManifest {

    /** Bump only with a documented change to the mapping below. Stored in the migration state. */
    public static final int VERSION = 1;

    private static final Map<ResourceLocation, ResourceLocation> MAPPING = build();

    private LegacyEnrichmentManifest() {
    }

    private static Map<ResourceLocation, ResourceLocation> build() {
        Map<ResourceLocation, ResourceLocation> mapping = new LinkedHashMap<>();
        mapping.put(McaReputation.id("villager_assaulted"), McaReputation.id("assaulted_villager"));
        mapping.put(McaReputation.id("villager_killed"), McaReputation.id("killed_villager"));
        mapping.put(McaReputation.id("villager_rescued"), McaReputation.id("rescued_villager"));
        mapping.put(McaReputation.id("villager_cured"), McaReputation.id("cured_villager"));
        mapping.put(McaReputation.id("raid_repelled"), McaReputation.id("repelled_raid"));
        mapping.put(McaReputation.id("promise_kept"), McaReputation.id("kept_commitment"));
        mapping.put(McaReputation.id("promise_broken"), McaReputation.id("broken_commitment"));
        return Collections.unmodifiableMap(mapping);
    }

    /** The profile the manifest names for this incident type, or empty when it names none. */
    public static Optional<ResourceLocation> profileFor(ResourceLocation incidentType) {
        return incidentType == null
                ? Optional.empty()
                : Optional.ofNullable(MAPPING.get(incidentType));
    }

    /** Whether §19.2 permits enriching this incident type at all. */
    public static boolean covers(ResourceLocation incidentType) {
        return profileFor(incidentType).isPresent();
    }

    /** The whole mapping, for diagnostics and the dry-run report. */
    public static Map<ResourceLocation, ResourceLocation> mapping() {
        return MAPPING;
    }

    /**
     * Whether this authored contribution may be reconstructed for a pre-upgrade deed.
     *
     * <p>{@code evaluative} is refused: it is a judgement about present conduct that depends on
     * apology, atonement and forgiveness state the old save never recorded for this channel (§19.2).
     */
    public static boolean enrichable(IncidentProfileDefinition.Contribution contribution) {
        return contribution != null
                && contribution.resolutionMode() != IncidentProfileDefinition.ResolutionMode.EVALUATIVE;
    }
}

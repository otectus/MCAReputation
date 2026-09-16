package dev.otectus.mcareputation.network;

import dev.otectus.mcareputation.api.profile.FacetValue;
import dev.otectus.mcareputation.api.profile.ProfileAvailability;
import dev.otectus.mcareputation.api.profile.ProfileCoverage;
import dev.otectus.mcareputation.api.profile.ProfileQueryResult;
import dev.otectus.mcareputation.api.profile.ProfileSnapshot;
import dev.otectus.mcareputation.api.profile.VillagerProfileSnapshot;
import dev.otectus.mcareputation.profile.FacetDefinition;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.profile.RecognitionTierSet;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a server-side profile answer into the bounded thing that crosses the wire (§18.3).
 *
 * <h2>Why this is not done in the screen</h2>
 *
 * <p>Every label here comes from a datapack the <b>server</b> loaded. A dedicated-server client holds
 * the facet and recognition definitions it shipped with, not the ones the world is running, so a
 * client that resolved its own labels would describe a player by a pack nobody is playing — the same
 * reason titles and tier names have crossed the wire resolved since 0.3.0. The client is handed
 * finished {@link Component}s and a handful of integers, and reconstructs no authority of its own.
 *
 * <h2>Bounded by construction</h2>
 *
 * <p>At most {@link ReputationBounds#MAX_SYNCED_DOMINANT_TRAITS} traits and
 * {@link ReputationBounds#MAX_SYNCED_FACET_DETAILS} detail entries leave here, in a deterministic
 * order: the dominance order §8.2 already fixed, then authored display order, then facet id. Ordering
 * matters beyond tidiness — a tie broken by map iteration would let the screen, the packet and the
 * diagnostics disagree about which three traits describe a player.
 *
 * <p>Pure: a {@link ProfileQueryResult}, a content bundle and two flags in, a record out. No server,
 * no level and no registries, so the projection is exercisable in a plain unit test.
 */
final class ProfileProjection {

    private ProfileProjection() {
    }

    /**
     * The selected community's public profile, or the honest reason there is none.
     *
     * <p>An unavailable answer still travels: "profiles are switched off", "this store is read-only"
     * and "we have not finished reading your older history" are different facts, and §18.2 requires
     * the screen to be able to tell them apart instead of showing an empty pane for all three.
     */
    static ReputationNetwork.ProfileSummary summary(ProfileQueryResult<ProfileSnapshot> result,
                                                    @Nullable ProfileRegistryBundle bundle,
                                                    boolean readOnlyStore) {
        ProfileRegistryBundle content = bundle == null ? ProfileRegistryBundle.EMPTY : bundle;
        if (result == null) {
            return unavailable(ProfileAvailability.ERROR, "internal_error", readOnlyStore);
        }
        Optional<ProfileSnapshot> maybe = result.value();
        if (!result.isAvailable() || maybe.isEmpty()) {
            return unavailable(result.availability(), result.reason().orElse(""), readOnlyStore);
        }
        ProfileSnapshot snapshot = maybe.get();
        List<ResourceLocation> dominant = snapshot.dominantFacets().stream()
                .limit(ReputationBounds.MAX_SYNCED_DOMINANT_TRAITS).toList();
        List<Component> traits = new ArrayList<>(dominant.size());
        for (ResourceLocation facet : dominant) {
            traits.add(traitLabel(content, facet, snapshot.facet(facet)));
        }
        return new ReputationNetwork.ProfileSummary(
                ProfileAvailability.AVAILABLE,
                snapshot.coverage(),
                readOnlyStore,
                Optional.empty(),
                snapshot.recognition().value(),
                snapshot.recognition().tierId(),
                recognitionTierName(content, snapshot.recognition().tierId()),
                snapshot.recognition().evidenceCount(),
                traits,
                details(content, snapshot, dominant),
                snapshot.profileRevision(),
                snapshot.definitionGeneration());
    }

    /**
     * What one villager knows the player for, beside the opinion that knowledge adds up to.
     *
     * <p>The villager's own knowledge-filtered profile is projected by the same method the community
     * profile is, so the two panes cannot describe the same facet in different words. The three
     * opinion integers travel because §13.4 asks for the applied capped adjustment to be explainable;
     * whether they are <em>shown</em> as numbers is a client presentation choice.
     */
    static Optional<ReputationNetwork.VillagerProfileSummary> villagerSummary(
            ProfileQueryResult<VillagerProfileSnapshot> result, @Nullable ProfileRegistryBundle bundle,
            @Nullable UUID villagerId, Component villagerName, Component opinionTierName,
            boolean readOnlyStore) {
        if (result == null || villagerId == null) {
            return Optional.empty();
        }
        Optional<VillagerProfileSnapshot> maybe = result.value();
        if (!result.isAvailable() || maybe.isEmpty()) {
            // A speaker the server could not resolve is not a speaker with an empty view (§13.3), and
            // nothing about that is worth a pane: the community profile beside it already carries the
            // availability story. Sending no observer pane is the honest answer here.
            return Optional.empty();
        }
        VillagerProfileSnapshot view = maybe.get();
        return Optional.of(new ReputationNetwork.VillagerProfileSummary(
                villagerId,
                villagerName == null ? Component.empty() : villagerName,
                summary(ProfileQueryResult.available(view.knownProfile()), bundle, readOnlyStore),
                view.baseOpinion(),
                view.facetAdjustment(),
                view.finalOpinion(),
                opinionTierName == null ? Component.empty() : opinionTierName,
                view.traitBasis(),
                view.involvedCount(),
                view.witnessedCount(),
                view.hearsayCount()));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ReputationNetwork.ProfileSummary unavailable(ProfileAvailability availability,
                                                                String reason, boolean readOnlyStore) {
        return new ReputationNetwork.ProfileSummary(
                availability == null ? ProfileAvailability.ERROR : availability,
                ProfileCoverage.MIGRATING, readOnlyStore,
                reason == null || reason.isEmpty() ? Optional.empty() : Optional.of(reason),
                0, "", Component.empty(), 0, List.of(), List.of(), 0L, 0L);
    }

    /**
     * The word this facet's value earns, from the pack that authored it.
     *
     * <p>A dominant facet always has a sign, so {@link FacetDefinition#labelFor} normally answers;
     * the facet's own name is the fallback for the one case it cannot — a definition that has left
     * the datapacks since the evidence was frozen (§9.4), where a stand-in naming the id is still
     * better than a blank line.
     */
    private static Component traitLabel(ProfileRegistryBundle content, ResourceLocation facet,
                                        Optional<FacetValue> value) {
        FacetDefinition definition = content.facetOrUnknown(facet);
        return value.flatMap(held -> definition.labelFor(held.value())).orElse(definition.name());
    }

    /**
     * The authored name of the tier the aggregation already chose, looked up by id rather than
     * recomputed from the value: the value has been capped by the policy since, and re-deriving the
     * rung here could name a tier the snapshot does not claim.
     */
    private static Component recognitionTierName(ProfileRegistryBundle content, String tierId) {
        return content.recognitionLadderOrDefault(RecognitionTierSet.DEFAULT_ID).byId(tierId)
                .map(RecognitionTierSet.Tier::name)
                .orElseGet(() -> tierId == null || tierId.isEmpty()
                        ? Component.empty()
                        : Component.literal(tierId));
    }

    /**
     * The detail entries for one pane: the dominant facets first, then whatever else this community
     * holds evidence on, by authored display order and then by id.
     *
     * <p>Dominant first because those are the facets the compact lines already named, and a pane that
     * listed them last would read as though the numbers belonged to something else.
     */
    private static List<ReputationNetwork.FacetSummary> details(ProfileRegistryBundle content,
                                                                ProfileSnapshot snapshot,
                                                                List<ResourceLocation> dominant) {
        Set<ResourceLocation> ordered = new LinkedHashSet<>(dominant);
        List<FacetValue> rest = new ArrayList<>();
        for (FacetValue value : snapshot.facets()) {
            if (!ordered.contains(value.facet())) {
                rest.add(value);
            }
        }
        rest.sort(Comparator
                .<FacetValue>comparingInt(value -> content.facetOrUnknown(value.facet()).displayOrder())
                .thenComparing(FacetValue.BY_ID));
        for (FacetValue value : rest) {
            ordered.add(value.facet());
        }

        List<ReputationNetwork.FacetSummary> out = new ArrayList<>();
        for (ResourceLocation facet : ordered) {
            if (out.size() >= ReputationBounds.MAX_SYNCED_FACET_DETAILS) {
                break;
            }
            Optional<FacetValue> held = snapshot.facet(facet);
            if (held.isEmpty()) {
                continue;
            }
            FacetValue value = held.get();
            FacetDefinition definition = content.facetOrUnknown(facet);
            out.add(new ReputationNetwork.FacetSummary(facet, definition.name(),
                    definition.labelFor(value.value()), value.value(), definition.range().min(),
                    definition.range().max(), value.supportingEvidence(), value.opposingEvidence(),
                    value.labelEligible()));
        }
        return out;
    }
}

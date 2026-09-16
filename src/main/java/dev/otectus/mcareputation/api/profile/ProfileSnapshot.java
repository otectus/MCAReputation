package dev.otectus.mcareputation.api.profile;

import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One community's whole public view of one player, as of one evaluation time (§14.2).
 *
 * <p>Immutable, defensively copied, and <b>explicitly sorted</b>: {@link #facets} is ordered by facet
 * id and {@link #dominantFacets} by §8.2's dominance order, never by map iteration. That is a
 * correctness requirement rather than tidiness — a tie broken by hash order would let a screen, a
 * packet and a log disagree about which three traits a player is known for.
 *
 * <p>The three revisions answer three different questions and must not be conflated (§15):
 * {@link #standingRevision} moves when the scalar score does, {@link #profileRevision} when profile
 * evidence does, and {@link #definitionGeneration} when a datapack reload republishes the
 * interpretation. A consumer caching profile-dependent state invalidates on
 * {@code profileRevision} and {@code definitionGeneration}; scalar standing events alone are not
 * enough.
 *
 * @param playerId            who the profile is about
 * @param community           which community holds it
 * @param standingScore       the reconciled scalar standing, for context
 * @param standingTierId      the standing tier that score falls in
 * @param recognition         how widely known the player is here
 * @param facets              every facet with a value or live evidence, ordered by id
 * @param dominantFacets      at most three label-eligible facets, strongest first (§8.2)
 * @param standingRevision    the community record's standing revision
 * @param profileRevision     the community record's profile revision
 * @param definitionGeneration the published profile-content generation this was interpreted under
 * @param evaluationTime      the game time this answer was evaluated at
 * @param coverage            how complete the history behind it is
 * @since MCA: Reputation 0.6.0
 */
public record ProfileSnapshot(UUID playerId, CommunityKey community, int standingScore,
                              String standingTierId, RecognitionValue recognition,
                              List<FacetValue> facets, List<ResourceLocation> dominantFacets,
                              long standingRevision, long profileRevision,
                              long definitionGeneration, long evaluationTime,
                              ProfileCoverage coverage) {

    public ProfileSnapshot {
        standingTierId = standingTierId == null ? "" : standingTierId;
        recognition = recognition == null ? RecognitionValue.none("") : recognition;
        facets = sortedById(facets);
        dominantFacets = dominantFacets == null ? List.of() : List.copyOf(dominantFacets);
        coverage = coverage == null ? ProfileCoverage.MIGRATING : coverage;
    }

    private static List<FacetValue> sortedById(List<FacetValue> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<FacetValue> copy = new ArrayList<>(raw);
        copy.sort(FacetValue.BY_ID);
        return List.copyOf(copy);
    }

    /** One facet's value, or empty when this community holds nothing on it. */
    public Optional<FacetValue> facet(ResourceLocation facet) {
        if (facet == null) {
            return Optional.empty();
        }
        for (FacetValue value : facets) {
            if (value.facet().equals(facet)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether this community holds any live public evidence about the player.
     *
     * <p>{@code false} together with {@link ProfileCoverage#COMPLETE_SINCE_RECORD_START} is the
     * honest "complete history, genuine stranger" answer §14.5 requires to stay expressible.
     */
    public boolean observed() {
        if (recognition.observed()) {
            return true;
        }
        for (FacetValue value : facets) {
            if (value.observed()) {
                return true;
            }
        }
        return false;
    }
}

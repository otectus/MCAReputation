package dev.otectus.mcareputation.profile;

import dev.otectus.mcareputation.api.profile.FacetValue;
import dev.otectus.mcareputation.api.profile.VillagerProfileSnapshot;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityResolver;
import dev.otectus.mcareputation.compat.McaCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The one observer-trait resolver, and the one facet-to-opinion term (§13.2).
 *
 * <h2>Why one resolver and not two</h2>
 *
 * <p>§13.2 requires the entity and UUID API entry points to agree about the same loaded villager.
 * Two resolvers — one reading an entity in hand, one looking a UUID up — is how they stop agreeing:
 * the first drifts toward "whatever this entity happens to expose" and the second toward "whatever
 * the store remembers". So both entry points land here, and the UUID entry point resolves the same
 * loaded entity the caller would have passed. Nothing is cached: the answer is worth exactly as long
 * as the interaction that asked for it.
 *
 * <h2>What this refuses to do</h2>
 *
 * <ul>
 *   <li><b>Never loads a chunk and never scans the world.</b> The lookup is the level's own map of
 *       already-loaded entities, consulted in the community's dimension first. An unloaded villager
 *       has <em>unresolved</em> traits, which is a real answer; guessing them from a name, a family
 *       tree entry or a neighbour would be a fabricated personality.</li>
 *   <li><b>Never invents a personality.</b> Only what MCA reports, normalised. An MCA that reports
 *       nothing yields {@link ObserverTraits#NEUTRAL} and the authored default weights, which §13.2
 *       makes mandatory rather than optional.</li>
 *   <li><b>Never stores a per-villager vector.</b> There is no write path here at all; the facet term
 *       is derived on every read from evidence that was already knowledge-filtered upstream.</li>
 * </ul>
 *
 * <p>The knowledge filter itself is deliberately <em>not</em> here: it runs per incident inside
 * {@code reputation.ProfileAggregator} before anything is aggregated (§13.1), and this class only
 * ever sees facet values that survived it. Interpreting a community-wide vector with a villager's
 * personality would leak events that villager never learned, however carefully the weights were
 * chosen.
 *
 * <p>Layering: this package is a leaf below {@code reputation}, so the policy values this needs
 * arrive as parameters rather than as a {@code ReputationPolicy} import. That keeps the dependency
 * one-directional and is also what lets every rule below be tested with no server running.
 */
public final class VillagerProfileResolver {

    private VillagerProfileResolver() {
    }

    // ------------------------------------------------------------------
    // Observer traits
    // ------------------------------------------------------------------

    /**
     * What could be read about one observer's own interpretation traits.
     *
     * <p>{@code personality} is a bare lowercase id ({@code odd}, {@code upbeat}) because that is the
     * form {@link FacetDefinition#personalityOverrides()} keys are normalised to (§9.2), so an
     * authored override and a resolved villager can actually meet. {@code profession} keeps its
     * namespace ({@code minecraft:farmer}, {@code mca:guard}): nothing authored consumes it yet, and
     * two namespaces may legitimately ship the same path, so dropping the namespace would invent an
     * ambiguity for no authoring benefit.
     *
     * <p>Both are optional and both default to absent. An absent value means "MCA did not tell us",
     * never "the villager has no personality" — MCA has its own {@code unassigned} personality for
     * that, and it is reported as the id it is rather than folded into the unresolved case.
     */
    public record ObserverTraits(Optional<String> personality, Optional<String> profession) {

        /** The mandatory neutral fallback: authored default weights, and an honest basis. */
        public static final ObserverTraits NEUTRAL = new ObserverTraits(Optional.empty(),
                Optional.empty());

        public ObserverTraits {
            personality = normalize(personality, true);
            profession = normalize(profession, false);
        }

        /** Normalises whatever MCA reported; blank or unusable values become absent. */
        public static ObserverTraits of(@Nullable String personality, @Nullable String profession) {
            return new ObserverTraits(Optional.ofNullable(personality), Optional.ofNullable(profession));
        }

        /**
         * Whether the interpretation weights were selected from this observer's own traits.
         *
         * <p>Personality is the deciding trait because it is the only one the facet schema authors a
         * weight for (§9.2). A resolved profession with no personality is still a neutral
         * interpretation and says so, rather than claiming a resolution that changed nothing.
         */
        public boolean interpretationResolved() {
            return personality.isPresent();
        }

        /** Whether MCA answered about either trait at all; for diagnostics, not for weighting. */
        public boolean anyResolved() {
            return personality.isPresent() || profession.isPresent();
        }

        private static Optional<String> normalize(@Nullable Optional<String> raw, boolean bare) {
            if (raw == null || raw.isEmpty()) {
                return Optional.empty();
            }
            String value = raw.get().strip().toLowerCase(Locale.ROOT);
            if (bare) {
                int colon = value.indexOf(':');
                if (colon >= 0) {
                    value = value.substring(colon + 1);
                }
            }
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
    }

    /**
     * The traits of a villager the caller already has in hand.
     *
     * <p>Reads only current MCA metadata through the compatibility seam, which fails safe: with MCA
     * absent, latched off, or changed under this build, every read answers empty and the result is
     * {@link ObserverTraits#NEUTRAL}.
     */
    public static ObserverTraits traits(@Nullable Entity villager) {
        if (villager == null) {
            return ObserverTraits.NEUTRAL;
        }
        return ObserverTraits.of(McaCompat.personality(villager).orElse(null),
                McaCompat.profession(villager).orElse(null));
    }

    /**
     * The traits of a villager named only by UUID — the same answer the entity overload gives for the
     * same loaded villager (§13.2).
     *
     * <p>The community's own dimension is consulted first because a speaker question is always
     * community-scoped; the remaining loaded levels are tried afterwards so a villager who has
     * stepped through a portal is not mistaken for an unloaded one. Both are lookups in a map of
     * already-loaded entities: no chunk is loaded and no region is scanned. A villager who is not
     * loaded anywhere resolves to {@link ObserverTraits#NEUTRAL}.
     */
    public static ObserverTraits traits(@Nullable MinecraftServer server,
                                        @Nullable CommunityKey community, @Nullable UUID villager) {
        if (server == null || villager == null) {
            return ObserverTraits.NEUTRAL;
        }
        try {
            Optional<ServerLevel> home = community == null
                    ? Optional.empty()
                    : CommunityResolver.levelOf(server, community);
            if (home.isPresent()) {
                Entity found = home.get().getEntity(villager);
                if (found != null) {
                    return traits(found);
                }
            }
            for (ServerLevel level : server.getAllLevels()) {
                if (home.isPresent() && level == home.get()) {
                    continue;
                }
                Entity found = level.getEntity(villager);
                if (found != null) {
                    return traits(found);
                }
            }
            return ObserverTraits.NEUTRAL;
        } catch (Throwable t) {
            // An unresolved observer interprets neutrally. There is no failure mode here worth
            // propagating into a dialogue check.
            return ObserverTraits.NEUTRAL;
        }
    }

    // ------------------------------------------------------------------
    // The facet opinion term (§13.2)
    // ------------------------------------------------------------------

    /**
     * One facet's share of the opinion term, for §13.4's bounded explanation.
     *
     * @param facet              the facet id
     * @param value              the observer's known display value for it
     * @param weightBp           the interpretation weight actually applied, in basis points
     * @param personalityApplied whether that weight came from a personality override
     */
    public record Contribution(ResourceLocation facet, int value, int weightBp,
                               boolean personalityApplied) {
    }

    /**
     * The applied facet term, its basis, and what went into it.
     *
     * @param adjustment    the capped opinion adjustment actually applied
     * @param traitBasis    how the weights were arrived at, reported rather than hidden (§13.2)
     * @param uncapped      the term before the operator's cap, so a clamped answer is explainable
     * @param contributions at most one entry per contributing facet, in facet-id order
     */
    public record OpinionAdjustment(int adjustment, VillagerProfileSnapshot.TraitBasis traitBasis,
                                    int uncapped, List<Contribution> contributions) {

        public OpinionAdjustment {
            contributions = contributions == null ? List.of() : List.copyOf(contributions);
        }

        /** Whether the operator's cap actually bound the term. */
        public boolean capped() {
            return adjustment != uncapped;
        }
    }

    /**
     * §13.2's {@code facetAdjustment}: the known facet values, weighted by their authored
     * interpretation weights, summed, and clamped to the operator's cap.
     *
     * <p><b>One term, never three bonuses (R04).</b> The community baseline and the deeds this
     * observer knows are already inside the base opinion this is added to; the facets are a second
     * reading of that same evidence, so they contribute one capped term rather than an independent
     * bonus per channel.
     *
     * <p><b>Two bounds, in different units, and both apply.</b> Each facet's contribution is its
     * display value scaled by the authored {@code opinion_weight_bp} — the facet's own bound, which a
     * pack sets and validation keeps within ±20000 — and the sum is then clamped to
     * {@code maxAdjustment} opinion points, the operator's bound (§13.2's 25 by default). Neither
     * bound substitutes for the other: a pack cannot out-author the operator, and the operator cannot
     * make a facet matter that its pack weighted at zero.
     *
     * <p>The personality override is applied only when this observer's personality actually resolved.
     * An unresolved personality uses the authored default weight, which is what makes the fallback
     * neutral rather than optimistic.
     *
     * @param facets              this observer's <em>already knowledge-filtered</em> facet values
     * @param facetOpinionEnabled the operator's master switch for facet interpretation
     * @param maxAdjustment       the operator's cap, in opinion points
     */
    public static OpinionAdjustment facetAdjustment(@Nullable ProfileRegistryBundle bundle,
                                                    @Nullable List<FacetValue> facets,
                                                    @Nullable ObserverTraits traits,
                                                    boolean facetOpinionEnabled, int maxAdjustment) {
        ObserverTraits observer = traits == null ? ObserverTraits.NEUTRAL : traits;
        if (!facetOpinionEnabled) {
            // Switched off is not "zero facets known": no weighting happened at all, and the basis
            // says so instead of being reported as a neutral interpretation that did.
            return new OpinionAdjustment(0, VillagerProfileSnapshot.TraitBasis.DISABLED, 0, List.of());
        }
        VillagerProfileSnapshot.TraitBasis basis = observer.interpretationResolved()
                ? VillagerProfileSnapshot.TraitBasis.RESOLVED
                : VillagerProfileSnapshot.TraitBasis.NEUTRAL_DEFAULT;
        ProfileRegistryBundle content = bundle == null ? ProfileRegistryBundle.EMPTY : bundle;
        String personality = observer.personality().orElse(null);
        long weightedBp = 0L;
        List<Contribution> contributions = new ArrayList<>();
        if (facets != null) {
            for (FacetValue facet : facets) {
                if (facet == null || !facet.observed() || facet.value() == 0) {
                    // Unobserved is not negative evidence (§14.5) and a zero value describes nobody,
                    // so neither may push an opinion in either direction.
                    continue;
                }
                FacetDefinition definition = content.facetOrUnknown(facet.facet());
                int weightBp = definition.opinionWeightFor(personality);
                if (weightBp == 0) {
                    continue;
                }
                weightedBp = ProfileMath.add(weightedBp, (long) facet.value() * weightBp);
                contributions.add(new Contribution(facet.facet(), facet.value(), weightBp,
                        personality != null
                                && definition.personalityOverrides().containsKey(personality)));
            }
        }
        // Truncated toward zero, like every other partial term in this mod: a weighting reduces
        // magnitude rather than rounding a discount away.
        long points = weightedBp / ProfileMath.FULL_BP;
        int uncapped = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, points));
        int cap = Math.max(0, maxAdjustment);
        return new OpinionAdjustment(ProfileMath.clamp(uncapped, -cap, cap), basis, uncapped,
                contributions);
    }

    /**
     * The trait basis when no facet term is computed at all — an observer with no stored record, or
     * one whose community holds no evidence.
     *
     * <p>Still an honest answer about the interpretation, not about the evidence: a resolved
     * personality that had nothing to interpret is {@code RESOLVED}, because the next deed will be
     * read with this villager's own weights.
     */
    public static VillagerProfileSnapshot.TraitBasis traitBasis(@Nullable ObserverTraits traits,
                                                                boolean facetOpinionEnabled) {
        return facetAdjustment(null, List.of(), traits, facetOpinionEnabled, 0).traitBasis();
    }
}

package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.api.VillagerOpinion.OpinionBasis;
import dev.otectus.mcareputation.api.profile.VillagerProfileSnapshot;
import dev.otectus.mcareputation.incident.AwarenessResolver;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.profile.ProfileMath;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.profile.VillagerProfileResolver;
import dev.otectus.mcareputation.state.CommunityReputationRecord;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Folds a community ledger into what one villager makes of one player (§19.3).
 *
 * <h2>Why nothing new is stored</h2>
 *
 * <p>A per-villager number would be a second store of the same fact, and §13.4 already says the
 * village score is a cache of the ledger. So this derives instead: every incident the villager
 * {@linkplain AwarenessResolver#knows knows about} contributes its <em>current</em> weight, scaled by
 * how they came to know it. Nothing ticks, nothing is saved, and the answer is the same on every
 * query, which is also what makes it testable with no game running.
 *
 * <h2>The three weights</h2>
 *
 * <ul>
 *   <li><b>Involved</b> — the villager is a subject of the deed. Weighted hardest, and it beats being
 *       a witness: what was done to you counts for more than what you watched.</li>
 *   <li><b>Witnessed</b> — they saw it. Full weight.</li>
 *   <li><b>Hearsay</b> — they were told, once the rumour reached them. Weakest, and it is also the
 *       weight the community baseline carries, because a baseline is the village's general sense of
 *       someone rather than anything this villager personally saw.</li>
 * </ul>
 *
 * <p>A villager who is not a resident hears no rumours and carries no baseline; they keep only what
 * they witnessed or were part of themselves, which is the same rule {@code AwarenessResolver} applies.
 *
 * <p>Every method here is pure and free of Minecraft types.
 */
public final class OpinionResolver {

    private OpinionResolver() {
    }

    /**
     * One villager's derived view. {@code score} is already clamped to the caller's score window, so it
     * sits on the same ladder — and therefore in the same tier bands — as village standing.
     */
    public record Opinion(int score, OpinionBasis basis, int knownIncidents) {

        /** The "this villager has never heard of you" answer. */
        public static final Opinion NOTHING = new Opinion(0, OpinionBasis.NONE, 0);
    }

    /**
     * Folds {@code record} through {@code villager}'s knowledge.
     *
     * @param resident          whether the villager currently lives in this community; a villager who
     *                          moved away keeps only what they witnessed or were part of
     * @param hearsayPercent    weight, in percent, of a deed only heard about, and of the baseline
     * @param involvedPercent   weight, in percent, of a deed the villager was a subject of
     */
    public static Opinion resolve(CommunityReputationRecord record, UUID villager, boolean resident,
                                  long gameTime, int minRumorDelayTicks, int maxRumorDelayTicks,
                                  int hearsayPercent, int involvedPercent, int minScore, int maxScore) {
        if (record == null || villager == null) {
            return Opinion.NOTHING;
        }
        float hearsay = Math.max(0, hearsayPercent) / 100.0f;
        float involved = Math.max(0, involvedPercent) / 100.0f;

        List<Integer> contributions = new ArrayList<>();
        OpinionBasis basis = OpinionBasis.NONE;
        int known = 0;

        for (IncidentRecord incident : record.incidents()) {
            if (!AwarenessResolver.knows(incident, villager, resident, gameTime,
                    minRumorDelayTicks, maxRumorDelayTicks)) {
                continue;
            }
            known++;
            // Subject first: being the villager it happened to outranks having merely seen it.
            OpinionBasis how = AwarenessResolver.isKnowingSubject(incident, villager)
                    ? OpinionBasis.INVOLVED
                    : incident.isWitness(villager) ? OpinionBasis.WITNESSED : OpinionBasis.HEARSAY;
            basis = strongest(basis, how);
            if (!incident.contributes()) {
                // Known, but worth nothing now: it still explains how they know you, not what they
                // think of you.
                continue;
            }
            float weight = switch (how) {
                case INVOLVED -> involved;
                case WITNESSED -> 1.0f;
                case HEARSAY, NONE -> hearsay;
            };
            contributions.add(ReputationMath.scaleTowardZero(incident.currentContribution(), weight));
        }

        // The baseline is village-wide sentiment, not a memory: it reaches a resident as hearsay and
        // does not follow a villager who has left.
        int baseline = resident ? ReputationMath.scaleTowardZero(record.baseline(), hearsay) : 0;
        if (baseline != 0) {
            basis = strongest(basis, OpinionBasis.HEARSAY);
        }
        if (basis == OpinionBasis.NONE) {
            return Opinion.NOTHING;
        }
        return new Opinion(ReputationMath.totalScore(baseline, contributions, minScore, maxScore),
                basis, known);
    }

    // ------------------------------------------------------------------
    // The facet-aware fold (§13.2)
    // ------------------------------------------------------------------

    /**
     * One villager's view with §13.2's facet term folded in, reported in its parts.
     *
     * @param base            the existing knowledge-filtered standing opinion, unchanged
     * @param facetAdjustment the capped facet term actually applied
     * @param score           {@code base + facetAdjustment}, clamped to the score window
     * @param traitBasis      how the interpretation weights were arrived at
     * @param aggregate       the knowledge-filtered profile the term was read from, so a caller that
     *                        needs both does not fold the ledger twice
     */
    public record ProfiledOpinion(Opinion base, int facetAdjustment, int score,
                                  VillagerProfileSnapshot.TraitBasis traitBasis,
                                  ProfileAggregator.Aggregate aggregate) {

        public OpinionBasis basis() {
            return base.basis();
        }

        public int knownIncidents() {
            return base.knownIncidents();
        }
    }

    /**
     * The same fold, plus the facet interpretation this observer applies to what they know.
     *
     * <p><b>Filter first, then aggregate, then interpret.</b> Both halves read the same ledger
     * through the same {@link AwarenessResolver} decision, per incident, before anything is summed:
     * the profile half goes through {@link ProfileAggregator#speaker}, which §13.1 requires instead of
     * scaling the community vector by a hearsay coefficient. Interpretation happens last, on evidence
     * that already survived the filter, so a personality can never reveal a deed its owner never
     * learned.
     *
     * <p><b>Identical to {@link #resolve} whenever there is nothing new to say.</b> With no profile
     * evidence, no published content, or {@code facetOpinionEnabled} off, the adjustment is zero and
     * {@code score} equals the base score exactly — the 0.4.x answer, to the integer.
     *
     * <p>The ledger must already have been reconciled through {@link ReconciliationService}; like
     * {@link ProfileAggregator}, this moves no clock and ages nothing.
     */
    public static ProfiledOpinion resolveProfiled(@Nullable ReputationPolicy policy,
                                                  CommunityReputationRecord record,
                                                  @Nullable ProfileRegistryBundle bundle, UUID villager,
                                                  boolean resident, long gameTime,
                                                  @Nullable VillagerProfileResolver.ObserverTraits traits) {
        ReputationPolicy rules = policy == null ? ReputationPolicy.defaults() : policy;
        Opinion base = resolve(record, villager, resident, gameTime, rules.minRumorDelayTicks(),
                rules.maxRumorDelayTicks(), rules.opinionHearsayPercent(),
                rules.opinionInvolvedPercent(), rules.minimumScore(), rules.maximumScore());
        ProfileRegistryBundle content = bundle == null ? ProfileRegistryBundle.EMPTY : bundle;
        ProfileAggregator.Aggregate aggregate = ProfileAggregator.speaker(rules, record, content,
                villager, resident, gameTime);
        VillagerProfileResolver.OpinionAdjustment adjustment = VillagerProfileResolver.facetAdjustment(
                content, aggregate.facets(), traits, rules.facetOpinionEnabled(),
                rules.maxFacetOpinionAdjustment());
        int score = ProfileMath.clamp(base.score() + adjustment.adjustment(), rules.minimumScore(),
                rules.maximumScore());
        return new ProfiledOpinion(base, adjustment.adjustment(), score, adjustment.traitBasis(),
                aggregate);
    }

    /**
     * §13.2's <b>final external contribution</b>: what a villager's view of a player is worth to an
     * outside Trust/Respect check, and the one place the two numbers in §13.2 are reconciled.
     *
     * <p>They are not the same quantity. {@code maxFacetOpinionAdjustment} (25 by default) bounds the
     * facet term in <em>opinion points</em>, on the same ladder as standing — it decides how far
     * facets may move this villager's opinion. The <b>±8</b> limit is in <em>check-bias units</em> and
     * bounds what leaves this mod: the resolved opinion picks a rung on the ladder, and that rung's
     * authored bias is the single term a consumer adds to its own check.
     *
     * <p>That is why nothing here sums two biases. The facet term is already inside {@code score},
     * so a larger facet contribution can only move the villager to a different rung; it can never add
     * a second bonus beside the rung's own (R04). The clamp is therefore an invariant restated rather
     * than a correction — {@link ReputationTier#biasFor} clamps identically — and it holds for the
     * maximal facet term as surely as for a zero one.
     */
    public static int externalCheckBias(@Nullable ReputationTierSet ladder, int opinionScore,
                                        String axis) {
        if (ladder == null) {
            return 0;
        }
        int bias = ladder.tierFor(opinionScore).biasFor(axis);
        return ProfileMath.clamp(bias, -ReputationTier.BIAS_SHIPPED_LIMIT,
                ReputationTier.BIAS_SHIPPED_LIMIT);
    }

    /** The stronger of two bases, in the declared order {@code INVOLVED > WITNESSED > HEARSAY > NONE}. */
    private static OpinionBasis strongest(OpinionBasis a, OpinionBasis b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}

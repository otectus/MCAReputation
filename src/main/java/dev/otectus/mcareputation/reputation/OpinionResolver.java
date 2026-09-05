package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.api.VillagerOpinion.OpinionBasis;
import dev.otectus.mcareputation.incident.AwarenessResolver;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.state.CommunityReputationRecord;

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

    /** The stronger of two bases, in the declared order {@code INVOLVED > WITNESSED > HEARSAY > NONE}. */
    private static OpinionBasis strongest(OpinionBasis a, OpinionBasis b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}

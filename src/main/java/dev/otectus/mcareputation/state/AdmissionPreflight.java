package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.reputation.ReputationPolicy;

/**
 * The inputs an admission decision is allowed to use, fixed at one evaluation point (§11.1 step 4).
 *
 * <p>Admission used to be asked as three loose scalars, read at three different moments from two
 * different sources: the cap from the live config, the clock from the context, the receipt horizon
 * from the policy snapshot — and the whole-player sweep that acts on the answer used the
 * <em>default</em> horizon rather than the configured one. Three readings of "the rules" cannot be
 * shown to agree, and when they disagree the preflight says a record is evictable and the sweep that
 * follows refuses to evict it, so the cap is exceeded with a warning nobody can act on.
 *
 * <p>Bundling them makes the guarantee checkable: one policy snapshot, one evaluation time, one
 * answer, used by the refusal, the eviction pass and the diagnostic alike.
 *
 * <p>Non-growing by contract. Every method that takes one of these answers a question about a record
 * that already exists; none of them may create a player, a community or a counter, and none of them
 * may fold a live contribution into a baseline to manufacture room. A full ledger of live history is
 * refused (§5 F09, I06), because folding preserves today's displayed number while silently changing
 * tomorrow's decay, the per-villager opinion, and the amends that were still available.
 *
 * @param evaluationTime             the single game time the whole operation is evaluated at
 * @param protectLiveProfileEvidence whether a record holding live profile subunits is unprunable
 *                                   (§12.3). Carried here rather than read where it is needed, for
 *                                   the same reason as the caps: the refusal, the eviction pass and
 *                                   the diagnostic must all answer from one snapshot of the rules.
 */
public record AdmissionPreflight(int maxIncidentsPerCommunity, int maxIncidentsPerPlayer,
                                 long receiptHorizonTicks, long evaluationTime,
                                 boolean protectLiveProfileEvidence) {

    /** Whether live profile evidence protects its record from pruning at this preflight's rules. */
    public boolean protectsLiveProfileEvidence() {
        return protectLiveProfileEvidence;
    }

    /** The preflight for one operation: the transaction's own policy snapshot and its one clock read. */
    public static AdmissionPreflight of(ReputationPolicy policy, long evaluationTime) {
        if (policy == null) {
            return new AdmissionPreflight(Integer.MAX_VALUE, Integer.MAX_VALUE,
                    ReputationPolicy.DEFAULT_RECEIPT_RETENTION_TICKS, evaluationTime,
                    ReputationPolicy.DEFAULT_PROTECT_PROFILE_EVIDENCE);
        }
        return new AdmissionPreflight(policy.maxIncidentsPerCommunity(), policy.maxIncidentsPerPlayer(),
                policy.receiptRetentionTicks(), evaluationTime, policy.protectProfileEvidence());
    }

    /** The same rules at a different per-community cap: what the whole-player sweep passes down. */
    public AdmissionPreflight withCommunityCap(int maxIncidents) {
        return new AdmissionPreflight(maxIncidents, maxIncidentsPerPlayer, receiptHorizonTicks,
                evaluationTime, protectLiveProfileEvidence);
    }

    /**
     * A preflight for a caller that only has loose values to hand — the capacity diagnostic and the
     * older pruning overloads. Kept explicit so it is obvious at the call site that the policy
     * snapshot was not used.
     */
    public static AdmissionPreflight ofLoose(int maxIncidentsPerCommunity, long evaluationTime,
                                             long receiptHorizonTicks) {
        // Profile evidence is protected here too: a caller with only loose values is exactly the one
        // that cannot have decided otherwise, and the conservative answer keeps the evidence.
        return new AdmissionPreflight(maxIncidentsPerCommunity, Integer.MAX_VALUE, receiptHorizonTicks,
                evaluationTime, ReputationPolicy.DEFAULT_PROTECT_PROFILE_EVIDENCE);
    }
}

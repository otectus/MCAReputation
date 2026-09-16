package dev.otectus.mcareputation.credit;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Turns a {@link CreditPolicy} plus the occurrence ordinals a tracker supplies into a
 * {@link CreditDecision} (spec §10.2, §10.4).
 *
 * <p>Pure and stateless. The tracker state itself — window watermarks, counters, the capacity
 * overflow flag — is persisted state and belongs to the canonical transaction, not here; this class
 * only decides what a given set of ordinals is worth. Splitting it that way is what lets the
 * schedule be tested exhaustively with no save file, and it keeps one rule in one place when the
 * transaction later reserves an allowance before mutating anything.
 *
 * <h2>The two ceilings</h2>
 *
 * <p>The group schedule is the allowance. The optional subject schedule is a <b>second ceiling on the
 * same allowance</b>, never a parallel one, so the effective percentage is the minimum of the two
 * (§10.2). This is precisely why helping a fresh villager cannot restore a spent group allowance:
 * rotating subjects resets the <em>ceiling</em>, not the <em>allowance</em>.
 */
public final class CreditResolver {

    private CreditResolver() {
    }

    /**
     * The decision for an operation.
     *
     * @param policy           the applicable policy, or empty when the profile authors none
     * @param commendable      whether §10.4 permits discounting this profile at all; a mixed or
     *                         adverse profile is never discounted
     * @param groupOrdinal     zero-based count of prior accepted qualifying operations in the live
     *                         window; occurrence 1 is {@code 0}
     * @param subjectOrdinal   the same for this subject, or empty when the operation carried no
     *                         usable subject identity and the policy defines a subject rule
     * @param capacityOverflow whether §10.5's tracker capacity is exhausted, in which case no new
     *                         positive credit is granted until the applicable window ends
     */
    public static CreditDecision resolve(Optional<CreditPolicy> policy, boolean commendable,
                                         int groupOrdinal, OptionalInt subjectOrdinal,
                                         boolean capacityOverflow) {
        if (policy == null || policy.isEmpty()) {
            return CreditDecision.unlimited(CreditDecision.Reason.NO_POLICY);
        }
        CreditPolicy applicable = policy.get();
        if (!commendable) {
            // §10.4: never apply a positive-credit rule to adverse or mixed effects. Reported with
            // its own reason so diagnostics can distinguish "no policy" from "policy not applicable".
            return new CreditDecision(Optional.of(applicable.group()),
                    applicable.subjectLimit().map(CreditPolicy.SubjectLimit::role),
                    Math.max(0, groupOrdinal), Math.max(0, subjectOrdinal.orElse(0)),
                    CreditPolicy.FULL_BP, CreditPolicy.FULL_BP, CreditPolicy.FULL_BP,
                    CreditDecision.Reason.NOT_COMMENDABLE);
        }

        ResourceLocation group = applicable.group();
        int normalizedGroupOrdinal = Math.max(0, groupOrdinal);
        int groupBp = applicable.bpForOrdinal(normalizedGroupOrdinal);

        Optional<CreditPolicy.SubjectLimit> limit = applicable.subjectLimit();
        int subjectBp = CreditPolicy.FULL_BP;
        int normalizedSubjectOrdinal = 0;
        boolean subjectMissing = false;
        if (limit.isPresent()) {
            if (subjectOrdinal != null && subjectOrdinal.isPresent()) {
                normalizedSubjectOrdinal = Math.max(0, subjectOrdinal.getAsInt());
                subjectBp = limit.get().bpForOrdinal(normalizedSubjectOrdinal);
            } else {
                // §10.2: missing subject data takes the conservative shared bucket, which is the
                // schedule's tail. It never opens a fresh allowance on each call.
                subjectMissing = true;
                subjectBp = limit.get().tailBp();
            }
        }

        int effectiveBp = Math.min(groupBp, subjectBp);
        CreditDecision.Reason reason;
        if (capacityOverflow) {
            effectiveBp = 0;
            reason = CreditDecision.Reason.CAPACITY_OVERFLOW;
        } else if (subjectMissing && subjectBp <= groupBp) {
            reason = CreditDecision.Reason.SUBJECT_MISSING;
        } else if (effectiveBp == CreditPolicy.FULL_BP) {
            reason = CreditDecision.Reason.FULL_CREDIT;
        } else if (subjectBp < groupBp) {
            reason = CreditDecision.Reason.SUBJECT_CEILING;
        } else {
            reason = CreditDecision.Reason.GROUP_SCHEDULE;
        }

        return new CreditDecision(Optional.of(group), limit.map(CreditPolicy.SubjectLimit::role),
                normalizedGroupOrdinal, normalizedSubjectOrdinal, groupBp, subjectBp, effectiveBp,
                reason);
    }

    /** Convenience for a policy-free profile: full credit, nothing tracked. */
    public static CreditDecision unlimited() {
        return CreditDecision.unlimited(CreditDecision.Reason.NO_POLICY);
    }
}

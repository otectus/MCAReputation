package dev.otectus.mcareputation.credit;

import dev.otectus.mcareputation.profile.ProfileMath;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Optional;

/**
 * The repeat-credit answer for one staged operation: the effective percentage, the ordinals it came
 * from, and the reason it is not full credit (spec §9.4 "credit decision", §10.4).
 *
 * <p>Immutable and explainable on purpose. §9.4 stores this on the accepted deed so a later
 * explanation can say <em>why</em> a fifth rescue was worth nothing, and §21.1's diagnostics read the
 * same record rather than recomputing it against a policy that may since have been edited.
 *
 * <p>{@link #applyTo(long)} is where invariant I07 lives structurally: a negative contribution is
 * returned unchanged no matter what the schedule says. Wrongdoing does not get cheaper through
 * repetition, and that cannot be undone by an authoring mistake or a future caller.
 */
public record CreditDecision(
        Optional<ResourceLocation> group,
        Optional<String> subjectRole,
        int groupOrdinal,
        int subjectOrdinal,
        int groupBp,
        int subjectBp,
        int effectiveBp,
        Reason reason) {

    /** Why the effective percentage is what it is. */
    public enum Reason {
        /** No policy applies to this profile: full credit, and nothing is tracked. */
        NO_POLICY,
        /** A policy applies and the schedule is still at full credit for this occurrence. */
        FULL_CREDIT,
        /** The group schedule reduced this occurrence. */
        GROUP_SCHEDULE,
        /** The subject ceiling was the binding limit (§10.2). */
        SUBJECT_CEILING,
        /**
         * A subject rule applies but the operation carried no usable subject identity, so the
         * conservative shared/missing-subject bucket applied (§10.2). Never a fresh allowance.
         */
        SUBJECT_MISSING,
        /**
         * Tracker capacity was exhausted, so §10.5's persisted conservative overflow decision
         * applied: no new positive credit until the applicable window ends. Adverse effects are
         * unaffected because {@link #applyTo(long)} never scales them.
         */
        CAPACITY_OVERFLOW,
        /**
         * The profile is not classified as repeatable-and-commendable, so §10.4 forbids discounting
         * it at all. Reported rather than silently treated as full credit.
         */
        NOT_COMMENDABLE,
        /**
         * The deed predates repeat-credit accounting altogether: the §19.2 migration awards historical
         * credit at 100% because the old system stored no decision to honour, and §19.2 forbids
         * inventing how much credit a player would have received under a policy that did not exist.
         * Distinct from {@link #NO_POLICY} so a diagnostic can tell "nothing limits this" from
         * "nothing was recorded when this happened".
         */
        LEGACY_FULL_CREDIT,
        /**
         * Repeat credit is switched off by an operator, so this award took its whole authored value
         * (§20). The bounded window accounting still advanced: disabling the discount is an explicit
         * bypass of the reduction, not permission to delete the anti-grind state that would apply
         * again the moment it is switched back on.
         */
        CREDIT_DISABLED;

        public String jsonName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public CreditDecision {
        group = group == null ? Optional.<ResourceLocation>empty() : group;
        subjectRole = subjectRole == null ? Optional.<String>empty() : subjectRole;
        groupBp = ProfileMath.clamp(groupBp, 0, CreditPolicy.FULL_BP);
        subjectBp = ProfileMath.clamp(subjectBp, 0, CreditPolicy.FULL_BP);
        effectiveBp = ProfileMath.clamp(effectiveBp, 0, CreditPolicy.FULL_BP);
    }

    /** Full credit with nothing tracked: the answer for a profile with no credit policy. */
    public static CreditDecision unlimited(Reason reason) {
        return new CreditDecision(Optional.empty(), Optional.empty(), 0, 0,
                CreditPolicy.FULL_BP, CreditPolicy.FULL_BP, CreditPolicy.FULL_BP, reason);
    }

    /** Whether the operation keeps its whole authored significance. */
    public boolean isFullCredit() {
        return effectiveBp == CreditPolicy.FULL_BP;
    }

    /**
     * Whether this operation earns no new positive evidence at all. §10.4: such a deed still
     * <em>occurred</em> and may remain a zero-contribution ledger entry, but it must not gain a
     * dominant-trait evidence count or a recognition bonus.
     */
    public boolean isSuppressed() {
        return effectiveBp == 0;
    }

    /**
     * The credited subunits for an authored contribution.
     *
     * <p>A non-positive contribution is returned unchanged (I07, §10.4): repeat credit discounts
     * rewards, never accountability. A positive contribution is scaled once, in subunits, so a
     * 25%-credited half point survives as evidence instead of truncating to zero (§8.3).
     */
    public long applyTo(long authoredSubunits) {
        if (authoredSubunits <= 0L) {
            return authoredSubunits;
        }
        return ProfileMath.scaleByBasisPoints(authoredSubunits, effectiveBp);
    }
}

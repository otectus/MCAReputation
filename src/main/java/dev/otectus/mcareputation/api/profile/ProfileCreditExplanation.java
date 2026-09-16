package dev.otectus.mcareputation.api.profile;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * Why an accepted deed's profile evidence was worth what it was worth (§10, §14.2's "accepted credit
 * explanation").
 *
 * <p>The public mirror of the frozen internal credit decision, so a producer can tell an operator
 * "the fifth delivery this week counted for a quarter" without reaching into this mod's internals.
 * The numbers are the ones frozen onto the deed at acceptance; a later policy edit never rewrites
 * them (§9.4).
 *
 * <p>§10.4's rule is visible in the arithmetic rather than stated here: repetition discounts rewards
 * and never accountability, so an adverse contribution is never scaled whatever this explanation
 * says about the occurrence count.
 *
 * @param group              the credit group that applied, when one did
 * @param subjectRole        the subject role a per-subject ceiling keyed on
 * @param groupOccurrence    which occurrence in the group's window this was, from zero
 * @param subjectOccurrence  which occurrence for that subject, from zero
 * @param effectiveBasisPoints the credit actually applied, in basis points of the authored value
 * @param reason             which rule produced it
 * @since MCA: Reputation 0.6.0
 */
public record ProfileCreditExplanation(Optional<ResourceLocation> group, Optional<String> subjectRole,
                                       int groupOccurrence, int subjectOccurrence,
                                       int effectiveBasisPoints, Reason reason) {

    /** One hundred percent, in basis points, so a consumer needs no constant of its own. */
    public static final int FULL_BASIS_POINTS = 10_000;

    public ProfileCreditExplanation {
        group = group == null ? Optional.empty() : group;
        subjectRole = subjectRole == null ? Optional.empty() : subjectRole;
        groupOccurrence = Math.max(0, groupOccurrence);
        subjectOccurrence = Math.max(0, subjectOccurrence);
        effectiveBasisPoints = Math.max(0, Math.min(FULL_BASIS_POINTS, effectiveBasisPoints));
        reason = reason == null ? Reason.NO_POLICY : reason;
    }

    /** The public mirror of the internal credit reasons; one value per rule, never a catch-all. */
    public enum Reason {

        /** No policy applies to this profile: full credit, nothing tracked. */
        NO_POLICY,

        /** A policy applies and the schedule is still at full credit for this occurrence. */
        FULL_CREDIT,

        /** The group schedule reduced this occurrence. */
        GROUP_SCHEDULE,

        /** A per-subject ceiling was the binding limit. */
        SUBJECT_CEILING,

        /** A subject rule applies but the operation named no usable subject; the shared bucket applied. */
        SUBJECT_MISSING,

        /** Tracker capacity was exhausted, so the conservative overflow decision applied. */
        CAPACITY_OVERFLOW,

        /** The profile is not repeatable-and-commendable, so no discount was permitted at all. */
        NOT_COMMENDABLE,

        /** The deed predates repeat-credit accounting; migration awarded historical credit in full. */
        LEGACY_FULL_CREDIT,

        /** An operator switched the reduction off. The window accounting still advanced. */
        CREDIT_DISABLED
    }

    /** Whether the deed kept its whole authored significance. */
    public boolean fullCredit() {
        return effectiveBasisPoints == FULL_BASIS_POINTS;
    }

    /** Whether the deed earned no new positive evidence at all. */
    public boolean suppressed() {
        return effectiveBasisPoints == 0;
    }
}

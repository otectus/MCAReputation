package dev.otectus.mcareputation.api.profile;

import dev.otectus.mcareputation.api.DeliveryOutcome;
import dev.otectus.mcareputation.api.ReceiptOutcome;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * What one {@link ProfiledDelivery} did: the ordinary delivery outcome, plus what became of the
 * profile half (§14.2's "detailed outcome").
 *
 * <p>The two halves are reported separately because they fail independently and a producer must not
 * confuse them. A deed can be recorded and move standing while profiles are switched off — the
 * delivery succeeded, {@link #profileAvailability} says {@code DISABLED}, and §16.3's rule applies:
 * a social delivery failure never undoes a legal settlement, and a profile-only change never pays a
 * bounty again.
 *
 * @param outcome              the canonical delivery outcome, receipt and all
 * @param profileAvailability  whether profile evidence could be recorded for this deed
 * @param appliedProfile       the authored profile the deed was actually judged by, when one was
 * @param credit               the frozen repeat-credit explanation, when a profile was applied
 * @param profileEvidenceRecorded whether live profile evidence is now attached to the incident
 * @since MCA: Reputation 0.6.0
 */
public record ProfiledDeliveryResult(DeliveryOutcome outcome, ProfileAvailability profileAvailability,
                                     Optional<ResourceLocation> appliedProfile,
                                     Optional<ProfileCreditExplanation> credit,
                                     boolean profileEvidenceRecorded) {

    public ProfiledDeliveryResult {
        profileAvailability = profileAvailability == null
                ? ProfileAvailability.UNSUPPORTED
                : profileAvailability;
        appliedProfile = appliedProfile == null ? Optional.empty() : appliedProfile;
        credit = credit == null ? Optional.empty() : credit;
    }

    /** A delivery that produced no profile evidence, for the stated reason. */
    public static ProfiledDeliveryResult withoutProfile(DeliveryOutcome outcome,
                                                        ProfileAvailability availability) {
        return new ProfiledDeliveryResult(outcome, availability, Optional.empty(), Optional.empty(),
                false);
    }

    /** True when the deed was recorded and moved standing. */
    public boolean applied() {
        return outcome != null && outcome.outcome() == ReceiptOutcome.APPLIED;
    }

    /** True when a retry under the same operation key could produce a different answer. */
    public boolean retryable() {
        return outcome != null && outcome.retryable();
    }
}

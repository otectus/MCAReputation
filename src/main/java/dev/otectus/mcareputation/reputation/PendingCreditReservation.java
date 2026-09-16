package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.credit.CreditDecision;
import dev.otectus.mcareputation.state.CreditWindowTrackers;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * The repeat-credit allowance a staged operation will consume, decided while nothing has been written
 * yet.
 *
 * <p>P1 reserved this slot empty for the reason that still governs it: a counter incremented before
 * the admission decision is a counter a refused delivery has already spent, and one incremented after
 * publication is one a synchronous listener can observe as unspent. Both turn a retry into free
 * credit. So the decision is computed from a <em>peek</em> at the trackers, carried here, and the
 * counters move only inside the canonical mutation that accepted the deed.
 *
 * <p>The reservation carries the window's duration as well as its identity because §10.5 freezes an
 * active window's duration until it expires: the value staged here is the one the commit hands to the
 * tracker, so a config or datapack reload in between cannot lengthen or shorten a window already in
 * progress.
 */
sealed interface PendingCreditReservation {

    /** No allowance was reserved: no policy applies, or nothing about this deed is discountable. */
    record Unreserved() implements PendingCreditReservation {
    }

    /**
     * An allowance reserved against one group, and optionally one subject inside it.
     *
     * @param group        the accounting key the tracker is stored under
     * @param windowTicks  the duration a <em>new</em> window would take, frozen at staging time
     * @param subjectKey   the subject identity the policy's role resolved to, when it resolved to one
     * @param subjectRequired whether the policy defines a subject ceiling at all; a policy that does,
     *                        with no usable subject, takes §10.2's conservative shared bucket rather
     *                        than a fresh allowance
     * @param peeked       the window the staging read, kept so the commit can prove the counters it
     *                     moved were the ones the decision was made against
     * @param decision     what the peeked ordinals were worth, which is what the deed freezes
     */
    record Reserved(ResourceLocation group, long windowTicks, Optional<String> subjectKey,
                    boolean subjectRequired, CreditWindowTrackers.CreditWindow peeked,
                    CreditDecision decision)
            implements PendingCreditReservation {
    }
}

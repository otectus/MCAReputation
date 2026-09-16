package dev.otectus.mcareputation.reputation;

/**
 * The slot a staged operation reserves for the repeat-credit decision a later phase calculates.
 *
 * <p>P1 never produces one, for the same reason {@link PendingProfilePayload} is empty: a credit
 * allowance must be <em>reserved</em> while the operation can still be refused and consumed only by
 * the canonical mutation that accepted the deed. A counter incremented before the admission decision
 * is a counter a refused delivery has already spent, and a counter incremented after publication is
 * one a synchronous listener can observe as unspent — both of which turn a retry into free credit.
 *
 * <p>Sealed and payload-free on purpose: a reserved seam, not a guess at a credit schedule.
 */
sealed interface PendingCreditReservation {

    /** The only shape this version can produce: no allowance was reserved for the operation. */
    record Unreserved() implements PendingCreditReservation {
    }
}

package dev.otectus.mcareputation.reputation;

/**
 * The slot a staged operation reserves for the incident profile evidence a later phase attaches.
 *
 * <p>P1 never produces one. It exists now, empty, because the thing being fixed is the <em>shape</em>
 * of the transaction: recognition and facet evidence must be computed while nothing has been written,
 * staged alongside the pending incident, committed in the same canonical mutation, and rolled back as
 * one unit. Adding the slot after the ordering was already in place is how a profile payload ends up
 * appended after publication — which is the exact defect §3.2 records against the receipt append.
 *
 * <p>Sealed and payload-free on purpose: this is a reserved seam, not a guess at profile semantics.
 * The phase that fills it replaces {@link Unstaged} with the real evidence record and every staging,
 * commit and rollback site already has a place to put it.
 */
sealed interface PendingProfilePayload {

    /** The only shape this version can produce: no profile evidence was staged for the operation. */
    record Unstaged() implements PendingProfilePayload {
    }
}

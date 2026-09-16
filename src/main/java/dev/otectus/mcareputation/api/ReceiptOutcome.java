package dev.otectus.mcareputation.api;

/**
 * What one keyed delivery did, in the producer's terms (§5 F03, §6 "Incident delivery").
 *
 * <p>The distinction that matters to a companion is not success versus failure but <b>terminal</b>
 * versus <b>retryable</b>. A terminal outcome is remembered: replaying the same operation key returns
 * the same answer forever, so a producer that crashed before storing our reply can recover it. A
 * retryable outcome is deliberately forgotten, because the condition that caused it — the mod being
 * switched off, a ledger with nothing left to evict — is one an operator can fix, and the delivery
 * should then succeed.
 *
 * @since MCA: Reputation 0.4.1
 */
public enum ReceiptOutcome {

    /** The deed was recorded and moved public standing. Terminal. */
    APPLIED,

    /**
     * This operation key has been delivered before; the stored outcome is being replayed. Never
     * stored itself — the receipt on disk keeps whatever the first delivery produced.
     */
    DUPLICATE,

    /**
     * Accepted, but nothing public came of it: an unwitnessed deed that was dropped, or one retained
     * as private, zero-contribution history. Terminal, and carries the incident id when a record was
     * in fact retained.
     */
    ACCEPTED_NO_PUBLIC_INCIDENT,

    /** Reputation is switched off. Retryable: nothing is stored, so a retry after re-enabling works. */
    REFUSED_DISABLED,

    /** The delivery was malformed or named an unknown incident type. Terminal: a retry cannot help. */
    REFUSED_INVALID,

    /**
     * The ledger is full of history that may not be evicted (§5 F09, D5). Retryable: nothing was
     * written, and pruning or unpinning makes room.
     */
    REFUSED_CAPACITY;

    /** Whether a receipt for this outcome is persisted, and therefore replayable forever. */
    public boolean isTerminal() {
        return this == APPLIED || this == ACCEPTED_NO_PUBLIC_INCIDENT || this == REFUSED_INVALID;
    }
}

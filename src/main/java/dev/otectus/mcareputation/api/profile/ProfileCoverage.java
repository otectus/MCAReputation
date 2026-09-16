package dev.otectus.mcareputation.api.profile;

/**
 * How complete the profile history behind an answer is (§14.2, §19.3).
 *
 * <p>Deliberately separate from {@link ProfileAvailability}: a profile can be perfectly available and
 * still be built on a save that predates profiles, and an authored gate that means "no adverse
 * evidence is known" must be able to tell those apart. "Available, zero, complete" is a stranger with
 * a clean record; "available, zero, partial legacy" is a save that cannot prove it.
 *
 * @since MCA: Reputation 0.6.0
 */
public enum ProfileCoverage {

    /** Every retained deed in this store was accepted with profile accounting in place. */
    COMPLETE_SINCE_RECORD_START,

    /**
     * The store holds pre-profile history, deeds that could not be enriched, or a quarantined
     * payload. §19.2's enrichment is conservative by design, so a finished pass is still not a
     * complete history.
     */
    PARTIAL_LEGACY,

    /** The budgeted migration pass still owes this save work. */
    MIGRATING;

    /** Whether an absence-of-evidence gate may be answered from this coverage. */
    public boolean complete() {
        return this == COMPLETE_SINCE_RECORD_START;
    }
}

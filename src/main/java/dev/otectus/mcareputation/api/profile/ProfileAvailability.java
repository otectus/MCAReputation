package dev.otectus.mcareputation.api.profile;

/**
 * Why a public-profile answer is or is not usable (§14.2).
 *
 * <p>The same reason {@code OpinionResult.OpinionAvailability} exists: a profile that is genuinely
 * empty is {@link #AVAILABLE} with zero recognition and no facets, and a caller must <b>not</b>
 * answer that with a community-wide guess or an authored "notorious stranger" fallback. Only the
 * unavailable values license a fallback, and each of them names a different operator-visible cause.
 *
 * @since MCA: Reputation 0.6.0
 */
public enum ProfileAvailability {

    /** A real answer, even when every value in it is zero. */
    AVAILABLE,

    /** Profiles, or the mod, are switched off. The stored evidence is untouched. */
    DISABLED,

    /** This build cannot answer the question at all, or no profile content is published. */
    UNSUPPORTED,

    /** The player, community, villager or server could not be resolved; nothing is invented. */
    UNRESOLVED,

    /** The saved data was written by a newer format, so this store may be read but not written. */
    READ_ONLY,

    /** §19.3's budgeted migration is still running; the history is not yet complete. */
    MIGRATING,

    /**
     * The question depends on complete history and this save's is not complete (§14.5).
     *
     * <p>Not one of the names §14.2 lists, and here because the two other candidates would both be
     * dishonest: a coverage-sensitive gate refused on a {@link ProfileCoverage#PARTIAL_LEGACY} save is
     * neither {@code MIGRATING} (nothing is running) nor {@code UNSUPPORTED} (the feature works). The
     * snapshot itself stays {@code AVAILABLE} and carries its coverage, so "available, zero,
     * complete" and "available, zero, partial legacy" remain distinguishable; only a predicate that
     * relies on an upper bound or on the absence of adverse evidence refuses, and it refuses
     * distinguishably so the authored fallback can run.
     */
    INCOMPLETE_HISTORY,

    /** A contained internal failure. Nothing was written and nothing may be inferred. */
    ERROR;

    /** Whether a real, evaluated profile backs this answer. */
    public boolean isAvailable() {
        return this == AVAILABLE;
    }
}

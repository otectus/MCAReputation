package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.profile.IncidentProfileEvidence;

/**
 * The incident profile evidence a staged operation will attach, computed while nothing has been
 * written yet.
 *
 * <p>P1 reserved this slot empty and said why: recognition and facet evidence must be calculated
 * before the operation can still be refused, staged alongside the pending incident, committed in the
 * same canonical mutation, and rolled back as one unit. Filling it afterwards is how a profile payload
 * ends up appended after publication — the exact defect §3.2 records against the receipt append.
 *
 * <p>Sealed with two shapes, which is the whole vocabulary: either this operation carries frozen
 * evidence or it does not. There is deliberately no "partially computed" state — a payload that could
 * not be computed in full is not staged at all, so the commit never has to decide what half a payload
 * means (§9.6's rule against a half-published reference-dependent profile, applied to one deed).
 */
sealed interface PendingProfilePayload {

    /** No profile evidence was staged: the definition names no profile, or the deed is private. */
    record Unstaged() implements PendingProfilePayload {
    }

    /**
     * The frozen §9.4 payload this operation will attach to its incident.
     *
     * @param evidence the whole payload, immutable, ready to be assigned in one reference
     */
    record Staged(IncidentProfileEvidence evidence) implements PendingProfilePayload {
    }
}

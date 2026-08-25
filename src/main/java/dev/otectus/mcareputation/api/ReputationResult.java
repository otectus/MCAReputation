package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.community.CommunityKey;

import java.util.Optional;
import java.util.UUID;

/**
 * What one {@link ReputationRequest} actually did (spec §18).
 *
 * <p>{@link #applied} is the important field and it does not mean "succeeded". A request that was
 * correctly refused — because its dedupe key had already been used, because the mod is disabled,
 * because an unwitnessed assault produced no public consequence — returns {@code applied = false}
 * with the current state intact and no exception. Callers should treat that as normal: it is the same
 * answer they would get from asking twice, which is precisely the point.
 *
 * <p>{@link #appliedDelta} is what the score actually moved, not what was requested. At the clamp
 * these differ, and the player-facing feedback must report the real number (§28.3).
 */
public record ReputationResult(
        boolean applied,
        Optional<UUID> incidentId,
        CommunityKey community,
        int oldScore,
        int newScore,
        int appliedDelta,
        String oldTierId,
        String newTierId,
        boolean tierChanged,
        boolean newHighWater,
        Reason reason) {

    /** Why a request did not apply. {@link #APPLIED} is the success case. */
    public enum Reason {
        APPLIED,
        /** Already recorded under the same dedupe key; the earlier outcome is returned unchanged. */
        DUPLICATE,
        /** {@code enableReputation=false}, or the relevant subsystem is switched off. */
        DISABLED,
        /** No community resolved, so there is no public to have an opinion (§12.2). */
        NO_COMMUNITY,
        /** The incident type is not defined by any loaded datapack. */
        UNKNOWN_INCIDENT,
        /** A witnessed incident nobody saw: no public consequence (§19.1, §33 rule 11). */
        UNWITNESSED,
        /** The request failed validation; nothing was written. */
        INVALID,
        /** An unexpected failure was contained; nothing was written. */
        ERROR
    }

    public static ReputationResult applied(UUID incidentId, CommunityKey community, int oldScore, int newScore,
                                           int appliedDelta, String oldTierId, String newTierId,
                                           boolean newHighWater) {
        return new ReputationResult(true, Optional.of(incidentId), community, oldScore, newScore, appliedDelta,
                oldTierId, newTierId, !java.util.Objects.equals(oldTierId, newTierId), newHighWater,
                Reason.APPLIED);
    }

    /** A no-op outcome that still reports the community's real current standing. */
    public static ReputationResult notApplied(Reason reason, CommunityKey community, int score, String tierId) {
        return new ReputationResult(false, Optional.empty(), community, score, score, 0,
                tierId, tierId, false, false, reason);
    }

    /**
     * A {@link Reason#DUPLICATE} outcome that names the incident the dedupe key already produced.
     *
     * <p>This is what makes a cross-mod write recoverable. A companion commits its own record, then
     * crashes before it can store the incident id we returned; on restart it replays the same dedupe
     * key. Without the id here it has no way to learn what the first attempt created, so it either
     * loses the link forever or records a second incident to get one. Neither is acceptable, and the
     * service already has the record in hand at the dedupe branch — it simply used to discard it.
     *
     * <p>Nothing was written, so {@code applied} is false and the delta is zero, exactly as with any
     * other refusal. Only the identity is recovered.
     */
    public static ReputationResult duplicate(UUID existingIncidentId, CommunityKey community,
                                             int score, String tierId) {
        return new ReputationResult(false, Optional.ofNullable(existingIncidentId), community, score, score, 0,
                tierId, tierId, false, false, Reason.DUPLICATE);
    }

    /** A no-op outcome for a request that never got far enough to resolve a score. */
    public static ReputationResult rejected(Reason reason, CommunityKey community) {
        return new ReputationResult(false, Optional.empty(), community, 0, 0, 0, "", "", false, false, reason);
    }

    /** True when the standing number moved, as opposed to a zero-delta narrative record being stored. */
    public boolean scoreChanged() {
        return appliedDelta != 0;
    }

    /** True when this deed is worth telling the player about at all (§28.3). */
    public boolean worthNotifying() {
        return applied && (scoreChanged() || tierChanged);
    }
}

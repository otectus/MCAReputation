package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.incident.IncidentStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * The outcome of resolving an incident (spec §25). Like {@link ReputationResult}, a refusal is a
 * normal answer rather than an error: re-running a repeatable restitution quest against an incident
 * that is already atoned returns {@link Reason#NOT_STRONGER} and changes nothing, which is exactly
 * what stops it from paying out twice (§33 rule 14).
 */
public record ResolutionResult(
        boolean applied,
        Optional<UUID> incidentId,
        IncidentStatus oldStatus,
        IncidentStatus newStatus,
        int oldContribution,
        int newContribution,
        int oldScore,
        int newScore,
        Reason reason) {

    public enum Reason {
        APPLIED,
        /** No incident matched the selector or the given id. */
        NOT_FOUND,
        /** The requested status is not stronger than the current one, or the incident is DISPROVEN. */
        NOT_STRONGER,
        /** The mod or the relevant subsystem is disabled. */
        DISABLED,
        /** The request failed validation; nothing was written. */
        INVALID,
        /** An unexpected failure was contained; nothing was written. */
        ERROR
    }

    public static ResolutionResult applied(UUID incidentId, IncidentStatus oldStatus, IncidentStatus newStatus,
                                           int oldContribution, int newContribution, int oldScore, int newScore) {
        return new ResolutionResult(true, Optional.of(incidentId), oldStatus, newStatus,
                oldContribution, newContribution, oldScore, newScore, Reason.APPLIED);
    }

    public static ResolutionResult notApplied(Reason reason) {
        return new ResolutionResult(false, Optional.empty(), IncidentStatus.ACTIVE, IncidentStatus.ACTIVE,
                0, 0, 0, 0, reason);
    }

    public int scoreDelta() {
        return newScore - oldScore;
    }
}

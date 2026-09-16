package dev.otectus.mcareputation.api;

import java.util.UUID;

/**
 * The terms on which one deed may absorb an earlier one (§6 "Superseding a precursor").
 *
 * <p>Passed to {@link McaReputationApi#recordSuperseding}, which is the single public-accounting seam
 * for a fatal encounter: the native assault → killing upgrade and a companion's own crime pipeline
 * both go through it, so the pair always totals the successor's figure rather than stacking.
 *
 * @param precursorIncidentId the incident the successor claims to replace
 * @param maxWindowTicks      how far back, by occurrence time, the precursor may lie
 * @param requireSharedSubject whether the two deeds must name at least one subject in common
 * @since MCA: Reputation 0.4.1
 */
public record SupersedeSpec(UUID precursorIncidentId, long maxWindowTicks, boolean requireSharedSubject) {

    public static SupersedeSpec of(UUID precursorIncidentId, long maxWindowTicks,
                                   boolean requireSharedSubject) {
        return new SupersedeSpec(precursorIncidentId, maxWindowTicks, requireSharedSubject);
    }
}

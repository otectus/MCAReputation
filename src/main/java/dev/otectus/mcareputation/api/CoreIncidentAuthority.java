package dev.otectus.mcareputation.api;

import net.minecraft.resources.ResourceLocation;

/**
 * A claim by another mod to be the sole producer of one or more {@link CoreIncidentKind} deeds
 * (integration spec §7.1).
 *
 * <p>MCA: Reputation detects villager assault and death itself. So does MCA: Crime. Installing both
 * naively means one swing costs the player two deeds. Rather than have Reputation check
 * {@code ModList.isLoaded("mcacrime")} — which cannot tell a working integration from a broken one,
 * and would hard-code a companion's name into a mod that must not know about it — the companion
 * registers an authority, and Reputation steps aside only for the kinds a <em>healthy</em> authority
 * actually claims.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>{@link #owns} is called on the server thread, once per candidate gameplay event, inside a
 *       guarded boundary. It must be cheap: no world scans, no I/O, no allocation storms.</li>
 *   <li>It must be <b>honest and current</b>. Return {@code false} the moment your own detector or
 *       your Reputation bridge is disabled, unhealthy, or version-incompatible — that is the whole
 *       point. Reputation resumes its native detector on the next event.</li>
 *   <li>A throw is treated as {@code false} (fail-safe: the deed still gets recorded by somebody)
 *       and is rate-limited in the log.</li>
 *   <li>{@link #authorityId} must be stable and unique — conventionally
 *       {@code <yourmodid>:<detector name>}. A duplicate id is rejected at registration.</li>
 * </ul>
 *
 * <p>If two distinct authorities claim the same kind, Reputation logs an error and keeps its own
 * detector running. Ambiguity fails toward a deed being recorded once by Reputation, never toward a
 * deed being recorded twice or lost entirely.
 */
public interface CoreIncidentAuthority {

    /** A stable, unique id for this authority. Conventionally {@code <modid>:<detector>}. */
    ResourceLocation authorityId();

    /**
     * Whether this authority is, right now, the producer for {@code kind}.
     *
     * <p>Answer {@code false} unless your detector is enabled, your integration handshake succeeded,
     * and you will genuinely record the deed. Claiming a kind you then drop creates an incident
     * black hole that no log line will explain.
     */
    boolean owns(CoreIncidentKind kind);
}

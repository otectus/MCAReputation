package dev.otectus.mcareputation.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Supplies pre-Reputation standing for one player, so it can be imported once (spec §32.2).
 *
 * <p>The direction of this dependency is the point. MCA: Reputation has no compile-time dependency on
 * MCA: Quests (§9.2) and therefore cannot read {@code ProjectSavedData} itself. Quests, which can,
 * registers a provider; Reputation asks it at the right moment and applies whatever comes back through
 * the one idempotent import path. Neither mod needs to know when the other loads.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Called on the server thread, at player login and from
 *       {@code /mcareputation migrate run}.</li>
 *   <li><b>Eligibility is the provider's judgement.</b> §32.2 lists the criteria — the player has
 *       quest history, an active quest, progression stats, or a title; or is the singleplayer owner;
 *       or an administrator asked explicitly. The provider knows those facts; Reputation does not.
 *       Return {@link Optional#empty()} for an ineligible player and they simply start at zero.</li>
 *   <li>May be called more than once. The import itself is guarded by a migration marker, so a
 *       provider that returns the same data twice cannot double-apply it — but a provider should
 *       still be cheap, since it is consulted on every login until the marker exists.</li>
 *   <li>May throw. Failures are caught and logged; migration is skipped for that login, and the
 *       player stays eligible to retry.</li>
 * </ul>
 */
@FunctionalInterface
public interface LegacyImportProvider {

    /**
     * The legacy standing to import for this player, or empty when there is none or they are not
     * eligible.
     *
     * @param force set by {@code /mcareputation migrate run} to bypass the provider's own eligibility
     *              heuristics — the administrator has taken responsibility for the decision
     */
    Optional<LegacyImportRequest> buildRequest(MinecraftServer server, ServerPlayer player, boolean force);

    /** Identifies this provider in log output. */
    default String providerName() {
        return getClass().getSimpleName();
    }
}

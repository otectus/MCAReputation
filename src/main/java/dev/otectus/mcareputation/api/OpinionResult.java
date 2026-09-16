package dev.otectus.mcareputation.api;

import java.util.Optional;

/**
 * A per-villager opinion together with the reason it is or is not available (§5 F14).
 *
 * <p>The distinction this exists for: a villager who genuinely knows nothing about a player is
 * {@link OpinionAvailability#AVAILABLE} with a zero opinion, and a caller must <b>not</b> fall back
 * to public standing for it. Only the other three states license that fallback — private ignorance is
 * not public omniscience.
 *
 * @since MCA: Reputation 0.4.1
 */
public record OpinionResult(OpinionAvailability availability, Optional<VillagerOpinion> opinion) {

    public OpinionResult {
        opinion = opinion == null ? Optional.empty() : opinion;
    }

    /** Why an opinion is or is not answerable. */
    public enum OpinionAvailability {

        /** A real answer, even when it is zero. */
        AVAILABLE,

        /** The opinion feature, or the mod, is switched off. */
        DISABLED,

        /** This build cannot derive an opinion here at all. */
        UNSUPPORTED,

        /** The villager, player, community or server could not be resolved. */
        UNRESOLVED
    }

    public static OpinionResult available(VillagerOpinion opinion) {
        return new OpinionResult(OpinionAvailability.AVAILABLE, Optional.of(opinion));
    }

    public static OpinionResult unavailable(OpinionAvailability availability) {
        return new OpinionResult(availability, Optional.empty());
    }

    public boolean isAvailable() {
        return availability == OpinionAvailability.AVAILABLE;
    }
}

package dev.otectus.mcareputation.api;

import net.minecraft.resources.ResourceLocation;

/**
 * A companion mod's claim to be the one that detects a {@link CoreIncidentKind}.
 *
 * <p>Register with {@link McaReputationApi#registerCoreIncidentAuthority}. While a registered
 * authority answers {@code true} from {@link #owns}, this mod's own automatic hook for that kind
 * stands down and records nothing — the claimant is expected to file the equivalent incident through
 * {@link McaReputationApi#record} instead. Nothing else changes: scores, decay, gossip, witnesses and
 * the ledger all behave exactly as they would have, because the incident that arrives is the same one.
 *
 * <h2>Why the claim is a question, not a flag</h2>
 *
 * <p>{@link #owns} is consulted <em>per event</em> rather than read once at registration, and that is
 * the whole design. A companion's detection is usually conditional on its own config — MCA: Crime, for
 * instance, only owns these deeds while its crime detection and its Reputation integration are both
 * switched on. If the claim were a one-time flag, an operator turning that config off would silently
 * disable villager assault detection in <em>both</em> mods and nobody would record anything. Asking
 * every time means detection returns here on the very next event, with no re-registration and no
 * restart.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>{@link #owns} is called on the server thread from inside a damage or death event, so it must
 *       be <b>cheap</b> — a couple of boolean reads. Do not query world state, walk entity lists, or
 *       take locks.</li>
 *   <li>It must not call back into {@link McaReputationApi}. It is invoked while this mod is deciding
 *       whether to record something; asking it a question mid-decision invites reentrancy.</li>
 *   <li>Throwing is contained and treated as {@code false} — see
 *       {@link McaReputationApi#hasExternalAuthority} for why that direction, and not the other.</li>
 * </ul>
 *
 * @since MCA: Reputation 0.3.0
 */
public interface CoreIncidentAuthority {

    /**
     * A stable id for the claiming mod, used in logs and {@code /mcareputation debug authorities}.
     *
     * <p>Namespaced to the claimant, so an operator looking at "who is recording villager assaults"
     * gets an answer that names a mod rather than a class.
     */
    ResourceLocation authorityId();

    /**
     * Whether this mod is, <em>right now</em>, detecting and recording this kind itself.
     *
     * <p>Answer honestly and conservatively. {@code true} means "I am filing this incident, do not
     * file it as well"; every {@code false} hands detection straight back on the next event.
     */
    boolean owns(CoreIncidentKind kind);

    /** A human-readable name for diagnostics. Defaults to the {@link #authorityId()}. */
    default String authorityName() {
        return authorityId().toString();
    }
}

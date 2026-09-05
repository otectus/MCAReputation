package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.incident.BuiltinIncidents;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * The deeds this mod detects for itself, and which a companion may therefore claim.
 *
 * <p>Deliberately a small, closed enum rather than "any incident type". A companion may already
 * <em>record</em> anything it likes through {@link McaReputationApi#record}; what this enum exists for
 * is the narrower and much sharper problem of <b>double detection</b>. Two mods watching the same
 * {@code LivingDamageEvent.Post} and both filing an assault do not produce a debate, they produce two
 * penalties for one punch, and no amount of care on either side fixes that from the outside.
 *
 * <p>Only the automatic core hooks in {@code ReputationGameplayEvents} are listed, because they are
 * the only things this mod does <em>without being asked</em>. Everything else it records arrives
 * through the API or a command, where the caller already decides whether to send it and there is
 * nothing to arbitrate. §20.3's list of deliberate non-detections is, for the same reason, not here.
 *
 * @see CoreIncidentAuthority
 * @since MCA: Reputation 0.3.0
 */
public enum CoreIncidentKind {

    /**
     * Harming an MCA villager — the {@code LivingDamageEvent.Post} hook behind
     * {@link BuiltinIncidents#VILLAGER_ASSAULTED}, including its damage coalescing.
     */
    MCA_VILLAGER_ASSAULT(BuiltinIncidents.VILLAGER_ASSAULTED),

    /**
     * Killing an MCA villager — the {@code LivingDeathEvent} hook behind
     * {@link BuiltinIncidents#VILLAGER_KILLED}, including the precursor-assault upgrade.
     */
    MCA_VILLAGER_KILL(BuiltinIncidents.VILLAGER_KILLED),

    /**
     * Saving an MCA villager from the mob attacking them — the {@code LivingDeathEvent} hook behind
     * {@link BuiltinIncidents#VILLAGER_RESCUED}.
     *
     * @since MCA: Reputation 0.4.0
     */
    MCA_VILLAGER_RESCUE(BuiltinIncidents.VILLAGER_RESCUED),

    /**
     * Curing a zombie villager back into an MCA villager — the {@code LivingConversionEvent.Post}
     * hook behind {@link BuiltinIncidents#VILLAGER_CURED}.
     *
     * @since MCA: Reputation 0.4.0
     */
    MCA_VILLAGER_CURE(BuiltinIncidents.VILLAGER_CURED),

    /**
     * Being present for a village's victory over a raid — the hero-of-the-village hook behind
     * {@link BuiltinIncidents#RAID_REPELLED}.
     *
     * @since MCA: Reputation 0.4.0
     */
    MCA_RAID_REPELLED(BuiltinIncidents.RAID_REPELLED),

    /**
     * Killing another player where a village can see it — the {@code LivingDeathEvent} hook behind
     * {@link BuiltinIncidents#PLAYER_KILLED_IN_VILLAGE}. Off by default.
     *
     * @since MCA: Reputation 0.4.0
     */
    PLAYER_KILL_IN_VILLAGE(BuiltinIncidents.PLAYER_KILLED_IN_VILLAGE);

    private final ResourceLocation incidentType;

    CoreIncidentKind(ResourceLocation incidentType) {
        this.incidentType = incidentType;
    }

    /**
     * The incident this kind would produce.
     *
     * <p>Exposed so a claiming mod can file the <em>same</em> incident type this mod would have, and
     * the ledger reads identically whichever mod detected the deed. A companion that claims a kind and
     * then records something of its own invention has taken the detection away without replacing it.
     */
    public ResourceLocation incidentType() {
        return incidentType;
    }

    /** The kind that produces this incident type, or empty for anything not detected automatically. */
    public static Optional<CoreIncidentKind> forIncident(ResourceLocation incidentType) {
        if (incidentType == null) {
            return Optional.empty();
        }
        for (CoreIncidentKind kind : values()) {
            if (kind.incidentType.equals(incidentType)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}

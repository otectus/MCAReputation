package dev.otectus.mcareputation.api.event;

import dev.otectus.mcareputation.api.ReputationIncidentView;
import dev.otectus.mcareputation.incident.IncidentStatus;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Posted after an incident's status has moved — apologised for, atoned, forgiven, disproven
 * (spec §26.4).
 *
 * <p>Only real transitions post this. A repeated resolution to the same or a weaker status is a no-op
 * and stays silent, which is what stops a repeatable restitution quest from firing this event (and
 * any consequence hanging off it) more than once per incident (§33 rule 14).
 *
 * <p>{@link #incident} is the state <em>after</em> resolution; {@link #oldStatus} and
 * {@link #oldContribution} describe what it was before.
 */
public final class ReputationIncidentResolvedEvent extends ReputationEvent {

    private final ReputationIncidentView incident;
    private final IncidentStatus oldStatus;
    private final int oldContribution;
    private final ResourceLocation resolutionSource;

    public ReputationIncidentResolvedEvent(UUID playerId, @Nullable ServerPlayer player,
                                           ReputationIncidentView incident, IncidentStatus oldStatus,
                                           int oldContribution, ResourceLocation resolutionSource) {
        super(playerId, player, incident.community());
        this.incident = incident;
        this.oldStatus = oldStatus;
        this.oldContribution = oldContribution;
        this.resolutionSource = resolutionSource;
    }

    public ReputationIncidentView incident() {
        return incident;
    }

    public IncidentStatus oldStatus() {
        return oldStatus;
    }

    public IncidentStatus newStatus() {
        return incident.status();
    }

    public int oldContribution() {
        return oldContribution;
    }

    public int newContribution() {
        return incident.currentContribution();
    }

    /** Who resolved it — the quest, command, or conversation action responsible. */
    public ResourceLocation resolutionSource() {
        return resolutionSource;
    }
}

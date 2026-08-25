package dev.otectus.mcareputation.api.event;

import dev.otectus.mcareputation.api.ReputationIncidentView;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Posted after a new incident has been committed to the ledger (spec §26.3).
 *
 * <p>Carries an immutable {@link ReputationIncidentView}, not the live record, so a listener cannot
 * reach into the store and mutate history from an event handler.
 *
 * <p>Note that a created incident does not imply a score change: zero-delta narrative records — a
 * promise made, a hidden unwitnessed killing — post this event and no {@link ReputationChangedEvent}.
 * Listeners interested in standing should watch that event instead.
 */
public final class ReputationIncidentCreatedEvent extends ReputationEvent {

    private final ReputationIncidentView incident;

    public ReputationIncidentCreatedEvent(UUID playerId, @Nullable ServerPlayer player,
                                          ReputationIncidentView incident) {
        super(playerId, player, incident.community());
        this.incident = incident;
    }

    public ReputationIncidentView incident() {
        return incident;
    }
}

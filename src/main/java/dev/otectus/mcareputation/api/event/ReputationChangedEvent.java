package dev.otectus.mcareputation.api.event;

import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * Posted after a player's standing with a community has changed (spec §26.1).
 *
 * <p>Fires for every source of movement — a recorded deed, a resolution, decay reconciliation, an
 * administrative baseline change, a legacy import — so a listener that wants to react to "this
 * player's standing here is now different" needs only this one event. {@link #incidentId} and
 * {@link #incidentType} are empty for the changes that were not caused by a single deed.
 *
 * <p>{@link #delta} is the <em>applied</em> change, so at the score clamp it is zero even though a
 * larger change was requested. Listeners that display it can trust it.
 */
public final class ReputationChangedEvent extends ReputationEvent {

    private final int oldScore;
    private final int newScore;
    private final int delta;
    @Nullable
    private final UUID incidentId;
    @Nullable
    private final ResourceLocation incidentType;
    private final ResourceLocation source;

    public ReputationChangedEvent(UUID playerId, @Nullable ServerPlayer player, CommunityKey community,
                                  int oldScore, int newScore, int delta,
                                  @Nullable UUID incidentId, @Nullable ResourceLocation incidentType,
                                  ResourceLocation source) {
        super(playerId, player, community);
        this.oldScore = oldScore;
        this.newScore = newScore;
        this.delta = delta;
        this.incidentId = incidentId;
        this.incidentType = incidentType;
        this.source = source;
    }

    public int oldScore() {
        return oldScore;
    }

    public int newScore() {
        return newScore;
    }

    /** The change actually applied, after clamping. */
    public int delta() {
        return delta;
    }

    public Optional<UUID> incidentId() {
        return Optional.ofNullable(incidentId);
    }

    public Optional<ResourceLocation> incidentType() {
        return Optional.ofNullable(incidentType);
    }

    /** Who caused this — a core hook, a quest, a command, another mod. */
    public ResourceLocation source() {
        return source;
    }
}

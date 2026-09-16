package dev.otectus.mcareputation.api.event;

import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
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
    private final ChangeCause cause;
    private final boolean quiet;

    /** The original shape: a loud change caused by a deed. */
    public ReputationChangedEvent(UUID playerId, @Nullable ServerPlayer player, CommunityKey community,
                                  int oldScore, int newScore, int delta,
                                  @Nullable UUID incidentId, @Nullable ResourceLocation incidentType,
                                  ResourceLocation source) {
        this(playerId, player, community, oldScore, newScore, delta, incidentId, incidentType, source,
                ChangeCause.DEED, false);
    }

    /**
     * The full shape, carrying why the standing moved.
     *
     * @since MCA: Reputation 0.4.1
     */
    public ReputationChangedEvent(UUID playerId, @Nullable ServerPlayer player, CommunityKey community,
                                  int oldScore, int newScore, int delta,
                                  @Nullable UUID incidentId, @Nullable ResourceLocation incidentType,
                                  ResourceLocation source, ChangeCause cause, boolean quiet) {
        super(playerId, player, community);
        this.oldScore = oldScore;
        this.newScore = newScore;
        this.delta = delta;
        this.incidentId = incidentId;
        this.incidentType = incidentType;
        this.source = source;
        this.cause = cause == null ? ChangeCause.DEED : cause;
        this.quiet = quiet;
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

    /**
     * Why the standing moved. {@link ChangeCause#DEED} for a listener that predates this method.
     *
     * @since MCA: Reputation 0.4.1
     */
    public ChangeCause cause() {
        return cause;
    }

    /**
     * Whether this is a background change: mirrors and displays should follow it, deed toasts and
     * action-bar lines must not replay for it.
     *
     * @since MCA: Reputation 0.4.1
     */
    public boolean quiet() {
        return quiet;
    }
}

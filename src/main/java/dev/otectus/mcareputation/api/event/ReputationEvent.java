package dev.otectus.mcareputation.api.event;

import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * Base class for every event this mod posts (spec §26).
 *
 * <p>Three properties are common to all of them and are guaranteed here rather than restated:
 *
 * <ul>
 *   <li><b>Server side only.</b> They are posted from the transaction service, which asserts the
 *       server thread.</li>
 *   <li><b>Posted after the canonical commit.</b> The store is already consistent when a listener
 *       runs, so a listener may safely query it and will see the change that triggered them.</li>
 *   <li><b>Non-cancellable and immutable.</b> §26 is explicit that 0.1.0 exposes no pre-change
 *       cancellable event: cancellation would break idempotency (a dedupe key would be consumed by a
 *       transaction that then did not happen) and cross-mod atomicity. An add-on that wants to
 *       influence standing should author data or record and resolve incidents of its own.</li>
 * </ul>
 *
 * <p>The player may be offline — project and command paths can move standing with nobody online — so
 * {@link #player()} is optional while {@link #playerId()} is always present.
 */
public abstract class ReputationEvent extends Event {

    private final UUID playerId;
    @Nullable
    private final ServerPlayer player;
    private final CommunityKey community;

    protected ReputationEvent(UUID playerId, @Nullable ServerPlayer player, CommunityKey community) {
        this.playerId = playerId;
        this.player = player;
        this.community = community;
    }

    public UUID playerId() {
        return playerId;
    }

    /** The online player, when there is one. Empty for offline or server-driven changes. */
    public Optional<ServerPlayer> player() {
        return Optional.ofNullable(player);
    }

    public CommunityKey community() {
        return community;
    }

    @Override
    public final boolean isCancelable() {
        return false;
    }
}

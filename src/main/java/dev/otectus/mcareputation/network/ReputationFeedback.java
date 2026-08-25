package dev.otectus.mcareputation.network;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.api.event.ReputationChangedEvent;
import dev.otectus.mcareputation.api.event.ReputationTierChangedEvent;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.reputation.ReputationTier;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns committed standing changes into player-facing feedback (spec §28.3).
 *
 * <h2>Why this listens to its own events</h2>
 *
 * <p>The transaction service could send a packet inline, but then a quest that grants three rewards in
 * one claim would produce three action-bar lines that overwrite each other, and the player would see
 * only the last. §27.3 requires same-tick changes to be merged into one message, so feedback buffers
 * per player and flushes at the end of the server tick. Riding the mod's own public events also means
 * the feedback path uses exactly the information an add-on would — if the events are wrong, the player
 * sees it.
 *
 * <h2>Buffered per community</h2>
 *
 * <p>The buffer is keyed by (player, community), not player alone. Deltas from one community merged
 * under another community's label — or a tier computed from a score that belongs to a different
 * village — would be a lie with two villages changing in one tick, so each community flushes as its
 * own message and the client decides how to present several arriving together.
 *
 * <p>Nothing here decides what the player is <em>allowed</em> to see: the client config
 * ({@code showChangeActionBar}, {@code showExactScore}, …) is presentation, applied client-side. The
 * server sends the same truthful packet either way (§23.2).
 *
 * <p>Secrecy is handled upstream rather than here. An unwitnessed assault produces no score change at
 * all, so there is no change event and nothing to suppress — the player is never told about a
 * consequence that did not happen, and never tipped off that one did (§28.3).
 */
@Mod.EventBusSubscriber(modid = McaReputation.MOD_ID)
public final class ReputationFeedback {

    /** One tick's worth of pending feedback, per player and community. */
    private static final Map<UUID, Map<CommunityKey, Pending>> PENDING = new LinkedHashMap<>();

    private ReputationFeedback() {
    }

    /** Package-visible so {@code FeedbackMergeTest} can exercise the accumulation directly. */
    static final class Pending {
        int totalDelta;
        int newScore;
        boolean scoreKnown;
        boolean tierChanged;
        boolean downward;
        String newTierId;
        final List<String> milestoneTierIds = new ArrayList<>();
    }

    private static Pending pending(UUID playerId, CommunityKey community) {
        return PENDING.computeIfAbsent(playerId, id -> new LinkedHashMap<>())
                .computeIfAbsent(community, key -> new Pending());
    }

    @SubscribeEvent
    public static void onChanged(ReputationChangedEvent event) {
        if (event.delta() == 0) {
            return; // a zero-delta narrative record is not news (§28.3)
        }
        Pending pending = pending(event.playerId(), event.community());
        pending.totalDelta += event.delta();
        pending.newScore = event.newScore();
        pending.scoreKnown = true;
    }

    @SubscribeEvent
    public static void onTierChanged(ReputationTierChangedEvent event) {
        Pending pending = pending(event.playerId(), event.community());
        pending.tierChanged = true;
        pending.downward = event.downward();
        pending.newTierId = event.newTierId();
        if (event.upward() && event.firstTime()) {
            pending.milestoneTierIds.add(event.newTierId());
        }
    }

    /** Flushes at the end of the tick, so everything that happened this tick arrives merged. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }
        Map<UUID, Map<CommunityKey, Pending>> flushing = new LinkedHashMap<>(PENDING);
        PENDING.clear();
        flushing.forEach((playerId, communities) -> {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(playerId);
            if (player == null) {
                return; // offline: the change is saved, there is simply nobody to tell
            }
            communities.forEach((community, pending) -> {
                try {
                    Component communityName = communityName(player, community);
                    if (pending.totalDelta != 0 || pending.tierChanged) {
                        ReputationNetwork.sendTo(player, toChangePacket(communityName, pending));
                    }
                    for (String milestone : pending.milestoneTierIds) {
                        ReputationTiers.getDefault().byId(milestone).ifPresent(tier ->
                                ReputationNetwork.sendTo(player, new ReputationNetwork.TierToastS2C(
                                        communityName, tier.name())));
                    }
                } catch (Throwable t) {
                    McaReputation.LOGGER.debug("[MCA: Reputation] could not deliver feedback to {}",
                            player.getGameProfile().getName(), t);
                }
            });
        });
    }

    /**
     * Builds the merged packet for one community. The tier label prefers the tier event's own id —
     * a tick that produced only a tier event carries no score to derive one from — and falls back to
     * the score this community actually reached, never another community's number.
     */
    static ReputationNetwork.ChangeS2C toChangePacket(Component communityName, Pending pending) {
        Component tierName = null;
        if (pending.newTierId != null) {
            tierName = ReputationTiers.getDefault().byId(pending.newTierId)
                    .map(ReputationTier::name).orElse(null);
        }
        if (tierName == null && pending.scoreKnown) {
            tierName = ReputationTiers.getDefault().tierFor(pending.newScore).name();
        }
        if (tierName == null) {
            tierName = Component.empty();
        }
        return new ReputationNetwork.ChangeS2C(communityName, pending.totalDelta, tierName,
                pending.tierChanged, pending.downward, !pending.milestoneTierIds.isEmpty());
    }

    /** Drops buffered feedback and rate-limit state for a departing player. */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
        ReputationNetwork.forget(event.getEntity().getUUID());
    }

    /** Clears every buffer on server stop; the next world in this JVM starts clean. */
    public static void clearAll() {
        PENDING.clear();
    }

    /** Test seam: the pending buffer for one player, in accumulation order. */
    static Map<CommunityKey, Pending> pendingForTest(UUID playerId) {
        return PENDING.getOrDefault(playerId, Map.of());
    }

    /** The cached village name, or the localized "Village #n" fallback (§12.3). */
    private static Component communityName(ServerPlayer player, CommunityKey community) {
        return ReputationSavedData.get(player.server).player(player.getUUID())
                .flatMap(record -> record.community(community))
                .map(record -> record.metadata().displayName(community))
                .orElseGet(() -> dev.otectus.mcareputation.community.CommunityMetadata.EMPTY
                        .displayName(community));
    }
}

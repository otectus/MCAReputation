package dev.otectus.mcareputation.network;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.api.ReputationIncidentView;
import dev.otectus.mcareputation.api.ReputationSnapshot;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityMetadata;
import dev.otectus.mcareputation.community.CommunityResolver;
import dev.otectus.mcareputation.compat.McaCompat;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import dev.otectus.mcareputation.reputation.ReputationService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The mod's network channel and every packet on it (spec §27).
 *
 * <h2>Server authority</h2>
 *
 * <p>The client sends exactly one thing: "show me my standing, optionally in the context of this
 * entity". It cannot send a score, a delta, a title, an incident, a witness, a status, or a village
 * id that the server then trusts. Everything travelling the other way is derived server-side from the
 * canonical store.
 *
 * <p>The one client-supplied value — a context entity id — is validated before use: the entity must
 * exist, be in the same dimension, be a living MCA villager, and be within 12 blocks (§27.2). Requests
 * are rate limited to one per 10 ticks per player, so spamming the button costs the server one map
 * lookup rather than a ledger walk.
 *
 * <p>Packets are registered in a fixed order with an explicit protocol version, so a mismatched
 * client is rejected at handshake rather than misreading a payload.
 */
public final class ReputationNetwork {

    private static final String PROTOCOL_VERSION = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            McaReputation.id("main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    /** §27.2: at most one snapshot request per player per this many ticks. */
    private static final int REQUEST_COOLDOWN_TICKS = 10;

    /** §27.2: a context villager must be within this many blocks to be a valid interaction subject. */
    private static final double MAX_CONTEXT_DISTANCE = 12.0D;

    private static final Map<UUID, Long> LAST_REQUEST_TICK = new HashMap<>();

    private ReputationNetwork() {
    }

    /** Registers every packet in a deterministic order. Called once, from mod setup. */
    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, RequestSnapshotC2S.class,
                RequestSnapshotC2S::encode, RequestSnapshotC2S::decode, RequestSnapshotC2S::handle);
        CHANNEL.registerMessage(id++, SnapshotS2C.class,
                SnapshotS2C::encode, SnapshotS2C::decode, SnapshotS2C::handle);
        CHANNEL.registerMessage(id++, OpenScreenS2C.class,
                OpenScreenS2C::encode, OpenScreenS2C::decode, OpenScreenS2C::handle);
        CHANNEL.registerMessage(id++, ChangeS2C.class,
                ChangeS2C::encode, ChangeS2C::decode, ChangeS2C::handle);
        CHANNEL.registerMessage(id, TierToastS2C.class,
                TierToastS2C::encode, TierToastS2C::decode, TierToastS2C::handle);
    }

    public static void sendTo(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * Pushes the standing screen to a client after a <b>server-validated</b> interaction (§29.7) —
     * the Quests Journal's "View Deeds" link arrives here via {@code McaReputationApi}. The fresh
     * snapshot goes first: an open with nothing behind it would show whatever stale cache the client
     * still holds.
     */
    public static void openScreenWithSnapshot(ServerPlayer player,
                                              @javax.annotation.Nullable CommunityKey community) {
        long gameTime = player.server.overworld().getGameTime();
        sendTo(player, buildSnapshot(player, Optional.ofNullable(community), gameTime));
        sendTo(player, new OpenScreenS2C());
    }

    /** Clears per-player rate-limit state on disconnect so the map cannot grow across sessions. */
    public static void forget(UUID playerId) {
        LAST_REQUEST_TICK.remove(playerId);
    }

    /** Clears every rate-limit stamp on server stop; the next world in this JVM starts clean. */
    public static void clearAll() {
        LAST_REQUEST_TICK.clear();
    }

    // ==================================================================
    // C2S
    // ==================================================================

    /**
     * "Send me my standing." Optionally names an entity the player is interacting with, so the server
     * can preselect that villager's community — the client never says <em>which</em> community, only
     * which entity it is looking at, and the server decides what that means.
     */
    public record RequestSnapshotC2S(int contextEntityId, Optional<CommunityKey> requestedCommunity) {

        public static void encode(RequestSnapshotC2S packet, FriendlyByteBuf buf) {
            buf.writeVarInt(packet.contextEntityId);
            buf.writeOptional(packet.requestedCommunity, (b, key) -> key.write(b));
        }

        public static RequestSnapshotC2S decode(FriendlyByteBuf buf) {
            int entityId = buf.readVarInt();
            Optional<CommunityKey> community = buf.readOptional(CommunityKey::read);
            return new RequestSnapshotC2S(entityId, community);
        }

        public static void handle(RequestSnapshotC2S packet, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player == null) {
                    return;
                }
                long gameTime = player.server.overworld().getGameTime();
                Long last = LAST_REQUEST_TICK.get(player.getUUID());
                if (last != null && gameTime - last < REQUEST_COOLDOWN_TICKS) {
                    return; // rate limited; silently ignored, not an error worth telling the client about
                }
                LAST_REQUEST_TICK.put(player.getUUID(), gameTime);

                Optional<CommunityKey> selected = resolveSelection(player, packet, gameTime);
                sendTo(player, buildSnapshot(player, selected, gameTime));
            });
            ctx.setPacketHandled(true);
        }

        /**
         * Decides which community the reply should detail, trusting nothing the client said about
         * villages. A named community is honoured only if the player already has a record for it.
         */
        private static Optional<CommunityKey> resolveSelection(ServerPlayer player, RequestSnapshotC2S packet,
                                                               long gameTime) {
            if (packet.requestedCommunity().isPresent()) {
                CommunityKey requested = packet.requestedCommunity().get();
                boolean known = dev.otectus.mcareputation.state.ReputationSavedData.get(player.server)
                        .knows(player.getUUID(), requested);
                if (known) {
                    return Optional.of(requested);
                }
            }
            if (packet.contextEntityId() > 0 && player.level() instanceof ServerLevel level) {
                Entity entity = level.getEntity(packet.contextEntityId());
                boolean valid = entity != null
                        && entity.level().dimension().equals(player.level().dimension())
                        && McaCompat.isLivingMcaVillager(entity)
                        && entity.distanceTo(player) <= MAX_CONTEXT_DISTANCE;
                if (valid) {
                    Optional<CommunityKey> community = CommunityResolver.resolve(entity);
                    if (community.isPresent()) {
                        // Touch the metadata cache so a first-ever look at a village records its name.
                        dev.otectus.mcareputation.community.CommunityMetadata fresh =
                                CommunityResolver.readMetadata(level, community.get(), gameTime);
                        if (fresh != CommunityMetadata.EMPTY) {
                            ReputationService.cacheCommunityMetadata(player.server, player.getUUID(),
                                    community.get(), fresh);
                        }
                        return community;
                    }
                }
            }
            // Fall back to wherever the player is standing, then to their best-known community.
            if (player.level() instanceof ServerLevel level) {
                Optional<CommunityKey> here = CommunityResolver.resolveNearest(level, player.blockPosition());
                if (here.isPresent()) {
                    return here;
                }
            }
            return ReputationService.knownCommunities(player.server, player.getUUID(), gameTime).stream()
                    .findFirst()
                    .map(ReputationSnapshot::community);
        }
    }

    // ==================================================================
    // S2C
    // ==================================================================

    /** A community as it appears in the screen's selector list. */
    public record CommunitySummary(CommunityKey key, String name, int score, String tierId) {

        public static void write(FriendlyByteBuf buf, CommunitySummary summary) {
            summary.key.write(buf);
            buf.writeUtf(summary.name, CommunityMetadata.MAX_NAME_LENGTH);
            buf.writeInt(summary.score);
            buf.writeUtf(summary.tierId, 48);
        }

        public static CommunitySummary read(FriendlyByteBuf buf) {
            CommunityKey key = CommunityKey.read(buf);
            String name = buf.readUtf(CommunityMetadata.MAX_NAME_LENGTH);
            int score = buf.readInt();
            String tierId = buf.readUtf(48);
            return new CommunitySummary(key, name, score, tierId);
        }
    }

    /** One line of the deeds list. */
    public record IncidentSummary(UUID id, ResourceLocation type, Component display, long ageTicks,
                                  int contribution, String status, String severity, boolean pinned) {

        public static void write(FriendlyByteBuf buf, IncidentSummary summary) {
            buf.writeUUID(summary.id);
            buf.writeResourceLocation(summary.type);
            buf.writeComponent(summary.display);
            buf.writeVarLong(Math.max(0L, summary.ageTicks));
            buf.writeInt(summary.contribution);
            buf.writeUtf(summary.status, 32);
            buf.writeUtf(summary.severity, 32);
            buf.writeBoolean(summary.pinned);
        }

        public static IncidentSummary read(FriendlyByteBuf buf) {
            UUID id = buf.readUUID();
            ResourceLocation type = buf.readResourceLocation();
            Component display = buf.readComponent();
            long age = buf.readVarLong();
            int contribution = buf.readInt();
            String status = buf.readUtf(32);
            String severity = buf.readUtf(32);
            boolean pinned = buf.readBoolean();
            return new IncidentSummary(id, type, display, age, contribution, status, severity, pinned);
        }
    }

    /**
     * The selected community, in full.
     *
     * <p>Titles travel as resolved {@link Component}s, not ids. The {@code Titles} registry is
     * populated by the <b>server's</b> datapack reload; a dedicated-server client has an empty copy
     * and would render every id as its raw path. Tier names already crossed the wire resolved for the
     * same reason — titles and the tier description now follow the same rule.
     */
    public record SelectedDetail(CommunityKey key, String name, int score, int baseline,
                                 String tierId, Component tierName,
                                 Optional<Component> tierDescription, int tierThreshold,
                                 Optional<String> nextTierId, Optional<Component> nextTierName,
                                 int nextThreshold, List<Component> titles,
                                 List<IncidentSummary> incidents, int totalIncidents) {

        public static void write(FriendlyByteBuf buf, SelectedDetail detail) {
            detail.key.write(buf);
            buf.writeUtf(detail.name, CommunityMetadata.MAX_NAME_LENGTH);
            buf.writeInt(detail.score);
            buf.writeInt(detail.baseline);
            buf.writeUtf(detail.tierId, 48);
            buf.writeComponent(detail.tierName);
            buf.writeOptional(detail.tierDescription, FriendlyByteBuf::writeComponent);
            buf.writeInt(detail.tierThreshold);
            buf.writeOptional(detail.nextTierId, (b, value) -> b.writeUtf(value, 48));
            buf.writeOptional(detail.nextTierName, FriendlyByteBuf::writeComponent);
            buf.writeInt(detail.nextThreshold);
            buf.writeCollection(detail.titles, FriendlyByteBuf::writeComponent);
            buf.writeCollection(detail.incidents, IncidentSummary::write);
            buf.writeVarInt(Math.max(0, detail.totalIncidents));
        }

        public static SelectedDetail read(FriendlyByteBuf buf) {
            CommunityKey key = CommunityKey.read(buf);
            String name = buf.readUtf(CommunityMetadata.MAX_NAME_LENGTH);
            int score = buf.readInt();
            int baseline = buf.readInt();
            String tierId = buf.readUtf(48);
            Component tierName = buf.readComponent();
            Optional<Component> tierDescription = buf.readOptional(FriendlyByteBuf::readComponent);
            int tierThreshold = buf.readInt();
            Optional<String> nextTierId = buf.readOptional(b -> b.readUtf(48));
            Optional<Component> nextTierName = buf.readOptional(FriendlyByteBuf::readComponent);
            int nextThreshold = buf.readInt();
            List<Component> titles = buf.readList(FriendlyByteBuf::readComponent);
            List<IncidentSummary> incidents = buf.readList(IncidentSummary::read);
            int total = buf.readVarInt();
            return new SelectedDetail(key, name, score, baseline, tierId, tierName, tierDescription,
                    tierThreshold, nextTierId, nextTierName, nextThreshold, titles, incidents, total);
        }
    }

    /**
     * The whole reply: every community the player is known in, plus the detail of the selected one.
     *
     * <p>Bounded before encoding (§27.3): at most {@link ReputationBounds#MAX_SYNCED_COMMUNITIES}
     * communities and {@link ReputationBounds#MAX_SYNCED_INCIDENTS} incident lines, so a player with a
     * maximal ledger cannot produce a packet large enough to disconnect them.
     */
    public record SnapshotS2C(List<CommunitySummary> communities, Optional<SelectedDetail> selected,
                              List<Component> globalTitles) {

        public static void encode(SnapshotS2C packet, FriendlyByteBuf buf) {
            buf.writeCollection(packet.communities.stream()
                    .limit(ReputationBounds.MAX_SYNCED_COMMUNITIES).toList(), CommunitySummary::write);
            buf.writeOptional(packet.selected, SelectedDetail::write);
            buf.writeCollection(packet.globalTitles.stream()
                    .limit(ReputationBounds.MAX_TITLES).toList(), FriendlyByteBuf::writeComponent);
        }

        public static SnapshotS2C decode(FriendlyByteBuf buf) {
            List<CommunitySummary> communities = buf.readList(CommunitySummary::read);
            Optional<SelectedDetail> selected = buf.readOptional(SelectedDetail::read);
            List<Component> globalTitles = buf.readList(FriendlyByteBuf::readComponent);
            return new SnapshotS2C(communities, selected, globalTitles);
        }

        public static void handle(SnapshotS2C packet, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> ClientPacketHandler.acceptSnapshot(packet));
            ctx.setPacketHandled(true);
        }
    }

    /** Tells the client to open the standing screen, after a validated server-side interaction. */
    public record OpenScreenS2C() {

        public static void encode(OpenScreenS2C packet, FriendlyByteBuf buf) {
        }

        public static OpenScreenS2C decode(FriendlyByteBuf buf) {
            return new OpenScreenS2C();
        }

        public static void handle(OpenScreenS2C packet, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(ClientPacketHandler::openScreen);
            ctx.setPacketHandled(true);
        }
    }

    /**
     * One merged standing change, for the action bar (§28.3). {@code firstTime} rides along so the
     * client can tell an already-celebrated upward crossing (quiet chat line) from a first-time
     * milestone (which gets the toast instead and must not be announced twice).
     */
    public record ChangeS2C(Component communityName, int delta, Component tierName, boolean tierChanged,
                            boolean downward, boolean firstTime) {

        public static void encode(ChangeS2C packet, FriendlyByteBuf buf) {
            buf.writeComponent(packet.communityName);
            buf.writeInt(packet.delta);
            buf.writeComponent(packet.tierName);
            buf.writeBoolean(packet.tierChanged);
            buf.writeBoolean(packet.downward);
            buf.writeBoolean(packet.firstTime);
        }

        public static ChangeS2C decode(FriendlyByteBuf buf) {
            Component name = buf.readComponent();
            int delta = buf.readInt();
            Component tierName = buf.readComponent();
            boolean tierChanged = buf.readBoolean();
            boolean downward = buf.readBoolean();
            boolean firstTime = buf.readBoolean();
            return new ChangeS2C(name, delta, tierName, tierChanged, downward, firstTime);
        }

        public static void handle(ChangeS2C packet, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> ClientPacketHandler.acceptChange(packet));
            ctx.setPacketHandled(true);
        }
    }

    /** A first-time upward tier transition, worth a toast (§17.3). */
    public record TierToastS2C(Component communityName, Component tierName) {

        public static void encode(TierToastS2C packet, FriendlyByteBuf buf) {
            buf.writeComponent(packet.communityName);
            buf.writeComponent(packet.tierName);
        }

        public static TierToastS2C decode(FriendlyByteBuf buf) {
            return new TierToastS2C(buf.readComponent(), buf.readComponent());
        }

        public static void handle(TierToastS2C packet, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> ClientPacketHandler.acceptToast(packet));
            ctx.setPacketHandled(true);
        }
    }

    // ==================================================================
    // Snapshot building (server side)
    // ==================================================================

    /** Builds the reply for one player, bounded and ready to encode. */
    public static SnapshotS2C buildSnapshot(ServerPlayer player, Optional<CommunityKey> selected, long gameTime) {
        List<ReputationSnapshot> all = ReputationService.knownCommunities(player.server, player.getUUID(),
                gameTime);
        List<CommunitySummary> summaries = new ArrayList<>();
        for (ReputationSnapshot snapshot : all) {
            if (summaries.size() >= ReputationBounds.MAX_SYNCED_COMMUNITIES) {
                break;
            }
            summaries.add(new CommunitySummary(snapshot.community(),
                    snapshot.metadata().name(), snapshot.score(), snapshot.tierId()));
        }

        Optional<ReputationSnapshot> detail = selected
                .flatMap(key -> ReputationService.snapshot(player.server, player.getUUID(), key, gameTime));
        // A community the player has never interacted with has no record. Rather than showing nothing,
        // synthesise an empty selection so the screen can say "you are a stranger here" honestly.
        Optional<SelectedDetail> selectedDetail = detail.map(ReputationNetwork::toDetail);
        if (selectedDetail.isEmpty() && selected.isPresent()) {
            selectedDetail = Optional.of(emptyDetail(player, selected.get(), gameTime));
        }

        // Resolved server-side: the client's Titles registry is empty on a dedicated server.
        List<Component> globalTitles = dev.otectus.mcareputation.reputation.TitleService
                .globalTitles(player.server, player.getUUID()).stream()
                .map(ReputationNetwork::resolveTitleName)
                .toList();
        return new SnapshotS2C(summaries, selectedDetail, globalTitles);
    }

    private static Component resolveTitleName(ResourceLocation titleId) {
        return dev.otectus.mcareputation.reputation.Titles.getOrUnknown(titleId).name();
    }

    private static SelectedDetail toDetail(ReputationSnapshot snapshot) {
        List<IncidentSummary> incidents = new ArrayList<>();
        for (ReputationIncidentView view : snapshot.incidents()) {
            if (incidents.size() >= ReputationBounds.MAX_SYNCED_INCIDENTS) {
                break;
            }
            incidents.add(new IncidentSummary(view.id(), view.type(), view.display(), view.ageTicks(),
                    view.currentContribution(), view.status().jsonName(), view.severity().jsonName(),
                    view.pinned()));
        }
        return new SelectedDetail(
                snapshot.community(),
                snapshot.metadata().name(),
                snapshot.score(),
                snapshot.baseline(),
                snapshot.tierId(),
                snapshot.tier().name(),
                snapshot.tier().description(),
                snapshot.tier().threshold(),
                snapshot.nextTier().map(tier -> tier.id()),
                snapshot.nextTier().map(tier -> tier.name()),
                snapshot.nextTier().map(tier -> tier.threshold()).orElse(snapshot.tier().threshold()),
                snapshot.villageTitles().stream().map(ReputationNetwork::resolveTitleName).toList(),
                incidents,
                snapshot.totalIncidentCount());
    }

    /** The "you have no history here yet" selection, built without creating a saved record. */
    private static SelectedDetail emptyDetail(ServerPlayer player, CommunityKey key, long gameTime) {
        var ladder = dev.otectus.mcareputation.reputation.ReputationTiers.getDefault();
        var tier = ladder.tierFor(0);
        var next = ladder.nextTier(0);
        String name = player.level() instanceof ServerLevel level
                ? CommunityResolver.readMetadata(level, key, gameTime).name()
                : "";
        return new SelectedDetail(key, name, 0, 0, tier.id(), tier.name(), tier.description(),
                tier.threshold(), next.map(t -> t.id()), next.map(t -> t.name()),
                next.map(t -> t.threshold()).orElse(tier.threshold()),
                List.of(), List.of(), 0);
    }
}

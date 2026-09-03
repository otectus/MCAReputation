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
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The mod's network payloads and their registration (spec §27).
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
 * <h2>What the NeoForge port changed</h2>
 *
 * <p>The five packets are the same five packets, but they are now named {@link CustomPacketPayload}s
 * registered through {@link PayloadRegistrar} instead of numerically-discriminated messages on a
 * {@code SimpleChannel}. The protocol version is bumped to {@code 3} to make the incompatible wire
 * revision auditable, even though a 1.20.1 client could never reach a 1.21.1 server anyway.
 *
 * <p>All five top-level codecs run over {@link RegistryFriendlyByteBuf}, because
 * {@code FriendlyByteBuf.writeComponent}/{@code readComponent} are gone in 1.21.1 and
 * {@link ComponentSerialization#STREAM_CODEC} needs registry context. Lower-level helpers such as
 * {@link CommunityKey#write} still take a plain {@code FriendlyByteBuf}, which
 * {@code RegistryFriendlyByteBuf} extends.
 *
 * <p>Decoding is now bounded as well as encoding (§27.3): {@link #readBoundedList} rejects an
 * oversized count <em>before</em> allocating, rather than reading whatever the sender claimed. The
 * outbound {@code .limit(...)} calls are kept as defence in depth.
 *
 * <p>Handlers run on the main thread, which is the registrar's default and the same guarantee the old
 * {@code ctx.enqueueWork(...)} provided. Each is wrapped in {@code try/catch (Throwable)} because an
 * exception escaping a NeoForge payload handler disconnects the player, where the Forge
 * {@code SimpleChannel} merely logged.
 */
public final class ReputationNetwork {

    /**
     * Bumped from the Forge channel's {@code "2"}: the framing, the payload ids and the component
     * encoding all changed with the loader, so nothing on the old protocol could talk to this.
     */
    private static final String PROTOCOL_VERSION = "3";

    /** §27.2: at most one snapshot request per player per this many ticks. */
    private static final int REQUEST_COOLDOWN_TICKS = 10;

    /** §27.2: a context villager must be within this many blocks to be a valid interaction subject. */
    private static final double MAX_CONTEXT_DISTANCE = 12.0D;

    /** Bounds for the short id strings that ride along inside payloads. */
    private static final int MAX_TIER_ID_LENGTH = 48;
    private static final int MAX_STATUS_LENGTH = 32;
    private static final int MAX_SEVERITY_LENGTH = 32;

    private static final Map<UUID, Long> LAST_REQUEST_TICK = new HashMap<>();

    private ReputationNetwork() {
    }

    /**
     * Registers every payload in a deterministic order. A mod-bus listener, wired up in the
     * {@code McaReputationMod} constructor — registering a payload later throws.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(McaReputation.MOD_ID).versioned(PROTOCOL_VERSION);

        registrar.playToServer(RequestSnapshotC2S.TYPE, RequestSnapshotC2S.STREAM_CODEC,
                ReputationNetwork::handleRequestSnapshot);
        registrar.playToClient(SnapshotS2C.TYPE, SnapshotS2C.STREAM_CODEC,
                ReputationNetwork::handleSnapshot);
        registrar.playToClient(OpenScreenS2C.TYPE, OpenScreenS2C.STREAM_CODEC,
                ReputationNetwork::handleOpenScreen);
        registrar.playToClient(ChangeS2C.TYPE, ChangeS2C.STREAM_CODEC,
                ReputationNetwork::handleChange);
        registrar.playToClient(TierToastS2C.TYPE, TierToastS2C.STREAM_CODEC,
                ReputationNetwork::handleTierToast);
    }

    /** Type-safe replacement for the old {@code CHANNEL.send(PacketDistributor.PLAYER…, Object)}. */
    public static void sendTo(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * Pushes the standing screen to a client after a <b>server-validated</b> interaction (§29.7) —
     * the Quests Journal's "View Deeds" link arrives here via {@code McaReputationApi}. The fresh
     * snapshot goes first: an open with nothing behind it would show whatever stale cache the client
     * still holds.
     */
    public static void openScreenWithSnapshot(ServerPlayer player, @Nullable CommunityKey community) {
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
    // Codec helpers
    // ==================================================================

    /** Builds a member stream codec over the registry-aware buffer the components need. */
    private static <T> StreamCodec<RegistryFriendlyByteBuf, T> codec(
            BiConsumer<T, RegistryFriendlyByteBuf> encode,
            Function<RegistryFriendlyByteBuf, T> decode) {
        return StreamCodec.of((buf, value) -> encode.accept(value, buf), decode::apply);
    }

    private static void writeComponent(RegistryFriendlyByteBuf buf, Component component) {
        ComponentSerialization.STREAM_CODEC.encode(buf, component);
    }

    private static Component readComponent(RegistryFriendlyByteBuf buf) {
        return ComponentSerialization.STREAM_CODEC.decode(buf);
    }

    /**
     * Reads a length-prefixed list, refusing an oversized count <b>before</b> allocating.
     *
     * <p>The Forge build bounded lists on the way out but used unbounded {@code readList} on the way
     * in, so a hostile peer could make the receiver allocate an arbitrarily large list. Rejecting the
     * count outright — rather than reading and truncating — means the work is never done at all.
     */
    private static <T> List<T> readBoundedList(RegistryFriendlyByteBuf buf, int limit,
                                               Function<RegistryFriendlyByteBuf, T> reader,
                                               String what) {
        int count = buf.readVarInt();
        if (count < 0 || count > limit) {
            throw new DecoderException("mcareputation: " + what + " count " + count
                    + " outside [0, " + limit + "]");
        }
        List<T> out = new ArrayList<>(Math.min(count, 16));
        for (int i = 0; i < count; i++) {
            out.add(reader.apply(buf));
        }
        return List.copyOf(out);
    }

    private static <T> void writeBoundedList(RegistryFriendlyByteBuf buf, List<T> values, int limit,
                                             BiConsumer<RegistryFriendlyByteBuf, T> writer) {
        List<T> bounded = values.size() <= limit ? values : values.subList(0, limit);
        buf.writeVarInt(bounded.size());
        for (T value : bounded) {
            writer.accept(buf, value);
        }
    }

    private static <T> void writeOptional(RegistryFriendlyByteBuf buf, Optional<T> value,
                                          BiConsumer<RegistryFriendlyByteBuf, T> writer) {
        buf.writeBoolean(value.isPresent());
        value.ifPresent(present -> writer.accept(buf, present));
    }

    private static <T> Optional<T> readOptional(RegistryFriendlyByteBuf buf,
                                                Function<RegistryFriendlyByteBuf, T> reader) {
        return buf.readBoolean() ? Optional.of(reader.apply(buf)) : Optional.empty();
    }

    // ==================================================================
    // C2S
    // ==================================================================

    /**
     * "Send me my standing." Optionally names an entity the player is interacting with, so the server
     * can preselect that villager's community — the client never says <em>which</em> community, only
     * which entity it is looking at, and the server decides what that means.
     */
    public record RequestSnapshotC2S(int contextEntityId, Optional<CommunityKey> requestedCommunity)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<RequestSnapshotC2S> TYPE =
                new CustomPacketPayload.Type<>(McaReputation.id("request_snapshot"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestSnapshotC2S> STREAM_CODEC =
                codec(RequestSnapshotC2S::write, RequestSnapshotC2S::read);

        @Override
        public CustomPacketPayload.Type<RequestSnapshotC2S> type() {
            return TYPE;
        }

        private static void write(RequestSnapshotC2S packet, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(packet.contextEntityId);
            writeOptional(buf, packet.requestedCommunity, (b, key) -> key.write(b));
        }

        private static RequestSnapshotC2S read(RegistryFriendlyByteBuf buf) {
            int entityId = buf.readVarInt();
            Optional<CommunityKey> community = readOptional(buf, CommunityKey::read);
            return new RequestSnapshotC2S(entityId, community);
        }
    }

    private static void handleRequestSnapshot(RequestSnapshotC2S packet, IPayloadContext context) {
        try {
            if (!(context.player() instanceof ServerPlayer player)) {
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
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] snapshot request handler failed; ignoring", t);
        }
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
                    CommunityMetadata fresh =
                            CommunityResolver.readMetadata(level, community.get(), gameTime);
                    if (fresh != CommunityMetadata.EMPTY) {
                        ReputationService.cacheCommunityMetadata(player.server, player.getUUID(),
                                community.get(), fresh);
                    }
                    return community;
                }
            }
        }
        // Nothing was asked about in particular, so the answer is positional or historical.
        Optional<CommunityKey> here = player.level() instanceof ServerLevel level
                ? CommunityResolver.resolveNearest(level, player.blockPosition())
                : Optional.empty();
        boolean knowsHere = here.isPresent()
                && dev.otectus.mcareputation.state.ReputationSavedData.get(player.server)
                        .knows(player.getUUID(), here.get());
        Optional<CommunityKey> bestKnown =
                ReputationService.knownCommunities(player.server, player.getUUID(), gameTime).stream()
                        .findFirst()
                        .map(ReputationSnapshot::community);
        return SnapshotSelection.unprompted(here, knowsHere, bestKnown);
    }

    // ==================================================================
    // S2C
    // ==================================================================

    /**
     * A community as it appears in the screen's selector list.
     *
     * <p>A nested value type, not a payload: it never travels on its own, so it needs no {@code TYPE}.
     */
    public record CommunitySummary(CommunityKey key, String name, int score, String tierId) {

        static void write(RegistryFriendlyByteBuf buf, CommunitySummary summary) {
            summary.key.write(buf);
            buf.writeUtf(summary.name, CommunityMetadata.MAX_NAME_LENGTH);
            buf.writeInt(summary.score);
            buf.writeUtf(summary.tierId, MAX_TIER_ID_LENGTH);
        }

        static CommunitySummary read(RegistryFriendlyByteBuf buf) {
            CommunityKey key = CommunityKey.read(buf);
            String name = buf.readUtf(CommunityMetadata.MAX_NAME_LENGTH);
            int score = buf.readInt();
            String tierId = buf.readUtf(MAX_TIER_ID_LENGTH);
            return new CommunitySummary(key, name, score, tierId);
        }
    }

    /** One line of the deeds list. */
    public record IncidentSummary(UUID id, ResourceLocation type, Component display, long ageTicks,
                                  int contribution, String status, String severity, boolean pinned) {

        static void write(RegistryFriendlyByteBuf buf, IncidentSummary summary) {
            buf.writeUUID(summary.id);
            buf.writeResourceLocation(summary.type);
            writeComponent(buf, summary.display);
            buf.writeVarLong(Math.max(0L, summary.ageTicks));
            buf.writeInt(summary.contribution);
            buf.writeUtf(summary.status, MAX_STATUS_LENGTH);
            buf.writeUtf(summary.severity, MAX_SEVERITY_LENGTH);
            buf.writeBoolean(summary.pinned);
        }

        static IncidentSummary read(RegistryFriendlyByteBuf buf) {
            UUID id = buf.readUUID();
            ResourceLocation type = buf.readResourceLocation();
            Component display = readComponent(buf);
            long age = buf.readVarLong();
            int contribution = buf.readInt();
            String status = buf.readUtf(MAX_STATUS_LENGTH);
            String severity = buf.readUtf(MAX_SEVERITY_LENGTH);
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

        static void write(RegistryFriendlyByteBuf buf, SelectedDetail detail) {
            detail.key.write(buf);
            buf.writeUtf(detail.name, CommunityMetadata.MAX_NAME_LENGTH);
            buf.writeInt(detail.score);
            buf.writeInt(detail.baseline);
            buf.writeUtf(detail.tierId, MAX_TIER_ID_LENGTH);
            writeComponent(buf, detail.tierName);
            writeOptional(buf, detail.tierDescription, ReputationNetwork::writeComponent);
            buf.writeInt(detail.tierThreshold);
            writeOptional(buf, detail.nextTierId, (b, value) -> b.writeUtf(value, MAX_TIER_ID_LENGTH));
            writeOptional(buf, detail.nextTierName, ReputationNetwork::writeComponent);
            buf.writeInt(detail.nextThreshold);
            writeBoundedList(buf, detail.titles, ReputationBounds.MAX_TITLES,
                    ReputationNetwork::writeComponent);
            writeBoundedList(buf, detail.incidents, ReputationBounds.MAX_SYNCED_INCIDENTS,
                    IncidentSummary::write);
            buf.writeVarInt(Math.max(0, detail.totalIncidents));
        }

        static SelectedDetail read(RegistryFriendlyByteBuf buf) {
            CommunityKey key = CommunityKey.read(buf);
            String name = buf.readUtf(CommunityMetadata.MAX_NAME_LENGTH);
            int score = buf.readInt();
            int baseline = buf.readInt();
            String tierId = buf.readUtf(MAX_TIER_ID_LENGTH);
            Component tierName = readComponent(buf);
            Optional<Component> tierDescription = readOptional(buf, ReputationNetwork::readComponent);
            int tierThreshold = buf.readInt();
            Optional<String> nextTierId = readOptional(buf, b -> b.readUtf(MAX_TIER_ID_LENGTH));
            Optional<Component> nextTierName = readOptional(buf, ReputationNetwork::readComponent);
            int nextThreshold = buf.readInt();
            List<Component> titles = readBoundedList(buf, ReputationBounds.MAX_TITLES,
                    ReputationNetwork::readComponent, "selected titles");
            List<IncidentSummary> incidents = readBoundedList(buf, ReputationBounds.MAX_SYNCED_INCIDENTS,
                    IncidentSummary::read, "selected incidents");
            int total = buf.readVarInt();
            return new SelectedDetail(key, name, score, baseline, tierId, tierName, tierDescription,
                    tierThreshold, nextTierId, nextTierName, nextThreshold, titles, incidents, total);
        }
    }

    /**
     * The whole reply: every community the player is known in, plus the detail of the selected one.
     *
     * <p>Bounded on both sides (§27.3): at most {@link ReputationBounds#MAX_SYNCED_COMMUNITIES}
     * communities and {@link ReputationBounds#MAX_SYNCED_INCIDENTS} incident lines, so a player with a
     * maximal ledger cannot produce a packet large enough to disconnect them, and a hostile server
     * cannot make a client allocate an unbounded one.
     */
    public record SnapshotS2C(List<CommunitySummary> communities, Optional<SelectedDetail> selected,
                              List<Component> globalTitles) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<SnapshotS2C> TYPE =
                new CustomPacketPayload.Type<>(McaReputation.id("snapshot"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotS2C> STREAM_CODEC =
                codec(SnapshotS2C::write, SnapshotS2C::read);

        @Override
        public CustomPacketPayload.Type<SnapshotS2C> type() {
            return TYPE;
        }

        private static void write(SnapshotS2C packet, RegistryFriendlyByteBuf buf) {
            writeBoundedList(buf, packet.communities, ReputationBounds.MAX_SYNCED_COMMUNITIES,
                    CommunitySummary::write);
            writeOptional(buf, packet.selected, SelectedDetail::write);
            writeBoundedList(buf, packet.globalTitles, ReputationBounds.MAX_TITLES,
                    ReputationNetwork::writeComponent);
        }

        private static SnapshotS2C read(RegistryFriendlyByteBuf buf) {
            List<CommunitySummary> communities = readBoundedList(buf,
                    ReputationBounds.MAX_SYNCED_COMMUNITIES, CommunitySummary::read, "communities");
            Optional<SelectedDetail> selected = readOptional(buf, SelectedDetail::read);
            List<Component> globalTitles = readBoundedList(buf, ReputationBounds.MAX_TITLES,
                    ReputationNetwork::readComponent, "global titles");
            return new SnapshotS2C(communities, selected, globalTitles);
        }
    }

    private static void handleSnapshot(SnapshotS2C packet, IPayloadContext context) {
        try {
            ClientPacketHandler.acceptSnapshot(packet);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] snapshot handler failed; ignoring", t);
        }
    }

    /** Tells the client to open the standing screen, after a validated server-side interaction. */
    public record OpenScreenS2C() implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<OpenScreenS2C> TYPE =
                new CustomPacketPayload.Type<>(McaReputation.id("open_screen"));

        /** No fields, so nothing crosses the wire but the payload id itself. */
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenScreenS2C> STREAM_CODEC =
                StreamCodec.unit(new OpenScreenS2C());

        @Override
        public CustomPacketPayload.Type<OpenScreenS2C> type() {
            return TYPE;
        }
    }

    private static void handleOpenScreen(OpenScreenS2C packet, IPayloadContext context) {
        try {
            ClientPacketHandler.openScreen();
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] open-screen handler failed; ignoring", t);
        }
    }

    /**
     * One merged standing change, for the action bar (§28.3). {@code firstTime} rides along so the
     * client can tell an already-celebrated upward crossing (quiet chat line) from a first-time
     * milestone (which gets the toast instead and must not be announced twice).
     */
    public record ChangeS2C(Component communityName, int delta, Component tierName, boolean tierChanged,
                            boolean downward, boolean firstTime) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ChangeS2C> TYPE =
                new CustomPacketPayload.Type<>(McaReputation.id("change"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ChangeS2C> STREAM_CODEC =
                codec(ChangeS2C::write, ChangeS2C::read);

        @Override
        public CustomPacketPayload.Type<ChangeS2C> type() {
            return TYPE;
        }

        private static void write(ChangeS2C packet, RegistryFriendlyByteBuf buf) {
            writeComponent(buf, packet.communityName);
            buf.writeInt(packet.delta);
            writeComponent(buf, packet.tierName);
            buf.writeBoolean(packet.tierChanged);
            buf.writeBoolean(packet.downward);
            buf.writeBoolean(packet.firstTime);
        }

        private static ChangeS2C read(RegistryFriendlyByteBuf buf) {
            Component name = readComponent(buf);
            int delta = buf.readInt();
            Component tierName = readComponent(buf);
            boolean tierChanged = buf.readBoolean();
            boolean downward = buf.readBoolean();
            boolean firstTime = buf.readBoolean();
            return new ChangeS2C(name, delta, tierName, tierChanged, downward, firstTime);
        }
    }

    private static void handleChange(ChangeS2C packet, IPayloadContext context) {
        try {
            ClientPacketHandler.acceptChange(packet);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] change handler failed; ignoring", t);
        }
    }

    /** A first-time upward tier transition, worth a toast (§17.3). */
    public record TierToastS2C(Component communityName, Component tierName)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<TierToastS2C> TYPE =
                new CustomPacketPayload.Type<>(McaReputation.id("tier_toast"));

        public static final StreamCodec<RegistryFriendlyByteBuf, TierToastS2C> STREAM_CODEC =
                codec(TierToastS2C::write, TierToastS2C::read);

        @Override
        public CustomPacketPayload.Type<TierToastS2C> type() {
            return TYPE;
        }

        private static void write(TierToastS2C packet, RegistryFriendlyByteBuf buf) {
            writeComponent(buf, packet.communityName);
            writeComponent(buf, packet.tierName);
        }

        private static TierToastS2C read(RegistryFriendlyByteBuf buf) {
            return new TierToastS2C(readComponent(buf), readComponent(buf));
        }
    }

    private static void handleTierToast(TierToastS2C packet, IPayloadContext context) {
        try {
            ClientPacketHandler.acceptToast(packet);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] tier-toast handler failed; ignoring", t);
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
        // synthesise an empty selection so the screen can say "you are a stranger here".
        //
        // That is honest only when this community is genuinely the one to talk about -- the villager
        // the player clicked, the village they asked for, or the one they are standing in when they
        // have no standing anywhere. It is a lie when it displaces a record they do have, which is
        // what it used to do; SnapshotSelection is where that is now decided, and why.
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

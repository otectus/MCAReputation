package dev.otectus.mcareputation.network;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.api.McaReputationApi;
import dev.otectus.mcareputation.api.ReputationIncidentView;
import dev.otectus.mcareputation.api.ReputationSnapshot;
import dev.otectus.mcareputation.api.VillagerOpinion;
import dev.otectus.mcareputation.api.profile.ProfileAvailability;
import dev.otectus.mcareputation.api.profile.ProfileCoverage;
import dev.otectus.mcareputation.api.profile.ProfileQueryResult;
import dev.otectus.mcareputation.api.profile.ProfileSnapshot;
import dev.otectus.mcareputation.api.profile.VillagerProfileSnapshot;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityMetadata;
import dev.otectus.mcareputation.community.CommunityResolver;
import dev.otectus.mcareputation.compat.McaCompat;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.profile.ProfileMath;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.reputation.ProfileService;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import dev.otectus.mcareputation.reputation.ReputationPolicy;
import dev.otectus.mcareputation.reputation.ReputationService;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import dev.otectus.mcareputation.state.ReputationSavedData;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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
 * are answered at most once per 10 ticks per player, so spamming the button costs the server one map
 * write rather than a ledger walk; a request inside that window is deferred to its end rather than
 * dropped (see {@link RequestPacing}).
 *
 * <h2>What the NeoForge port changed</h2>
 *
 * <p>The five packets are the same five packets, but they are now named {@link CustomPacketPayload}s
 * registered through {@link PayloadRegistrar} instead of numerically-discriminated messages on a
 * {@code SimpleChannel}. The protocol version was bumped to {@code 4} at the port to make the
 * incompatible wire revision auditable, even though a 1.20.1 client could never reach a 1.21.1
 * server anyway; it is at {@code 6} as of 0.6.0.
 *
 * <p>0.6.0's profile subpayload (§18.3) rides on {@link SelectedDetail} as two optional panes and is
 * bounded twice: per field, and then by a byte budget measured on the real encoding in
 * {@link SnapshotCodec}. Its authored labels cross the wire as resolved {@link Component}s through
 * {@link ComponentSerialization#STREAM_CODEC}, for the same reason titles and tier names always
 * have — a dedicated-server client holds the content it shipped with, not the content the world is
 * running.
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
     * encoding all changed with the loader, so nothing on the old protocol could talk to this. Bumped
     * again for 0.4.0, which added a field to the snapshot, again for 0.5.0's paging counters, and
     * again here for 0.6.0's profile subpayload (§18.3) and its request stamp.
     *
     * <p>The registrar compares this string by equality and nothing else, so leaving it at
     * {@code "5"} would let a 0.5.0 client complete the handshake with a 0.6.0 server and then
     * mis-read every snapshot — which is the exact failure a protocol number exists to prevent. That
     * is also why appending a field is safe <em>within</em> a version and a bump is mandatory across
     * one.
     *
     * <p>This channel runs one ahead of the Forge one, as it has since the port: Forge
     * {@code "4"} → {@code "5"} is NeoForge {@code "5"} → {@code "6"}. The two lineages are not
     * comparable and were never wire-compatible.
     */
    private static final String PROTOCOL_VERSION = "6";

    /** §27.2: at most one snapshot answer per player per this many ticks. */
    private static final int REQUEST_COOLDOWN_TICKS = 10;

    /** §27.2: a context villager must be within this many blocks to be a valid interaction subject. */
    private static final double MAX_CONTEXT_DISTANCE = 12.0D;

    /** Bounds for the short id strings that ride along inside payloads. */
    private static final int MAX_TIER_ID_LENGTH = 48;
    private static final int MAX_STATUS_LENGTH = 32;
    private static final int MAX_SEVERITY_LENGTH = 32;

    private static final RequestPacing<UUID, RequestSnapshotC2S> PACING =
            new RequestPacing<>(REQUEST_COOLDOWN_TICKS);

    /** The last snapshot each player was sent, for {@code /mcareputation debug standing}. */
    private static final Map<UUID, SentSnapshot> LAST_SENT = new HashMap<>();

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
        sendSnapshot(player, buildSnapshot(player, Optional.ofNullable(community), gameTime), gameTime);
        sendTo(player, new OpenScreenS2C());
    }

    /**
     * What the server last sent one player's standing screen, as the debug command prints it: the
     * difference between "the stored value is wrong" and "the client is showing something older than
     * what it was sent" is only visible from here.
     */
    public record SentSnapshot(long gameTime, int requestId, int totalCommunities,
                               Optional<CommunityKey> community, int score, String tierId,
                               int tierThreshold, Optional<String> nextTierId, int nextThreshold) {

        static SentSnapshot of(SnapshotS2C packet, long gameTime) {
            Optional<SelectedDetail> detail = packet.selected();
            return new SentSnapshot(gameTime, packet.requestId(), packet.totalCommunities(),
                    detail.map(SelectedDetail::key), detail.map(SelectedDetail::score).orElse(0),
                    detail.map(SelectedDetail::tierId).orElse(""),
                    detail.map(SelectedDetail::tierThreshold).orElse(0),
                    detail.flatMap(SelectedDetail::nextTierId),
                    detail.map(SelectedDetail::nextThreshold).orElse(0));
        }
    }

    /** The last snapshot sent to this player in this server session, if any. */
    public static Optional<SentSnapshot> lastSent(UUID playerId) {
        return Optional.ofNullable(LAST_SENT.get(playerId));
    }

    private static void sendSnapshot(ServerPlayer player, SnapshotS2C packet, long gameTime) {
        LAST_SENT.put(player.getUUID(), SentSnapshot.of(packet, gameTime));
        sendTo(player, packet);
    }

    /**
     * Answers every snapshot request whose pacing window has now closed. Called at the end of each
     * server tick; returns before reading anything when nothing is parked, which is the steady state.
     */
    public static void flushDeferredRequests(MinecraftServer server) {
        if (!PACING.hasDeferred()) {
            return;
        }
        long gameTime = server.overworld().getGameTime();
        for (Map.Entry<UUID, RequestSnapshotC2S> due : PACING.due(gameTime)) {
            ServerPlayer player = server.getPlayerList().getPlayer(due.getKey());
            if (player != null) {
                // Contained as the immediate path's handler is: this runs on the server tick, where
                // an escaping exception would take the tick loop down with it.
                try {
                    answer(player, due.getValue(), gameTime);
                } catch (Throwable t) {
                    McaReputation.LOGGER.debug("[MCA: Reputation] deferred snapshot request failed; "
                            + "ignoring", t);
                }
            }
        }
    }

    /** Clears per-player request state on disconnect so the maps cannot grow across sessions. */
    public static void forget(UUID playerId) {
        PACING.forget(playerId);
        LAST_SENT.remove(playerId);
    }

    /** Clears every request stamp on server stop; the next world in this JVM starts clean. */
    public static void clearAll() {
        PACING.clear();
        LAST_SENT.clear();
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
     *
     * <p>{@code requestId} is the client's own generation stamp, echoed back on the reply and used for
     * nothing else (§18.3). The server never trusts it, compares it or stores it: it exists so a
     * reply that arrives after the player has switched village or villager can be recognised as
     * answering a question nobody is asking any more, instead of applying a profile to the wrong
     * selection. Zero means "not asked for" and is reserved for a server-pushed snapshot.
     */
    public record RequestSnapshotC2S(int contextEntityId, Optional<CommunityKey> requestedCommunity,
                                     int page, int requestId) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<RequestSnapshotC2S> TYPE =
                new CustomPacketPayload.Type<>(McaReputation.id("request_snapshot"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestSnapshotC2S> STREAM_CODEC =
                codec(RequestSnapshotC2S::write, RequestSnapshotC2S::read);

        /** The page-less form: page 0, which is what every non-paging caller wants. */
        public RequestSnapshotC2S(int contextEntityId, Optional<CommunityKey> requestedCommunity) {
            this(contextEntityId, requestedCommunity, 0, 0);
        }

        public RequestSnapshotC2S(int contextEntityId, Optional<CommunityKey> requestedCommunity,
                                  int page) {
            this(contextEntityId, requestedCommunity, page, 0);
        }

        @Override
        public CustomPacketPayload.Type<RequestSnapshotC2S> type() {
            return TYPE;
        }

        private static void write(RequestSnapshotC2S packet, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(packet.contextEntityId);
            writeOptional(buf, packet.requestedCommunity, (b, key) -> key.write(b));
            // Written last, so every field a reader already knew keeps the offset it had.
            buf.writeVarInt(Math.max(0, packet.page));
            buf.writeVarInt(Math.max(0, packet.requestId));
        }

        private static RequestSnapshotC2S read(RegistryFriendlyByteBuf buf) {
            int entityId = buf.readVarInt();
            Optional<CommunityKey> community = readOptional(buf, CommunityKey::read);
            // A hostile page index is clamped here and again against the real page count, so it can
            // never index anything; the client's claim carries no weight of its own (§27.2).
            int page = Math.max(0, buf.readVarInt());
            int requestId = Math.max(0, buf.readVarInt());
            return new RequestSnapshotC2S(entityId, community, page, requestId);
        }
    }

    private static void handleRequestSnapshot(RequestSnapshotC2S packet, IPayloadContext context) {
        try {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            long gameTime = player.server.overworld().getGameTime();
            if (!PACING.offer(player.getUUID(), packet, gameTime)) {
                // Inside the window: parked, newest wins, and answered by flushDeferredRequests once
                // the window closes. Dropping it here is what used to leave the screen on the previous
                // village whenever jitter or lag packed two requests together.
                return;
            }
            answer(player, packet, gameTime);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] snapshot request handler failed; ignoring", t);
        }
    }

    /** Builds and sends the reply to one request that pacing has already let through. */
    private static void answer(ServerPlayer player, RequestSnapshotC2S packet, long gameTime) {
        Optional<CommunityKey> selected = resolveSelection(player, packet, gameTime);
        // One validation of the one client-supplied value, shared by both context answers: a
        // villager good enough to have an opinion and a villager good enough to have read the
        // player must be the same villager, or the two panes describe different people.
        Optional<Entity> villager = contextEntity(player, packet.contextEntityId());
        sendSnapshot(player, buildSnapshot(player, selected, gameTime,
                contextOpinion(player, villager, selected),
                contextProfile(player, villager, selected),
                packet.page(), packet.requestId()), gameTime);
    }

    /**
     * Decides which community the reply should detail, trusting nothing the client said about
     * villages. A named community is honoured only if the player already has a record for it.
     */
    private static Optional<CommunityKey> resolveSelection(ServerPlayer player, RequestSnapshotC2S packet,
                                                           long gameTime) {
        if (packet.requestedCommunity().isPresent()) {
            CommunityKey requested = packet.requestedCommunity().get();
            boolean known = ReputationSavedData.get(player.server)
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
        return ReputationService.unpromptedCommunity(player.server, player.getUUID(), here);
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

    /**
     * One line of the deeds list.
     *
     * <p>{@code contribution} is what this deed counts for <em>now</em>; {@code baseDelta} is what it
     * counted for when it happened. The screen shows both when they differ, because a deed that has
     * been made right or has faded is not the penalty it was, and presenting the original figure as
     * the current one is the same lie in the other direction (§5 F16 row 1).
     */
    public record IncidentSummary(UUID id, ResourceLocation type, Component display, long ageTicks,
                                  int contribution, String status, String severity, boolean pinned,
                                  IncidentVisibility visibility, int baseDelta, boolean decays,
                                  boolean superseded) {

        static void write(RegistryFriendlyByteBuf buf, IncidentSummary summary) {
            buf.writeUUID(summary.id);
            buf.writeResourceLocation(summary.type);
            writeComponent(buf, summary.display);
            buf.writeVarLong(Math.max(0L, summary.ageTicks));
            buf.writeInt(summary.contribution);
            buf.writeUtf(summary.status, MAX_STATUS_LENGTH);
            buf.writeUtf(summary.severity, MAX_SEVERITY_LENGTH);
            buf.writeBoolean(summary.pinned);
            // Appended, so every field a reader already knew keeps the offset it had.
            buf.writeEnum(summary.visibility);
            buf.writeInt(summary.baseDelta);
            buf.writeBoolean(summary.decays);
            buf.writeBoolean(summary.superseded);
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
            IncidentVisibility visibility = buf.readEnum(IncidentVisibility.class);
            int baseDelta = buf.readInt();
            boolean decays = buf.readBoolean();
            boolean superseded = buf.readBoolean();
            return new IncidentSummary(id, type, display, age, contribution, status, severity, pinned,
                    visibility, baseDelta, decays, superseded);
        }
    }

    /**
     * What the villager the player is looking at personally makes of them (spec 19.3).
     *
     * <p>Present only when the request named a villager and the server agreed it was one. The tier
     * name is resolved server-side for the same reason titles are: a dedicated-server client holds
     * the ladder it shipped with, not the one the server's datapack loaded.
     */
    public record OpinionSummary(Component villagerName, Component tierName,
                                 VillagerOpinion.OpinionBasis basis) {

        public static void write(RegistryFriendlyByteBuf buf, OpinionSummary summary) {
            writeComponent(buf, summary.villagerName);
            writeComponent(buf, summary.tierName);
            buf.writeEnum(summary.basis);
        }

        public static OpinionSummary read(RegistryFriendlyByteBuf buf) {
            Component villagerName = readComponent(buf);
            Component tierName = readComponent(buf);
            return new OpinionSummary(villagerName, tierName,
                    buf.readEnum(VillagerOpinion.OpinionBasis.class));
        }
    }

    /**
     * One facet line in the profile pane: what the village knows the player for on this axis.
     *
     * <p>Both the facet's name and the word its value earns arrive resolved (see
     * {@link ProfileProjection}). The two evidence counts travel separately rather than as one total
     * because a facet held up by three deeds and pulled down by two is a different fact from one held
     * up by one deed — and §28.4 forbids the difference being carried by colour.
     */
    public record FacetSummary(ResourceLocation facet, Component name, Optional<Component> label,
                               int value, int rangeMin, int rangeMax, int supportingEvidence,
                               int opposingEvidence, boolean labelEligible) {

        public static void write(RegistryFriendlyByteBuf buf, FacetSummary summary) {
            buf.writeResourceLocation(summary.facet);
            writeComponent(buf, summary.name);
            writeOptional(buf, summary.label, ReputationNetwork::writeComponent);
            buf.writeInt(summary.value);
            buf.writeInt(summary.rangeMin);
            buf.writeInt(summary.rangeMax);
            buf.writeVarInt(Math.max(0, summary.supportingEvidence));
            buf.writeVarInt(Math.max(0, summary.opposingEvidence));
            buf.writeBoolean(summary.labelEligible);
        }

        /**
         * Every number is clamped to a range this build can display before it reaches a layout.
         *
         * <p>Not defence against the server — it is the authority here — but against a corrupt or
         * forged frame: a facet value of two billion would be drawn as a bar off the edge of the
         * panel, and an evidence count of {@code Integer.MAX_VALUE} would be read aloud by a narrator.
         */
        public static FacetSummary read(RegistryFriendlyByteBuf buf) {
            ResourceLocation facet = buf.readResourceLocation();
            Component name = readComponent(buf);
            Optional<Component> label = readOptional(buf, ReputationNetwork::readComponent);
            int points = ProfileMath.MAX_FACET_POINTS;
            int value = ProfileMath.clamp(buf.readInt(), -points, points);
            int rangeMin = ProfileMath.clamp(buf.readInt(), -points, 0);
            int rangeMax = ProfileMath.clamp(buf.readInt(), 0, points);
            int supporting = SnapshotCodec.boundedCount(buf.readVarInt());
            int opposing = SnapshotCodec.boundedCount(buf.readVarInt());
            boolean labelEligible = buf.readBoolean();
            return new FacetSummary(facet, name, label, value, rangeMin, rangeMax, supporting,
                    opposing, labelEligible);
        }
    }

    /**
     * The bounded profile subpayload for one selection (§18.3).
     *
     * <p>Carries the answer <em>and</em> the reason there is not one. §18.2 asks for genuine unknown,
     * temporarily unavailable, partial legacy history, migrating and read-only to render distinctly,
     * and none of those is expressible by an absent pane: an empty profile and an unreadable one look
     * identical to a player, and only one of them means "nobody here knows you".
     *
     * <p>Bounded to {@link ReputationBounds#MAX_SYNCED_DOMINANT_TRAITS} traits and
     * {@link ReputationBounds#MAX_SYNCED_FACET_DETAILS} details per pane, both validated before
     * allocation on decode, with {@link ReputationBounds#MAX_PROFILE_PAYLOAD_BYTES} as the second,
     * byte-level bound that authored text cannot argue with.
     */
    public record ProfileSummary(ProfileAvailability availability, ProfileCoverage coverage,
                                 boolean readOnlyStore, Optional<String> reason, int recognition,
                                 String recognitionTierId, Component recognitionTierName,
                                 int recognitionEvidence, List<Component> dominantTraits,
                                 List<FacetSummary> details, long profileRevision,
                                 long definitionGeneration) {

        /** The reason token a summary carries when its own authored text blew the byte budget. */
        public static final String OVERSIZE_REASON = "profile_payload_too_large";

        public ProfileSummary {
            availability = availability == null ? ProfileAvailability.ERROR : availability;
            coverage = coverage == null ? ProfileCoverage.MIGRATING : coverage;
            reason = reason == null ? Optional.<String>empty() : reason;
            recognitionTierId = recognitionTierId == null ? "" : recognitionTierId;
            recognitionTierName = recognitionTierName == null ? Component.empty() : recognitionTierName;
            dominantTraits = dominantTraits == null ? List.of() : List.copyOf(dominantTraits);
            details = details == null ? List.of() : List.copyOf(details);
        }

        /**
         * The same availability and coverage with every variable-length field dropped.
         *
         * <p>Provably small: what is left is two enums, a boolean, a bounded token, four integers and
         * a component built from the 48-character tier id. A pack cannot make this exceed the budget,
         * which is what makes it a safe answer to a payload that did.
         */
        public ProfileSummary degraded() {
            return new ProfileSummary(availability, coverage, readOnlyStore,
                    Optional.of(OVERSIZE_REASON), recognition, recognitionTierId,
                    recognitionTierId.isEmpty() ? Component.empty()
                            : Component.literal(recognitionTierId),
                    recognitionEvidence, List.of(), List.of(), profileRevision, definitionGeneration);
        }

        public static void write(RegistryFriendlyByteBuf buf, ProfileSummary summary) {
            buf.writeEnum(summary.availability);
            buf.writeEnum(summary.coverage);
            buf.writeBoolean(summary.readOnlyStore);
            writeOptional(buf, summary.reason,
                    (b, value) -> b.writeUtf(value, ProfileQueryResult.MAX_REASON_LENGTH));
            buf.writeVarInt(Math.max(0, summary.recognition));
            buf.writeUtf(summary.recognitionTierId, SnapshotCodec.TIER_ID_LENGTH);
            writeComponent(buf, summary.recognitionTierName);
            buf.writeVarInt(Math.max(0, summary.recognitionEvidence));
            writeBoundedList(buf, summary.dominantTraits, ReputationBounds.MAX_SYNCED_DOMINANT_TRAITS,
                    ReputationNetwork::writeComponent);
            writeBoundedList(buf, summary.details, ReputationBounds.MAX_SYNCED_FACET_DETAILS,
                    FacetSummary::write);
            buf.writeVarLong(Math.max(0L, summary.profileRevision));
            buf.writeVarLong(Math.max(0L, summary.definitionGeneration));
        }

        public static ProfileSummary read(RegistryFriendlyByteBuf buf) {
            ProfileAvailability availability = buf.readEnum(ProfileAvailability.class);
            ProfileCoverage coverage = buf.readEnum(ProfileCoverage.class);
            boolean readOnly = buf.readBoolean();
            Optional<String> reason =
                    readOptional(buf, b -> b.readUtf(ProfileQueryResult.MAX_REASON_LENGTH));
            int recognition = Math.min(ProfileMath.MAX_RECOGNITION, Math.max(0, buf.readVarInt()));
            String tierId = buf.readUtf(SnapshotCodec.TIER_ID_LENGTH);
            Component tierName = readComponent(buf);
            int evidence = SnapshotCodec.boundedCount(buf.readVarInt());
            // Both lengths are checked before a list is allocated: truncating at the encoder bounds a
            // reply this server built, and says nothing at all about a frame somebody else wrote.
            List<Component> traits = readBoundedList(buf, ReputationBounds.MAX_SYNCED_DOMINANT_TRAITS,
                    ReputationNetwork::readComponent, "dominant traits");
            List<FacetSummary> details = readBoundedList(buf, ReputationBounds.MAX_SYNCED_FACET_DETAILS,
                    FacetSummary::read, "facet details");
            long profileRevision = Math.max(0L, buf.readVarLong());
            long generation = Math.max(0L, buf.readVarLong());
            return new ProfileSummary(availability, coverage, readOnly, reason, recognition, tierId,
                    tierName, evidence, traits, details, profileRevision, generation);
        }
    }

    /**
     * What the villager the player is looking at knows them for, and the opinion that adds up to
     * (§13.2, §13.4).
     *
     * <p>{@code villagerId} is the observer identity §18.3 asks to travel with the reply: together
     * with the selected community on the detail and the echoed request id on the snapshot, it makes a
     * late reply about a <em>different</em> villager recognisable rather than plausible.
     *
     * <p>The three opinion integers are the bounded explanation, not decoration: the base opinion is
     * what this observer's knowledge was worth before their reading of it, the adjustment is the one
     * capped facet term, and the final opinion is the clamped result. Printing all three is what lets
     * a player see that a capped term was capped.
     */
    public record VillagerProfileSummary(UUID villagerId, Component villagerName, ProfileSummary known,
                                         int baseOpinion, int facetAdjustment, int finalOpinion,
                                         Component opinionTierName,
                                         VillagerProfileSnapshot.TraitBasis traitBasis,
                                         int involvedCount, int witnessedCount, int hearsayCount) {

        public VillagerProfileSummary {
            villagerName = villagerName == null ? Component.empty() : villagerName;
            opinionTierName = opinionTierName == null ? Component.empty() : opinionTierName;
            traitBasis = traitBasis == null
                    ? VillagerProfileSnapshot.TraitBasis.NEUTRAL_DEFAULT
                    : traitBasis;
        }

        /** Known deeds behind this view, however this observer came to know them. */
        public int knownIncidents() {
            return involvedCount + witnessedCount + hearsayCount;
        }

        /** As {@link ProfileSummary#degraded()}, applied to the observer's own knowledge. */
        public VillagerProfileSummary degraded() {
            return new VillagerProfileSummary(villagerId, villagerName, known.degraded(), baseOpinion,
                    facetAdjustment, finalOpinion, opinionTierName, traitBasis, involvedCount,
                    witnessedCount, hearsayCount);
        }

        public static void write(RegistryFriendlyByteBuf buf, VillagerProfileSummary summary) {
            buf.writeUUID(summary.villagerId);
            writeComponent(buf, summary.villagerName);
            ProfileSummary.write(buf, summary.known);
            buf.writeInt(summary.baseOpinion);
            buf.writeInt(summary.facetAdjustment);
            buf.writeInt(summary.finalOpinion);
            writeComponent(buf, summary.opinionTierName);
            buf.writeEnum(summary.traitBasis);
            buf.writeVarInt(Math.max(0, summary.involvedCount));
            buf.writeVarInt(Math.max(0, summary.witnessedCount));
            buf.writeVarInt(Math.max(0, summary.hearsayCount));
        }

        public static VillagerProfileSummary read(RegistryFriendlyByteBuf buf) {
            UUID villagerId = buf.readUUID();
            Component villagerName = readComponent(buf);
            ProfileSummary known = ProfileSummary.read(buf);
            int base = buf.readInt();
            int adjustment = buf.readInt();
            int finalOpinion = buf.readInt();
            Component tierName = readComponent(buf);
            VillagerProfileSnapshot.TraitBasis basis =
                    buf.readEnum(VillagerProfileSnapshot.TraitBasis.class);
            int involved = SnapshotCodec.boundedCount(buf.readVarInt());
            int witnessed = SnapshotCodec.boundedCount(buf.readVarInt());
            int hearsay = SnapshotCodec.boundedCount(buf.readVarInt());
            return new VillagerProfileSummary(villagerId, villagerName, known, base, adjustment,
                    finalOpinion, tierName, basis, involved, witnessed, hearsay);
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
                                 List<IncidentSummary> incidents, int totalIncidents,
                                 Optional<OpinionSummary> opinion, Optional<ProfileSummary> profile,
                                 Optional<VillagerProfileSummary> villagerProfile) {

        /** The 0.5.0 shape, for callers that carry no profile: the two panes are simply absent. */
        public SelectedDetail(CommunityKey key, String name, int score, int baseline, String tierId,
                              Component tierName, Optional<Component> tierDescription,
                              int tierThreshold, Optional<String> nextTierId,
                              Optional<Component> nextTierName, int nextThreshold,
                              List<Component> titles, List<IncidentSummary> incidents,
                              int totalIncidents, Optional<OpinionSummary> opinion) {
            this(key, name, score, baseline, tierId, tierName, tierDescription, tierThreshold,
                    nextTierId, nextTierName, nextThreshold, titles, incidents, totalIncidents,
                    opinion, Optional.empty(), Optional.empty());
        }

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
            // Written last, so every field a reader already knew keeps the offset it had.
            writeOptional(buf, detail.opinion, OpinionSummary::write);
            SnapshotCodec.writeWithinBudget(buf, detail.profile, detail.villagerProfile,
                    ProfileSummary::write, VillagerProfileSummary::write, ProfileSummary::degraded,
                    VillagerProfileSummary::degraded, ReputationBounds.MAX_PROFILE_PAYLOAD_BYTES);
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
            Optional<OpinionSummary> opinion = readOptional(buf, OpinionSummary::read);
            Optional<ProfileSummary> profile = readOptional(buf, ProfileSummary::read);
            Optional<VillagerProfileSummary> villagerProfile =
                    readOptional(buf, VillagerProfileSummary::read);
            return new SelectedDetail(key, name, score, baseline, tierId, tierName, tierDescription,
                    tierThreshold, nextTierId, nextTierName, nextThreshold, titles, incidents, total,
                    opinion, profile, villagerProfile);
        }
    }

    /**
     * The whole reply: every community the player is known in, plus the detail of the selected one.
     *
     * <p>Bounded on both sides (§27.3): at most {@link ReputationBounds#MAX_SYNCED_COMMUNITIES}
     * communities <em>per page</em> and {@link ReputationBounds#MAX_SYNCED_INCIDENTS} incident lines,
     * so a player with a maximal ledger cannot produce a packet large enough to disconnect them, and a
     * hostile server cannot make a client allocate an unbounded one.
     *
     * <p>{@code totalCommunities} is the true count, not the page's: the screen must be able to say
     * "64 of 210" rather than silently omitting the rest (§5 F16 row 3). The selected detail is
     * carried whatever page this is, so paging never moves the selection.
     */
    public record SnapshotS2C(List<CommunitySummary> communities, Optional<SelectedDetail> selected,
                              List<Component> globalTitles, int page, int pageCount,
                              int totalCommunities, int requestId) implements CustomPacketPayload {

        /** A reply to nobody's question: a server push, which no client may discard as stale. */
        public static final int UNSOLICITED = 0;

        public static final CustomPacketPayload.Type<SnapshotS2C> TYPE =
                new CustomPacketPayload.Type<>(McaReputation.id("snapshot"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotS2C> STREAM_CODEC =
                codec(SnapshotS2C::write, SnapshotS2C::read);

        /** The single-page form, for callers that never page. */
        public SnapshotS2C(List<CommunitySummary> communities, Optional<SelectedDetail> selected,
                           List<Component> globalTitles) {
            this(communities, selected, globalTitles, 0, 1, communities.size(), UNSOLICITED);
        }

        /** The paged form without a request stamp: a push that happens to name a page. */
        public SnapshotS2C(List<CommunitySummary> communities, Optional<SelectedDetail> selected,
                           List<Component> globalTitles, int page, int pageCount,
                           int totalCommunities) {
            this(communities, selected, globalTitles, page, pageCount, totalCommunities, UNSOLICITED);
        }

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
            // Appended, so every field a reader already knew keeps the offset it had.
            buf.writeVarInt(Math.max(0, packet.page));
            buf.writeVarInt(Math.max(1, packet.pageCount));
            buf.writeVarInt(Math.max(0, packet.totalCommunities));
            // The client's own stamp, echoed unread: see RequestSnapshotC2S#requestId.
            buf.writeVarInt(Math.max(0, packet.requestId));
        }

        private static SnapshotS2C read(RegistryFriendlyByteBuf buf) {
            List<CommunitySummary> communities = readBoundedList(buf,
                    ReputationBounds.MAX_SYNCED_COMMUNITIES, CommunitySummary::read, "communities");
            Optional<SelectedDetail> selected = readOptional(buf, SelectedDetail::read);
            List<Component> globalTitles = readBoundedList(buf, ReputationBounds.MAX_TITLES,
                    ReputationNetwork::readComponent, "global titles");
            int page = buf.readVarInt();
            int pageCount = Math.max(1, buf.readVarInt());
            int total = buf.readVarInt();
            int requestId = Math.max(0, buf.readVarInt());
            return new SnapshotS2C(communities, selected, globalTitles,
                    SnapshotPaging.clampPage(page, pageCount), pageCount, total, requestId);
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
        return buildSnapshot(player, selected, gameTime, Optional.empty());
    }

    /**
     * As above, carrying the opinion of the villager the request named — the one part of the reply
     * that depends on <em>who</em> was asked rather than only on which community was selected.
     */
    public static SnapshotS2C buildSnapshot(ServerPlayer player, Optional<CommunityKey> selected,
                                            long gameTime, Optional<OpinionSummary> opinion) {
        return buildSnapshot(player, selected, gameTime, opinion, 0);
    }

    /**
     * As above, for one page of the community list.
     *
     * <p>The page bounds the <b>summary list only</b>. The selected community's detail is built and
     * sent whichever page was asked for, even when that community is not on it — paging is navigation
     * through a list, never a way to change what the screen is looking at (DIAGNOSIS.md §2 hop 7b).
     */
    public static SnapshotS2C buildSnapshot(ServerPlayer player, Optional<CommunityKey> selected,
                                            long gameTime, Optional<OpinionSummary> opinion, int page) {
        return buildSnapshot(player, selected, gameTime, opinion, Optional.empty(), page,
                SnapshotS2C.UNSOLICITED);
    }

    /**
     * As above, with the observer's profile pane and the client's own request stamp (§18.3).
     *
     * <p>The stamp is echoed and never interpreted: the client generated it and the client is the only
     * party that can tell whether the question it marks is still the one on screen.
     */
    public static SnapshotS2C buildSnapshot(ServerPlayer player, Optional<CommunityKey> selected,
                                            long gameTime, Optional<OpinionSummary> opinion,
                                            Optional<VillagerProfileSummary> villagerProfile, int page,
                                            int requestId) {
        List<ReputationSnapshot> all = ReputationService.knownCommunities(player.server, player.getUUID(),
                gameTime);
        int perPage = ReputationBounds.MAX_SYNCED_COMMUNITIES;
        int total = all.size();
        int pageCount = SnapshotPaging.pageCount(total, perPage);
        int clampedPage = SnapshotPaging.clampPage(page, pageCount);
        List<CommunitySummary> summaries = new ArrayList<>();
        for (ReputationSnapshot snapshot : all.subList(SnapshotPaging.pageStart(clampedPage, perPage, total),
                SnapshotPaging.pageEnd(clampedPage, perPage, total))) {
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
        // The profile pane is built for whichever community the detail is about -- including the
        // synthesised stranger selection below, where "nobody here knows you" is a true profile
        // answer rather than a missing one.
        Optional<ProfileSummary> profile = selected.map(key -> profileSummary(player, key));
        Optional<SelectedDetail> selectedDetail = detail
                .map(snapshot -> toDetail(player, snapshot, opinion, profile, villagerProfile));
        if (selectedDetail.isEmpty() && selected.isPresent()) {
            selectedDetail = Optional.of(emptyDetail(player, selected.get(), gameTime, opinion, profile,
                    villagerProfile));
        }

        // Resolved server-side: the client's Titles registry is empty on a dedicated server.
        List<Component> globalTitles = dev.otectus.mcareputation.reputation.TitleService
                .globalTitles(player.server, player.getUUID()).stream()
                .map(ReputationNetwork::resolveTitleName)
                .toList();
        return new SnapshotS2C(summaries, selectedDetail, globalTitles, clampedPage, pageCount, total,
                requestId);
    }

    /**
     * The selected community's profile pane, read through the canonical gate and nothing else.
     *
     * <p>{@link ProfileService} is entered with a live read intent, not {@code INSPECT}: this is the
     * screen a player is looking at, and the same evaluation time has already reconciled the standing
     * beside it, so the second pass ages nothing further. Every failure has a shape — disabled,
     * unpublished, unresolved, read-only store, incomplete history — and the shape travels, because
     * §18.2 forbids five different reasons rendering as one empty pane.
     *
     * <p>Evaluated through the public API rather than at this reply's own {@code gameTime}, and the
     * two are the same instant: every caller of {@code buildSnapshot} takes its time from
     * {@code server.overworld().getGameTime()}, which is exactly what the API reads. Going through the
     * API also means the observer pane beside it is built by the same entry point a companion would
     * use, so the screen cannot see a profile no API consumer could.
     */
    private static ProfileSummary profileSummary(ServerPlayer player, CommunityKey community) {
        ReputationSavedData data = ReputationSavedData.get(player.server);
        ProfileQueryResult<ProfileSnapshot> result = McaReputationApi
                .getProfileDetailed(player.server, player.getUUID(), community);
        return ProfileProjection.summary(result, ProfileRegistryBundle.current(), data.isReadOnly());
    }

    /**
     * The one validation of the one client-supplied value (§27.2).
     *
     * <p>Same dimension, a living MCA villager, within reach. Extracted so the opinion line and the
     * observer profile pane cannot drift apart about what "the villager I am looking at" means, and
     * so neither of them can widen it into "any villager on the server". Nothing here loads a chunk:
     * an entity the level does not already hold is simply not a context.
     */
    private static Optional<Entity> contextEntity(ServerPlayer player, int contextEntityId) {
        if (contextEntityId <= 0 || !(player.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        Entity entity = level.getEntity(contextEntityId);
        boolean valid = entity != null
                && entity.level().dimension().equals(player.level().dimension())
                && McaCompat.isLivingMcaVillager(entity)
                && entity.distanceTo(player) <= MAX_CONTEXT_DISTANCE;
        return valid ? Optional.of(entity) : Optional.empty();
    }

    /**
     * What the named villager knows the player for, for the community the server already selected.
     *
     * <p>Asked about the <b>selected</b> community rather than the villager's own, so the pane and the
     * detail beside it are about one village. A speaker the profile service cannot answer for sends no
     * pane at all: §13.3 forbids widening an unresolved speaker into the community view, and a pane
     * showing the village's traits under a villager's name would be exactly that.
     */
    private static Optional<VillagerProfileSummary> contextProfile(ServerPlayer player,
                                                                   Optional<Entity> context,
                                                                   Optional<CommunityKey> selected) {
        if (context.isEmpty() || selected.isEmpty()) {
            return Optional.empty();
        }
        ReputationPolicy policy = McaReputationConfig.snapshot();
        if (!ProfileService.live(policy)) {
            return Optional.empty();
        }
        Entity villager = context.get();
        ProfileQueryResult<VillagerProfileSnapshot> view = McaReputationApi
                .getVillagerProfileDetailed(player.server, player.getUUID(), villager.getUUID(),
                        selected.get());
        Component opinionTier = view.value()
                .map(snapshot -> ReputationTiers.getDefault().tierFor(snapshot.finalOpinion()).name())
                .orElse(Component.empty());
        return ProfileProjection.villagerSummary(view, ProfileRegistryBundle.current(),
                villager.getUUID(), villager.getName(), opinionTier,
                ReputationSavedData.get(player.server).isReadOnly());
    }

    private static Component resolveTitleName(ResourceLocation titleId) {
        return dev.otectus.mcareputation.reputation.Titles.getOrUnknown(titleId).name();
    }

    private static SelectedDetail toDetail(ServerPlayer player, ReputationSnapshot snapshot,
                                           Optional<OpinionSummary> opinion,
                                           Optional<ProfileSummary> profile,
                                           Optional<VillagerProfileSummary> villagerProfile) {
        List<IncidentSummary> incidents = new ArrayList<>();
        for (ReputationIncidentView view : snapshot.incidents()) {
            if (incidents.size() >= ReputationBounds.MAX_SYNCED_INCIDENTS) {
                break;
            }
            // Whether ordinary fading applies is a property of the definition, not of the record, and
            // supersession is read from the stored record: the view carries neither.
            boolean decays = IncidentRegistry.getOrUnknown(view.type()).decay().decays();
            boolean superseded = ReputationService
                    .incident(player.server, player.getUUID(), snapshot.community(), view.id())
                    .map(record -> record.isSuperseded())
                    .orElse(false);
            incidents.add(new IncidentSummary(view.id(), view.type(), view.display(), view.ageTicks(),
                    view.currentContribution(), view.status().jsonName(), view.severity().jsonName(),
                    view.pinned(), view.visibility(), view.baseDelta(), decays, superseded));
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
                snapshot.totalIncidentCount(),
                opinion,
                profile,
                villagerProfile);
    }

    /** The "you have no history here yet" selection, built without creating a saved record. */
    private static SelectedDetail emptyDetail(ServerPlayer player, CommunityKey key, long gameTime,
                                              Optional<OpinionSummary> opinion,
                                              Optional<ProfileSummary> profile,
                                              Optional<VillagerProfileSummary> villagerProfile) {
        var ladder = dev.otectus.mcareputation.reputation.ReputationTiers.getDefault();
        var tier = ladder.tierFor(0);
        var next = ladder.nextTier(0);
        String name = player.level() instanceof ServerLevel level
                ? CommunityResolver.readMetadata(level, key, gameTime).name()
                : "";
        return new SelectedDetail(key, name, 0, 0, tier.id(), tier.name(), tier.description(),
                tier.threshold(), next.map(t -> t.id()), next.map(t -> t.name()),
                next.map(t -> t.threshold()).orElse(tier.threshold()),
                List.of(), List.of(), 0, opinion, profile, villagerProfile);
    }

    /**
     * The opinion of the villager the client named, for the community the server has already selected.
     *
     * <p>The entity is validated exactly as {@code resolveSelection} validates it — same dimension, a
     * living MCA villager, within reach — because it is still the one client-supplied value here, and
     * "the villager I am looking at" must not be allowed to become "any villager on the server".
     */
    private static Optional<OpinionSummary> contextOpinion(ServerPlayer player,
                                                           Optional<Entity> context,
                                                           Optional<CommunityKey> selected) {
        if (context.isEmpty() || selected.isEmpty() || !McaReputationConfig.villagerOpinionEnabled()) {
            return Optional.empty();
        }
        Entity entity = context.get();
        return McaReputationApi.getVillagerOpinion(player.server, player.getUUID(), entity.getUUID(),
                        selected.get())
                .map(opinion -> new OpinionSummary(entity.getName(),
                        ReputationTiers.getDefault().tierFor(opinion.opinion()).name(),
                        opinion.basis()));
    }
}

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

    /**
     * Bumped 4 → 5 for 0.6.0's profile subpayload (§18.3).
     *
     * <p>A mismatched client is rejected at handshake rather than left to misread a payload, which is
     * what makes appending fields safe <em>within</em> a version and a bump mandatory across one.
     */
    private static final String PROTOCOL_VERSION = "5";

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
     *
     * <p>{@code requestId} is the client's own generation stamp, echoed back on the reply and used for
     * nothing else (§18.3). The server never trusts it, compares it or stores it: it exists so a
     * reply that arrives after the player has switched village or villager can be recognised as
     * answering a question nobody is asking any more, instead of applying a profile to the wrong
     * selection. Zero means "not asked for" and is reserved for a server-pushed snapshot.
     */
    public record RequestSnapshotC2S(int contextEntityId, Optional<CommunityKey> requestedCommunity,
                                     int page, int requestId) {

        /** The page-less form: page 0, which is what every non-paging caller wants. */
        public RequestSnapshotC2S(int contextEntityId, Optional<CommunityKey> requestedCommunity) {
            this(contextEntityId, requestedCommunity, 0, 0);
        }

        public RequestSnapshotC2S(int contextEntityId, Optional<CommunityKey> requestedCommunity,
                                  int page) {
            this(contextEntityId, requestedCommunity, page, 0);
        }

        public static void encode(RequestSnapshotC2S packet, FriendlyByteBuf buf) {
            buf.writeVarInt(packet.contextEntityId);
            buf.writeOptional(packet.requestedCommunity, (b, key) -> key.write(b));
            // Written last, so every field a reader already knew keeps the offset it had.
            buf.writeVarInt(Math.max(0, packet.page));
            buf.writeVarInt(Math.max(0, packet.requestId));
        }

        public static RequestSnapshotC2S decode(FriendlyByteBuf buf) {
            int entityId = buf.readVarInt();
            Optional<CommunityKey> community = buf.readOptional(CommunityKey::read);
            // A hostile page index is clamped here and again against the real page count, so it can
            // never index anything; the client's claim carries no weight of its own (§27.2).
            int page = Math.max(0, buf.readVarInt());
            int requestId = Math.max(0, buf.readVarInt());
            return new RequestSnapshotC2S(entityId, community, page, requestId);
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
                // One validation of the one client-supplied value, shared by both context answers: a
                // villager good enough to have an opinion and a villager good enough to have read the
                // player must be the same villager, or the two panes describe different people.
                Optional<Entity> villager = contextEntity(player, packet.contextEntityId());
                sendTo(player, buildSnapshot(player, selected, gameTime,
                        contextOpinion(player, villager, selected),
                        contextProfile(player, villager, selected),
                        packet.page(), packet.requestId()));
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
            // Nothing was asked about in particular, so the answer is positional or historical.
            Optional<CommunityKey> here = player.level() instanceof ServerLevel level
                    ? CommunityResolver.resolveNearest(level, player.blockPosition())
                    : Optional.empty();
            return ReputationService.unpromptedCommunity(player.server, player.getUUID(), here);
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

        public static void write(FriendlyByteBuf buf, IncidentSummary summary) {
            buf.writeUUID(summary.id);
            buf.writeResourceLocation(summary.type);
            buf.writeComponent(summary.display);
            buf.writeVarLong(Math.max(0L, summary.ageTicks));
            buf.writeInt(summary.contribution);
            buf.writeUtf(summary.status, 32);
            buf.writeUtf(summary.severity, 32);
            buf.writeBoolean(summary.pinned);
            // Appended, so every field a reader already knew keeps the offset it had.
            buf.writeEnum(summary.visibility);
            buf.writeInt(summary.baseDelta);
            buf.writeBoolean(summary.decays);
            buf.writeBoolean(summary.superseded);
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

        public static void write(FriendlyByteBuf buf, OpinionSummary summary) {
            buf.writeComponent(summary.villagerName);
            buf.writeComponent(summary.tierName);
            buf.writeEnum(summary.basis);
        }

        public static OpinionSummary read(FriendlyByteBuf buf) {
            Component villagerName = buf.readComponent();
            Component tierName = buf.readComponent();
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

        public static void write(FriendlyByteBuf buf, FacetSummary summary) {
            buf.writeResourceLocation(summary.facet);
            buf.writeComponent(summary.name);
            buf.writeOptional(summary.label, FriendlyByteBuf::writeComponent);
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
        public static FacetSummary read(FriendlyByteBuf buf) {
            ResourceLocation facet = buf.readResourceLocation();
            Component name = buf.readComponent();
            Optional<Component> label = buf.readOptional(FriendlyByteBuf::readComponent);
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

        public static void write(FriendlyByteBuf buf, ProfileSummary summary) {
            buf.writeEnum(summary.availability);
            buf.writeEnum(summary.coverage);
            buf.writeBoolean(summary.readOnlyStore);
            buf.writeOptional(summary.reason,
                    (b, value) -> b.writeUtf(value, ProfileQueryResult.MAX_REASON_LENGTH));
            buf.writeVarInt(Math.max(0, summary.recognition));
            buf.writeUtf(summary.recognitionTierId, SnapshotCodec.TIER_ID_LENGTH);
            buf.writeComponent(summary.recognitionTierName);
            buf.writeVarInt(Math.max(0, summary.recognitionEvidence));
            buf.writeCollection(summary.dominantTraits.stream()
                            .limit(ReputationBounds.MAX_SYNCED_DOMINANT_TRAITS).toList(),
                    FriendlyByteBuf::writeComponent);
            buf.writeCollection(summary.details.stream()
                            .limit(ReputationBounds.MAX_SYNCED_FACET_DETAILS).toList(),
                    FacetSummary::write);
            buf.writeVarLong(Math.max(0L, summary.profileRevision));
            buf.writeVarLong(Math.max(0L, summary.definitionGeneration));
        }

        public static ProfileSummary read(FriendlyByteBuf buf) {
            ProfileAvailability availability = buf.readEnum(ProfileAvailability.class);
            ProfileCoverage coverage = buf.readEnum(ProfileCoverage.class);
            boolean readOnly = buf.readBoolean();
            Optional<String> reason =
                    buf.readOptional(b -> b.readUtf(ProfileQueryResult.MAX_REASON_LENGTH));
            int recognition = Math.min(ProfileMath.MAX_RECOGNITION, Math.max(0, buf.readVarInt()));
            String tierId = buf.readUtf(SnapshotCodec.TIER_ID_LENGTH);
            Component tierName = buf.readComponent();
            int evidence = SnapshotCodec.boundedCount(buf.readVarInt());
            // Both lengths are checked before a list is allocated: truncating at the encoder bounds a
            // reply this server built, and says nothing at all about a frame somebody else wrote.
            List<Component> traits = SnapshotCodec.readBounded(buf, ReputationBounds.MAX_SYNCED_DOMINANT_TRAITS,
                    FriendlyByteBuf::readComponent);
            List<FacetSummary> details = SnapshotCodec.readBounded(buf, ReputationBounds.MAX_SYNCED_FACET_DETAILS,
                    FacetSummary::read);
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

        public static void write(FriendlyByteBuf buf, VillagerProfileSummary summary) {
            buf.writeUUID(summary.villagerId);
            buf.writeComponent(summary.villagerName);
            ProfileSummary.write(buf, summary.known);
            buf.writeInt(summary.baseOpinion);
            buf.writeInt(summary.facetAdjustment);
            buf.writeInt(summary.finalOpinion);
            buf.writeComponent(summary.opinionTierName);
            buf.writeEnum(summary.traitBasis);
            buf.writeVarInt(Math.max(0, summary.involvedCount));
            buf.writeVarInt(Math.max(0, summary.witnessedCount));
            buf.writeVarInt(Math.max(0, summary.hearsayCount));
        }

        public static VillagerProfileSummary read(FriendlyByteBuf buf) {
            UUID villagerId = buf.readUUID();
            Component villagerName = buf.readComponent();
            ProfileSummary known = ProfileSummary.read(buf);
            int base = buf.readInt();
            int adjustment = buf.readInt();
            int finalOpinion = buf.readInt();
            Component tierName = buf.readComponent();
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
            buf.writeCollection(detail.titles.stream()
                    .limit(ReputationBounds.MAX_TITLES).toList(), FriendlyByteBuf::writeComponent);
            buf.writeCollection(detail.incidents.stream()
                    .limit(ReputationBounds.MAX_SYNCED_INCIDENTS).toList(), IncidentSummary::write);
            buf.writeVarInt(Math.max(0, detail.totalIncidents));
            // Written last, so every field a reader already knew keeps the offset it had.
            buf.writeOptional(detail.opinion, OpinionSummary::write);
            SnapshotCodec.writeWithinBudget(buf, detail.profile, detail.villagerProfile,
                    ProfileSummary::write, VillagerProfileSummary::write, ProfileSummary::degraded,
                    VillagerProfileSummary::degraded, ReputationBounds.MAX_PROFILE_PAYLOAD_BYTES);
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
            List<Component> titles = SnapshotCodec.readBounded(buf, ReputationBounds.MAX_TITLES,
                    FriendlyByteBuf::readComponent);
            List<IncidentSummary> incidents = SnapshotCodec.readBounded(buf, ReputationBounds.MAX_SYNCED_INCIDENTS,
                    IncidentSummary::read);
            int total = buf.readVarInt();
            Optional<OpinionSummary> opinion = buf.readOptional(OpinionSummary::read);
            Optional<ProfileSummary> profile = buf.readOptional(ProfileSummary::read);
            Optional<VillagerProfileSummary> villagerProfile =
                    buf.readOptional(VillagerProfileSummary::read);
            return new SelectedDetail(key, name, score, baseline, tierId, tierName, tierDescription,
                    tierThreshold, nextTierId, nextTierName, nextThreshold, titles, incidents, total,
                    opinion, profile, villagerProfile);
        }
    }

    /**
     * The whole reply: every community the player is known in, plus the detail of the selected one.
     *
     * <p>Bounded before encoding (§27.3): at most {@link ReputationBounds#MAX_SYNCED_COMMUNITIES}
     * communities <em>per page</em> and {@link ReputationBounds#MAX_SYNCED_INCIDENTS} incident lines,
     * so a player with a maximal ledger cannot produce a packet large enough to disconnect them.
     *
     * <p>{@code totalCommunities} is the true count, not the page's: the screen must be able to say
     * "64 of 210" rather than silently omitting the rest (§5 F16 row 3). The selected detail is
     * carried whatever page this is, so paging never moves the selection.
     */
    public record SnapshotS2C(List<CommunitySummary> communities, Optional<SelectedDetail> selected,
                              List<Component> globalTitles, int page, int pageCount,
                              int totalCommunities, int requestId) {

        /** A reply to nobody's question: a server push, which no client may discard as stale. */
        public static final int UNSOLICITED = 0;

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

        public static void encode(SnapshotS2C packet, FriendlyByteBuf buf) {
            buf.writeCollection(packet.communities.stream()
                    .limit(ReputationBounds.MAX_SYNCED_COMMUNITIES).toList(), CommunitySummary::write);
            buf.writeOptional(packet.selected, SelectedDetail::write);
            buf.writeCollection(packet.globalTitles.stream()
                    .limit(ReputationBounds.MAX_TITLES).toList(), FriendlyByteBuf::writeComponent);
            // Appended, so every field a reader already knew keeps the offset it had.
            buf.writeVarInt(Math.max(0, packet.page));
            buf.writeVarInt(Math.max(1, packet.pageCount));
            buf.writeVarInt(Math.max(0, packet.totalCommunities));
            // The client's own stamp, echoed unread: see RequestSnapshotC2S#requestId.
            buf.writeVarInt(Math.max(0, packet.requestId));
        }

        public static SnapshotS2C decode(FriendlyByteBuf buf) {
            List<CommunitySummary> communities = SnapshotCodec.readBounded(buf,
                    ReputationBounds.MAX_SYNCED_COMMUNITIES, CommunitySummary::read);
            Optional<SelectedDetail> selected = buf.readOptional(SelectedDetail::read);
            List<Component> globalTitles = SnapshotCodec.readBounded(buf, ReputationBounds.MAX_TITLES,
                    FriendlyByteBuf::readComponent);
            int page = buf.readVarInt();
            int pageCount = Math.max(1, buf.readVarInt());
            int total = buf.readVarInt();
            int requestId = Math.max(0, buf.readVarInt());
            return new SnapshotS2C(communities, selected, globalTitles,
                    SnapshotPaging.clampPage(page, pageCount), pageCount, total, requestId);
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

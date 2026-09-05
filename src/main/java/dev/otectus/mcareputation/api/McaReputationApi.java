package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.event.CoreIncidentAuthorities;
import dev.otectus.mcareputation.community.CommunityResolver;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.reputation.ReputationService;
import dev.otectus.mcareputation.reputation.ReputationTierSet;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import dev.otectus.mcareputation.reputation.TitleService;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The stable public entry point for other mods (spec §25). Everything here takes and returns
 * Minecraft and Java types; no MCA internal type and no mutable internal record ever crosses this
 * boundary.
 *
 * <h2>Contracts every method honours</h2>
 *
 * <ul>
 *   <li><b>Writes are server-thread only.</b> Called from elsewhere they refuse and log rather than
 *       corrupting the store.</li>
 *   <li><b>Queries never expose mutable collections.</b></li>
 *   <li><b>Nothing here throws at an integration.</b> A dialogue evaluation, a quest condition, or a
 *       reward claim must never crash because of this mod; failures come back as a documented
 *       fallback or a typed failure result.</li>
 *   <li><b>Unknown player or community reads as absent or zero</b>, per each method's documentation.
 *       There is no meaningful difference between "no record" and "a stranger".</li>
 * </ul>
 *
 * <h2>Versioning</h2>
 *
 * <p>{@link #getApiVersion()} lets a bridge refuse an incompatible future version gracefully instead
 * of dying on a {@code NoSuchMethodError}. Bump it only for a breaking change to these signatures;
 * {@code API.md} documents what each version guarantees.
 */
public final class McaReputationApi {

    /** Incremented only on a breaking change to this class's signatures (§25). */
    private static final int API_VERSION = 1;

    private McaReputationApi() {
    }

    /** The binary API generation. Bridges should refuse anything they were not written against. */
    public static int getApiVersion() {
        return API_VERSION;
    }

    /** Whether the reputation system is switched on. False means every query returns its neutral value. */
    public static boolean isEnabled() {
        return McaReputationConfig.enabled();
    }

    /**
     * Whether requests attributed to this source's mod are currently accepted (§23's
     * {@code enableQuestsIntegration} / {@code enableConversationsIntegration}). Sources from any
     * other namespace — the core hooks, commands, third-party mods — are always accepted.
     */
    private static boolean integrationEnabled(@Nullable ResourceLocation source) {
        if (source == null) {
            return true;
        }
        if (McaReputation.QUESTS_MOD_ID.equals(source.getNamespace())) {
            return McaReputationConfig.questsIntegrationEnabled();
        }
        if (McaReputation.CONVERSATIONS_MOD_ID.equals(source.getNamespace())) {
            return McaReputationConfig.conversationsIntegrationEnabled();
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Communities
    // ------------------------------------------------------------------

    /** The community an MCA villager belongs to, or empty (§12.2). */
    public static Optional<CommunityKey> resolveCommunity(Entity villager) {
        try {
            return CommunityResolver.resolve(villager);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] resolveCommunity(entity) failed; returning empty", t);
            return Optional.empty();
        }
    }

    /** The nearest community to a position within {@code radius}, or empty. */
    public static Optional<CommunityKey> resolveCommunity(ServerLevel level, BlockPos pos, int radius) {
        try {
            return CommunityResolver.resolveNearest(level, pos, radius);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] resolveCommunity(level,pos) failed; returning empty", t);
            return Optional.empty();
        }
    }

    /** Every community this player has a record for. Empty list for an unknown player. */
    public static List<CommunityKey> knownCommunities(MinecraftServer server, UUID player) {
        try {
            return dev.otectus.mcareputation.state.ReputationSavedData.get(server).player(player)
                    .map(record -> List.copyOf(record.communityKeys()))
                    .orElseGet(List::of);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] knownCommunities failed; returning empty", t);
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** A player's standing, or empty when they have no record for this community. */
    public static OptionalInt getScore(MinecraftServer server, UUID player, CommunityKey community) {
        try {
            return ReputationService.score(server, player, community);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] getScore failed; returning empty", t);
            return OptionalInt.empty();
        }
    }

    /** A player's standing, treating an unknown record as {@code 0}. */
    public static int getScoreOrZero(MinecraftServer server, UUID player, CommunityKey community) {
        return getScore(server, player, community).orElse(0);
    }

    /** The full snapshot: score, tier, next tier, titles, and recent history. */
    public static Optional<ReputationSnapshot> getSnapshot(MinecraftServer server, UUID player,
                                                           CommunityKey community) {
        try {
            return ReputationService.snapshot(server, player, community, gameTime(server));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] getSnapshot failed; returning empty", t);
            return Optional.empty();
        }
    }

    /** Every known community's snapshot, best standing first. */
    public static List<ReputationSnapshot> getAllSnapshots(MinecraftServer server, UUID player) {
        try {
            return ReputationService.knownCommunities(server, player, gameTime(server));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] getAllSnapshots failed; returning empty", t);
            return List.of();
        }
    }

    /**
     * The current tier id on the default ladder, or {@code "stranger"}-equivalent for an unknown
     * record — the floor tier containing score zero. Never empty, so a dialogue condition always has
     * something to compare.
     */
    public static String getTierId(MinecraftServer server, UUID player, CommunityKey community) {
        try {
            return ReputationService.currentTierId(getScoreOrZero(server, player, community));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] getTierId failed; returning the floor tier", t);
            return ReputationTiers.BUILTIN_DEFAULT.tierFor(0).id();
        }
    }

    /**
     * The bounded check bias for a Conversations axis (§30.3). Only {@code trust} and {@code respect}
     * are ever non-zero, and the value is hard-clamped to ±8 whatever the datapack says. Returns
     * {@code 0} when the mod is disabled, so authored disabled-context fallbacks fire.
     */
    public static int getCheckBias(MinecraftServer server, UUID player, CommunityKey community, String axis) {
        try {
            if (!isEnabled() || !McaReputationConfig.conversationsIntegrationEnabled() || community == null) {
                return 0;
            }
            ReputationTierSet ladder = ReputationTiers.getDefault();
            return ladder.tierFor(getScoreOrZero(server, player, community)).biasFor(axis);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] getCheckBias failed; returning 0", t);
            return 0;
        }
    }

    /** Whether a snapshot satisfies an authored standing query (§30.2). Unknown ids fail to match. */
    public static boolean matches(MinecraftServer server, UUID player, CommunityKey community,
                                  ReputationQuery query) {
        try {
            if (!isEnabled() || !McaReputationConfig.conversationsIntegrationEnabled() || query == null) {
                return false;
            }
            ReputationTierSet ladder = ReputationTiers.getDefault();
            return getSnapshot(server, player, community)
                    .map(snapshot -> query.matches(snapshot, ladder::indexOf))
                    .orElseGet(() -> query.isEmpty());
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] matches failed; returning false", t);
            return false;
        }
    }

    /** Recent incidents, newest first, capped at {@code limit}. */
    public static List<ReputationIncidentView> recentIncidents(MinecraftServer server, UUID player,
                                                               CommunityKey community, int limit) {
        try {
            return ReputationService.recentIncidents(server, player, community, limit, gameTime(server));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] recentIncidents failed; returning empty", t);
            return List.of();
        }
    }

    /** Incidents matching a selector, in deterministic order (§29.6). */
    public static List<ReputationIncidentView> selectIncidents(MinecraftServer server, UUID player,
                                                               CommunityKey community, IncidentQuery query) {
        try {
            if (query == null) {
                return List.of();
            }
            return query.select(ReputationService.recentIncidents(server, player, community,
                    Integer.MAX_VALUE, gameTime(server)));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] selectIncidents failed; returning empty", t);
            return List.of();
        }
    }

    /**
     * One incident, normalized into a tellable story for MCA: Conversations (§30.4).
     *
     * <p>Reputation supplies the fact, a phrase key, and its arguments; Conversations renders it in the
     * speaker's personality and locale and owns the per-teller/per-listener "already told" memory.
     * Empty when the incident is unknown or its definition carries no gossip phrase — not every deed
     * is worth a sentence.
     *
     * @param playerName the deed's actor as the speaker would name them, bound to {@code {player}}
     */
    public static Optional<dev.otectus.mcareputation.api.ExternalGossipCandidate> gossipCandidate(
            MinecraftServer server, UUID player, CommunityKey community, UUID incidentId,
            String playerName) {
        try {
            if (!McaReputationConfig.conversationsIntegrationEnabled()) {
                return Optional.empty();
            }
            return dev.otectus.mcareputation.reputation.ReputationService
                    .incident(server, player, community, incidentId)
                    .flatMap(record -> {
                        var definition = dev.otectus.mcareputation.incident.IncidentRegistry
                                .getOrUnknown(record.type());
                        if (!definition.gossip().isTellable()) {
                            return Optional.<dev.otectus.mcareputation.api.ExternalGossipCandidate>empty();
                        }
                        var metadata = dev.otectus.mcareputation.state.ReputationSavedData.get(server)
                                .player(player)
                                .flatMap(playerRecord -> playerRecord.community(community))
                                .map(communityRecord -> communityRecord.metadata())
                                .orElse(dev.otectus.mcareputation.community.CommunityMetadata.EMPTY);
                        return Optional.of(new dev.otectus.mcareputation.api.ExternalGossipCandidate(
                                record.id(),
                                record.type(),
                                record.createdGameTime(),
                                record.ageTicks(gameTime(server)),
                                community.asString(),
                                metadata.name(),
                                definition.gossip().tone().orElse(""),
                                definition.gossip().phrase().orElse(""),
                                dev.otectus.mcareputation.incident.IncidentDisplay
                                        .gossipArguments(definition, record, playerName),
                                record.currentContribution()));
                    });
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] gossipCandidate failed; returning empty", t);
            return Optional.empty();
        }
    }

    /**
     * Whether a villager knows about an incident (§19.3). Residency is resolved from MCA, so a
     * villager who moved away keeps only what they witnessed themselves.
     */
    public static boolean villagerKnows(MinecraftServer server, Entity villager, UUID player, UUID incident) {
        try {
            Optional<CommunityKey> community = resolveCommunity(villager);
            if (community.isEmpty()) {
                return false;
            }
            return ReputationService.villagerKnows(server, player, community.get(), incident,
                    villager.getUUID(),
                    CommunityResolver.isResident(server, community.get(), villager.getUUID()),
                    gameTime(server));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] villagerKnows failed; returning false", t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Per-villager opinion
    // ------------------------------------------------------------------

    /**
     * What one villager personally makes of a player (§19.3), derived from the community ledger
     * through what that villager saw, was part of, or has had time to hear. Nothing is stored, and
     * nothing done to the villager directly moves it.
     *
     * <p>Empty when the feature is switched off, when the mod is disabled, or when the player has no
     * record with this community at all — there is nothing to have an opinion about. A villager who
     * knows nothing yet answers with a present {@code VillagerOpinion} at {@code 0} and
     * {@link VillagerOpinion.OpinionBasis#NONE}, which is a different and useful answer.
     *
     * <p>Additive to API version 1; {@link #getApiVersion()} deliberately does not move. A companion
     * probes for this method with {@code getMethod} and degrades to village-level standing without it.
     *
     * @since MCA: Reputation 0.4.0
     */
    public static Optional<VillagerOpinion> getVillagerOpinion(MinecraftServer server, UUID player,
                                                               UUID villager, CommunityKey community) {
        try {
            if (!isEnabled() || !McaReputationConfig.villagerOpinionEnabled()
                    || server == null || player == null || villager == null || community == null) {
                return Optional.empty();
            }
            long gameTime = gameTime(server);
            ReputationService.reconcile(server, player, gameTime);
            var record = dev.otectus.mcareputation.state.ReputationSavedData.get(server).player(player)
                    .flatMap(playerRecord -> playerRecord.community(community));
            if (record.isEmpty()) {
                return Optional.empty();
            }
            boolean resident = CommunityResolver.isResident(server, community, villager);
            var opinion = dev.otectus.mcareputation.reputation.OpinionResolver.resolve(
                    record.get(), villager, resident, gameTime,
                    McaReputationConfig.minRumorDelayTicks(), McaReputationConfig.maxRumorDelayTicks(),
                    McaReputationConfig.opinionHearsayPercent(),
                    McaReputationConfig.opinionInvolvedPercent(),
                    McaReputationConfig.minimumScore(), McaReputationConfig.maximumScore());
            // The UUID overload has no entity to read a name from; the entity overload fills it in.
            return Optional.of(new VillagerOpinion(villager, "", community,
                    opinion.score(), ReputationTiers.getDefault().tierFor(opinion.score()).id(),
                    opinion.basis(), opinion.knownIncidents()));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] getVillagerOpinion failed; returning empty", t);
            return Optional.empty();
        }
    }

    /**
     * The same question asked about a villager entity: their community is resolved from MCA, and their
     * name comes back with the answer.
     *
     * @since MCA: Reputation 0.4.0
     */
    public static Optional<VillagerOpinion> getVillagerOpinion(MinecraftServer server, UUID player,
                                                               Entity villager) {
        try {
            if (villager == null) {
                return Optional.empty();
            }
            return resolveCommunity(villager)
                    .flatMap(community -> getVillagerOpinion(server, player, villager.getUUID(), community))
                    .map(opinion -> new VillagerOpinion(opinion.villagerId(),
                            villager.getName().getString(), opinion.community(), opinion.opinion(),
                            opinion.tierId(), opinion.basis(), opinion.knownIncidents()));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] getVillagerOpinion(entity) failed; returning empty", t);
            return Optional.empty();
        }
    }

    /**
     * The bounded check bias for a Conversations axis, read from the <em>villager's own</em> opinion
     * tier rather than the village's (§30.3). Same ±8 ceiling and the same two axes as
     * {@link #getCheckBias}; {@code 0} whenever opinion is unavailable, so an authored fallback fires.
     *
     * @since MCA: Reputation 0.4.0
     */
    public static int getOpinionBias(MinecraftServer server, UUID player, UUID villager,
                                     CommunityKey community, String axis) {
        try {
            if (!McaReputationConfig.conversationsIntegrationEnabled()) {
                return 0;
            }
            ReputationTierSet ladder = ReputationTiers.getDefault();
            return getVillagerOpinion(server, player, villager, community)
                    .map(opinion -> ladder.tierFor(opinion.opinion()).biasFor(axis))
                    .orElse(0);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] getOpinionBias failed; returning 0", t);
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** Records one deed. The only way to change canonical standing (§8, §18). */
    public static ReputationResult record(ReputationRequest request) {
        if (request != null && !integrationEnabled(request.source())) {
            return ReputationResult.rejected(ReputationResult.Reason.DISABLED, request.community());
        }
        return ReputationService.record(request);
    }

    /** Moves an incident to a stronger status (§15.2). Idempotent; a weaker status is refused. */
    public static ResolutionResult resolve(MinecraftServer server, UUID player, CommunityKey community,
                                           UUID incident, IncidentStatus status, ResourceLocation source) {
        if (!integrationEnabled(source)) {
            return ResolutionResult.notApplied(ResolutionResult.Reason.DISABLED);
        }
        return ReputationService.resolve(server, player, community, incident, status, source, gameTime(server));
    }

    /** Resolves whichever incident a selector picks — server-side and deterministic (§29.6). */
    public static ResolutionResult resolveBySelector(MinecraftServer server, UUID player,
                                                     CommunityKey community, IncidentQuery selector,
                                                     IncidentStatus status, ResourceLocation source) {
        if (!integrationEnabled(source)) {
            return ResolutionResult.notApplied(ResolutionResult.Reason.DISABLED);
        }
        return ReputationService.resolveBySelector(server, player, community, selector, status, source,
                gameTime(server));
    }

    // ------------------------------------------------------------------
    // Titles
    // ------------------------------------------------------------------

    /**
     * Whether the player holds a title. With {@code community} empty, answers "anywhere" — which is
     * what a dialogue condition without a village context means.
     */
    public static boolean hasTitle(MinecraftServer server, UUID player, ResourceLocation title,
                                   Optional<CommunityKey> community) {
        try {
            return TitleService.hasTitle(server, player, title, community.orElse(null));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] hasTitle failed; returning false", t);
            return false;
        }
    }

    /** @return true when newly granted. */
    public static boolean grantTitle(MinecraftServer server, UUID player, ResourceLocation title,
                                     @Nullable CommunityKey community) {
        try {
            return TitleService.grant(server, player, server.getPlayerList().getPlayer(player),
                    community, title);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] grantTitle failed; nothing granted", t);
            return false;
        }
    }

    /** @return true when it was held and removed. */
    public static boolean revokeTitle(MinecraftServer server, UUID player, ResourceLocation title,
                                      @Nullable CommunityKey community) {
        try {
            return TitleService.revoke(server, player, community, title);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] revokeTitle failed; nothing revoked", t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Integration plumbing
    // ------------------------------------------------------------------

    /**
     * Claims one or more {@linkplain CoreIncidentKind core detection kinds} for a companion mod, so
     * this mod stops detecting them itself (§20, §25.1).
     *
     * <p>This exists to solve double detection and nothing else. Two mods watching the same
     * {@code LivingHurtEvent} and both filing an assault charge one punch twice, and neither can fix
     * that alone. The claimant files the equivalent incident through {@link #record} instead; because
     * it is the same incident type, scores, decay, gossip, witnesses and the ledger are unchanged.
     *
     * <p>Call at mod setup. Hold the returned handle: closing it is the only way to withdraw the
     * claim, after which detection resumes here on the very next event.
     *
     * <p>Additive to API version 1 rather than a break. A companion written against the original
     * version neither calls this nor is affected by it, so {@link #getApiVersion()} deliberately does
     * not move — bumping it would make every existing bridge refuse an API it is still fully
     * compatible with.
     *
     * @return the handle that withdraws the claim; never null
     * @since MCA: Reputation 0.3.0
     */
    public static CoreIncidentAuthorityRegistration registerCoreIncidentAuthority(CoreIncidentAuthority authority) {
        return CoreIncidentAuthorities.register(authority);
    }

    /**
     * Whether a companion is currently detecting this kind, leaving this mod stood down for it.
     *
     * <p>The question a claimant asks straight after registering, to confirm the claim actually took —
     * and the question an operator is really asking when villager assaults stop appearing in the
     * ledger. Also drives {@code /mcareputation debug authorities}.
     *
     * <p>False when nobody has claimed it, when every claimant's own configuration currently says it
     * is not detecting, and when a claimant threw while being asked. That last case is deliberate:
     * detection staying here risks a visible duplicate, whereas assuming the claim held would lose the
     * deed silently.
     *
     * @since MCA: Reputation 0.3.0
     */
    public static boolean hasExternalAuthority(CoreIncidentKind kind) {
        return CoreIncidentAuthorities.isClaimed(kind);
    }

    // ------------------------------------------------------------------
    // Decay immunity
    // ------------------------------------------------------------------

    /**
     * Whether decay is currently switched off for a community, for every player at once (§15.1).
     * A protected village's ledger ages only when a deed moves it.
     *
     * <p>False for an unknown server or community, and false when anything goes wrong — the safe
     * answer is the ordinary one, where decay runs.
     *
     * <p>Additive to API version 1; {@link #getApiVersion()} deliberately does not move. A companion
     * probes for this method with {@code getMethod} and treats its absence as "not immune".
     *
     * @since MCA: Reputation 0.4.0
     */
    public static boolean isDecayImmune(MinecraftServer server, CommunityKey community) {
        try {
            if (server == null || community == null) {
                return false;
            }
            return dev.otectus.mcareputation.state.ReputationSavedData.get(server)
                    .isDecayImmune(community);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] isDecayImmune failed; returning false", t);
            return false;
        }
    }

    /**
     * Switches decay off or back on for a community. A write, so server-thread only: called from
     * anywhere else it refuses and logs rather than racing the save.
     *
     * @return true when the flag actually changed
     * @since MCA: Reputation 0.4.0
     */
    public static boolean setDecayImmune(MinecraftServer server, CommunityKey community, boolean immune) {
        try {
            if (server == null || community == null) {
                return false;
            }
            if (!server.isSameThread()) {
                McaReputation.LOGGER.warn("[MCA: Reputation] setDecayImmune called off the server thread; "
                        + "refusing to write");
                return false;
            }
            return dev.otectus.mcareputation.state.ReputationSavedData.get(server)
                    .setDecayImmune(community, immune);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] setDecayImmune failed; nothing changed", t);
            return false;
        }
    }

    /** Registers a fallback mirror (§25.1). Call at mod setup; see {@link ReputationMirror}. */
    public static void registerMirror(ReputationMirror mirror) {
        ReputationService.registerMirror(mirror);
    }

    public static void unregisterMirror(ReputationMirror mirror) {
        ReputationService.unregisterMirror(mirror);
    }

    /**
     * Registers a legacy import provider (§32.2), consulted at each login and by
     * {@code /mcareputation migrate}. This is the supported registration path — companions must not
     * reach into internal packages for it.
     */
    public static void registerImportProvider(LegacyImportProvider provider) {
        dev.otectus.mcareputation.event.LegacyImportProviders.register(provider);
    }

    public static void unregisterImportProvider(LegacyImportProvider provider) {
        dev.otectus.mcareputation.event.LegacyImportProviders.unregister(provider);
    }

    /** The registered import providers' names, for diagnostics. */
    public static List<String> importProviderNames() {
        return dev.otectus.mcareputation.event.LegacyImportProviders.providerNames();
    }

    /** Applies a one-time import of pre-Reputation standing (§32.2). Idempotent per source id. */
    public static ImportResult importLegacy(LegacyImportRequest request) {
        return ReputationService.importLegacy(request);
    }

    /**
     * Opens the standing screen on this player's client, preselecting {@code community} (§29.7 —
     * the Quests Journal's "View Deeds" link). The caller is responsible for having validated the
     * interaction server-side; this method's own guarantee is that a fresh snapshot is sent ahead of
     * the open, so the screen never shows another moment's cache.
     *
     * @return false when the push could not be sent; nothing is shown in that case
     */
    public static boolean openReputationScreen(net.minecraft.server.level.ServerPlayer player,
                                               @Nullable CommunityKey community) {
        try {
            if (player == null) {
                return false;
            }
            dev.otectus.mcareputation.network.ReputationNetwork.openScreenWithSnapshot(player, community);
            return true;
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] openReputationScreen failed; nothing shown", t);
            return false;
        }
    }

    /**
     * The game time queries should be evaluated against. Read from the overworld so every dimension's
     * communities age on one clock — otherwise an incident's decay would depend on which dimension the
     * player happened to be standing in when it was queried.
     */
    private static long gameTime(MinecraftServer server) {
        try {
            return server.overworld().getGameTime();
        } catch (Throwable t) {
            return 0L;
        }
    }
}

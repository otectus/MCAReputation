package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityResolver;
import dev.otectus.mcareputation.event.CoreIncidentAuthorities;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.reputation.ReputationService;
import dev.otectus.mcareputation.reputation.ReputationTierSet;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import dev.otectus.mcareputation.reputation.StandingAvailability;
import dev.otectus.mcareputation.reputation.TitleService;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import org.jetbrains.annotations.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
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
 *
 * <p><b>This is version 2.</b> The public event types in {@code api.event} now extend NeoForge's
 * event base rather than Forge's, so the bytecode a bridge compiled against version 1 no longer
 * links: a companion written against 1 must re-target this API and recompile, not merely re-resolve.
 */
public final class McaReputationApi {

    /** Incremented only on a breaking change to this class's signatures (§25). */
    private static final int API_VERSION = 2;

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
        if (McaReputation.CRIME_MOD_ID.equals(source.getNamespace())) {
            return McaReputationConfig.crimeIntegrationEnabled();
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Core-incident authority
    // ------------------------------------------------------------------

    /**
     * Claims one or more {@linkplain CoreIncidentKind core detection kinds} for a companion mod, so
     * this mod stops detecting them itself (§20, §25.1).
     *
     * <p>This exists to solve double detection and nothing else. Two mods watching the same
     * {@code LivingDamageEvent.Post} and both filing an assault charge one punch twice, and neither
     * can fix that alone. The claimant files the equivalent incident through {@link #record} instead;
     * because it is the same incident type, scores, decay, gossip, witnesses and the ledger are
     * unchanged.
     *
     * <p>Call at mod setup. Hold the returned handle: closing it is the only way to withdraw the
     * claim, after which detection resumes here on the very next event.
     *
     * @return the handle that withdraws the claim; never null
     * @since MCA: Reputation 0.3.0
     */
    public static CoreIncidentAuthorityRegistration registerCoreIncidentAuthority(CoreIncidentAuthority authority) {
        return CoreIncidentAuthorities.register(authority);
    }

    /**
     * What this build can currently do, in one object a bridge can negotiate against (§6).
     *
     * <p>Additive to API version 1, which deliberately does not move (D4): a companion probes for this
     * method and falls back to its own assumptions without it. {@link ReputationCapabilities#nativeKinds}
     * lists the kinds this mod is still detecting itself right now — enabled by config and not
     * effectively claimed — so a claimant can confirm its claim took effect rather than trusting it.
     *
     * @since MCA: Reputation 0.4.1
     */
    public static ReputationCapabilities capabilities(MinecraftServer server) {
        boolean enabled = isEnabled();
        Set<CoreIncidentKind> nativeKinds = new LinkedHashSet<>();
        Map<CoreIncidentKind, Optional<ResourceLocation>> claimants = new LinkedHashMap<>();
        Optional<String> readinessReason = Optional.empty();
        try {
            for (CoreIncidentAuthorities.AuthorityStatus status : CoreIncidentAuthorities.inspect()) {
                Optional<ResourceLocation> claimant = status.claimed()
                        ? status.claimantId().map(ResourceLocation::tryParse)
                        : Optional.empty();
                claimants.put(status.kind(), claimant);
                if (enabled && claimant.isEmpty() && detectsNatively(status.kind())) {
                    nativeKinds.add(status.kind());
                }
            }
            if (!enabled) {
                readinessReason = Optional.of("mcareputation is disabled in the common config");
            } else if (server == null) {
                readinessReason = Optional.of("no server is running; only static capabilities are known");
            }
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] capabilities failed; reporting what is known", t);
            readinessReason = Optional.of("capability inspection failed: " + t);
        }
        return new ReputationCapabilities(API_VERSION, enabled,
                McaReputationConfig.scoreDecayEnabled(), McaReputationConfig.villagerOpinionEnabled(),
                Set.of(ReputationCapabilities.FEATURE_STANDING_CHANGE,
                        ReputationCapabilities.FEATURE_EFFECTIVE_AUTHORITY,
                        ReputationCapabilities.FEATURE_DUPLICATE_IDENTITY,
                        ReputationCapabilities.FEATURE_SUPERSEDE,
                        ReputationCapabilities.FEATURE_OCCURRENCE_TIME,
                        ReputationCapabilities.FEATURE_RECEIPTS,
                        ReputationCapabilities.FEATURE_DELIVERY,
                        ReputationCapabilities.FEATURE_READ_ONLY_LOOKUP,
                        ReputationCapabilities.FEATURE_SPEAKER_QUERY,
                        ReputationCapabilities.FEATURE_BOUND_RESOLUTION,
                        ReputationCapabilities.FEATURE_TITLE_SYNC,
                        ReputationCapabilities.FEATURE_LADDER_HIGH_WATER,
                        ReputationCapabilities.FEATURE_GOSSIP_STORY),
                nativeKinds, claimants, readinessReason);
    }

    /** Whether the core hook behind a kind is switched on at all (§23's {@code core_events} block). */
    private static boolean detectsNatively(CoreIncidentKind kind) {
        return switch (kind) {
            case MCA_VILLAGER_ASSAULT -> McaReputationConfig.coreAssaultEnabled();
            case MCA_VILLAGER_KILL -> McaReputationConfig.coreKillingEnabled();
            case MCA_VILLAGER_RESCUE -> McaReputationConfig.coreRescueEnabled();
            case MCA_VILLAGER_CURE -> McaReputationConfig.coreCureEnabled();
            case MCA_RAID_REPELLED -> McaReputationConfig.coreRaidEnabled();
            case PLAYER_KILL_IN_VILLAGE -> McaReputationConfig.corePvpEnabled();
        };
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

    /**
     * Whether a player's standing satisfies an authored query (§30.2, §5 F14). Unknown ids fail to match.
     *
     * <p>Not gated on the Conversations integration switch: this same method backs Reputation's own
     * standing condition and its loot condition, and a dialogue mod's toggle has no business
     * disabling either. Evaluation goes through the one truth table, so a valid community with no
     * stored record answers from the effective neutral standing and creates nothing; an unresolvable
     * community fails closed.
     */
    public static boolean matches(MinecraftServer server, UUID player, CommunityKey community,
                                  ReputationQuery query) {
        try {
            if (query == null || server == null || player == null || community == null) {
                return false;
            }
            ReputationSavedData data = ReputationSavedData.get(server);
            StandingAvailability.EffectiveStanding standing = StandingAvailability.of(
                    McaReputationConfig.snapshot(), data, player, community, gameTime(server));
            Set<ResourceLocation> villageTitles = Set.of();
            Set<ResourceLocation> globalTitles = Set.of();
            if (standing.state().isAvailable()) {
                var playerRecord = data.player(player);
                villageTitles = playerRecord.flatMap(record -> record.community(community))
                        .map(record -> record.titles()).orElseGet(Set::of);
                globalTitles = playerRecord.map(record -> record.globalTitles()).orElseGet(Set::of);
            }
            return StandingAvailability.matches(standing, query, villageTitles, globalTitles);
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

    /**
     * Incidents matching a selector, in deterministic order (§29.6).
     *
     * <p><b>Fails closed</b> since 0.4.1: a selector asking for {@code known_to_speaker} with no speaker
     * to evaluate it against answers with nothing. Use
     * {@link #selectIncidents(MinecraftServer, UUID, CommunityKey, IncidentQuery, SpeakerContext)} to
     * supply one.
     */
    public static List<ReputationIncidentView> selectIncidents(MinecraftServer server, UUID player,
                                                               CommunityKey community, IncidentQuery query) {
        try {
            if (query == null) {
                return List.of();
            }
            if (query.knownToSpeaker()) {
                McaReputation.LOGGER.debug("[MCA: Reputation] selectIncidents was asked for incidents "
                        + "known to a speaker without naming one; answering with nothing");
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
     * The same selection, evaluated on behalf of one villager (§6 "Speaker-aware query").
     *
     * <p>{@link IncidentQuery#knownToSpeaker()} is honoured here and only here: the speaker's own
     * knowledge, including the deterministic delay before hearsay reaches them, decides what the
     * selector can see. Without the flag the speaker is irrelevant and the answer matches the
     * four-argument form.
     *
     * @since MCA: Reputation 0.4.1
     */
    public static List<ReputationIncidentView> selectIncidents(MinecraftServer server, UUID player,
                                                               CommunityKey community, IncidentQuery query,
                                                               SpeakerContext speaker) {
        try {
            return ReputationService.selectIncidents(server, player, community, query, speaker,
                    gameTime(server));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] selectIncidents failed; returning empty", t);
            return List.of();
        }
    }

    /**
     * The speaker a villager entity stands for, with residency resolved exactly as
     * {@link #villagerKnows} resolves it. Empty when the villager belongs to no community this mod can
     * name.
     *
     * @since MCA: Reputation 0.4.1
     */
    public static Optional<SpeakerContext> speakerContext(MinecraftServer server, Entity villager) {
        try {
            if (server == null || villager == null) {
                return Optional.empty();
            }
            return resolveCommunity(villager).map(community -> new SpeakerContext(villager.getUUID(),
                    CommunityResolver.isResident(server, community, villager.getUUID())));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] speakerContext failed; returning empty", t);
            return Optional.empty();
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
                        if (record.isSuperseded() || !definition.gossip().isTellable()) {
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
     * The newest story this villager could tell about this player, enriched for MCA: Conversations
     * (§6 "Gossip story").
     *
     * <p>Additive to {@link #gossipCandidate}, which is untouched: an adapter that only knows the older
     * call keeps its exact behaviour. What this adds is the semantic revision that says whether the
     * village's belief about a deed has actually changed - a resolution or a supersession moves it,
     * ordinary decay does not - and the corrections a speaker should acknowledge rather than repeat.
     *
     * @since MCA: Reputation 0.4.1
     */
    public static Optional<GossipStory> gossipStory(MinecraftServer server, Entity villager, UUID player,
                                                    CommunityKey community) {
        try {
            if (!McaReputationConfig.conversationsIntegrationEnabled() || server == null
                    || villager == null || community == null) {
                return Optional.empty();
            }
            return ReputationService.gossipStory(server, player, community, villager.getUUID(),
                    CommunityResolver.isResident(server, community, villager.getUUID()), gameTime(server));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] gossipStory failed; returning empty", t);
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
     * <p>Empty when the feature is switched off, when the mod is disabled, or when the villager,
     * player or community could not be resolved. A villager who knows nothing — including in a village
     * with no ledger yet — answers with a present {@code VillagerOpinion} at {@code 0} and
     * {@link VillagerOpinion.OpinionBasis#NONE}, which is a different and useful answer.
     * {@link #getVillagerOpinionDetailed} says which of those cases produced an empty result.
     *
     * <p>Additive to API version 1; {@link #getApiVersion()} deliberately does not move. A companion
     * probes for this method with {@code getMethod} and degrades to village-level standing without it.
     *
     * @since MCA: Reputation 0.4.0
     */
    public static Optional<VillagerOpinion> getVillagerOpinion(MinecraftServer server, UUID player,
                                                               UUID villager, CommunityKey community) {
        return getVillagerOpinionDetailed(server, player, villager, community).opinion();
    }

    /**
     * The same answer, with the reason there is or is not one (§5 F14).
     *
     * <p>The distinction the plain overload cannot make: a villager who has simply never heard of this
     * player is {@link OpinionResult.OpinionAvailability#AVAILABLE} with a zero opinion, and a caller
     * must not answer that with public standing. Only {@code DISABLED}, {@code UNSUPPORTED} and
     * {@code UNRESOLVED} license that fallback.
     *
     * @since MCA: Reputation 0.4.1
     */
    public static OpinionResult getVillagerOpinionDetailed(MinecraftServer server, UUID player,
                                                           UUID villager, CommunityKey community) {
        try {
            if (!isEnabled() || !McaReputationConfig.villagerOpinionEnabled()) {
                return OpinionResult.unavailable(OpinionResult.OpinionAvailability.DISABLED);
            }
            if (server == null || player == null || villager == null || community == null) {
                return OpinionResult.unavailable(OpinionResult.OpinionAvailability.UNRESOLVED);
            }
            long gameTime = gameTime(server);
            // Targeted: only the community being asked about, not every community this player knows
            // (§5 F07). One villager wondering about you is no reason to age the rest of the world.
            ReputationService.reconcileCommunity(server, player, community, gameTime);
            var record = ReputationSavedData.get(server).player(player)
                    .flatMap(playerRecord -> playerRecord.community(community));
            if (record.isEmpty()) {
                return uninformedOpinion(villager, community);
            }
            boolean resident = CommunityResolver.isResident(server, community, villager);
            var opinion = dev.otectus.mcareputation.reputation.OpinionResolver.resolve(
                    record.get(), villager, resident, gameTime,
                    McaReputationConfig.minRumorDelayTicks(), McaReputationConfig.maxRumorDelayTicks(),
                    McaReputationConfig.opinionHearsayPercent(),
                    McaReputationConfig.opinionInvolvedPercent(),
                    McaReputationConfig.minimumScore(), McaReputationConfig.maximumScore());
            // The UUID overload has no entity to read a name from; the entity overload fills it in.
            return OpinionResult.available(new VillagerOpinion(villager, "", community,
                    opinion.score(), ReputationTiers.getDefault().tierFor(opinion.score()).id(),
                    opinion.basis(), opinion.knownIncidents()));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] getVillagerOpinion failed; returning unresolved", t);
            return OpinionResult.unavailable(OpinionResult.OpinionAvailability.UNRESOLVED);
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
        return getVillagerOpinionDetailed(server, player, villager).opinion();
    }

    /**
     * A valid community with no ledger: this villager genuinely knows nothing, which is a real answer
     * and not a fallback (§5 F14). Package-visible so the rule can be asserted without a server.
     */
    static OpinionResult uninformedOpinion(UUID villager, CommunityKey community) {
        return OpinionResult.available(new VillagerOpinion(villager, "", community, 0,
                ReputationTiers.getDefault().tierFor(0).id(), VillagerOpinion.OpinionBasis.NONE, 0));
    }

    /**
     * The entity overload of {@link #getVillagerOpinionDetailed(MinecraftServer, UUID, UUID,
     * CommunityKey)}. A villager whose community cannot be resolved is {@code UNRESOLVED}, never a
     * silent zero.
     *
     * @since MCA: Reputation 0.4.1
     */
    public static OpinionResult getVillagerOpinionDetailed(MinecraftServer server, UUID player,
                                                           Entity villager) {
        try {
            if (villager == null) {
                return OpinionResult.unavailable(OpinionResult.OpinionAvailability.UNRESOLVED);
            }
            Optional<CommunityKey> community = resolveCommunity(villager);
            if (community.isEmpty()) {
                return OpinionResult.unavailable(OpinionResult.OpinionAvailability.UNRESOLVED);
            }
            OpinionResult result = getVillagerOpinionDetailed(server, player, villager.getUUID(),
                    community.get());
            return new OpinionResult(result.availability(), result.opinion()
                    .map(opinion -> new VillagerOpinion(opinion.villagerId(),
                            villager.getName().getString(), opinion.community(), opinion.opinion(),
                            opinion.tierId(), opinion.basis(), opinion.knownIncidents())));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] getVillagerOpinion(entity) failed; returning "
                    + "unresolved", t);
            return OpinionResult.unavailable(OpinionResult.OpinionAvailability.UNRESOLVED);
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

    /**
     * Records one deed under a producer-owned operation identity, and answers with a receipt
     * (§5 F03, §6 "Incident delivery").
     *
     * <p>Use this instead of {@link #record} when the delivery has to be exactly-once across a crash.
     * Replaying the same {@code namespace + player + community + operationKey} returns the first
     * delivery's answer — including for an operation that produced no incident at all, which a dedupe
     * key alone cannot express. {@link #receiptFloor} says how far back that memory reaches.
     *
     * @since MCA: Reputation 0.4.1
     */
    public static DeliveryOutcome deliver(IncidentDelivery delivery) {
        if (delivery != null && !integrationEnabled(delivery.request().source())) {
            return DeliveryOutcome.of(ReceiptOutcome.REFUSED_DISABLED,
                    ReputationResult.rejected(ReputationResult.Reason.DISABLED,
                            delivery.request().community()));
        }
        return ReputationService.deliver(delivery);
    }

    /**
     * The receipt for one operation, or empty when this side has no memory of it. Strictly read-only:
     * it creates no player record, reconciles nothing, and posts no event.
     *
     * <p>The namespaced identity is tried first and the legacy unnamespaced form second, so an
     * operation key written before receipts existed still resolves.
     *
     * @since MCA: Reputation 0.4.1
     */
    public static Optional<ReceiptView> findReceipt(MinecraftServer server, String namespace, UUID player,
                                                    CommunityKey community, String operationKey) {
        try {
            return ReputationService.findReceipt(server, namespace, player, community, operationKey);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] findReceipt failed; returning empty", t);
            return Optional.empty();
        }
    }

    /**
     * One incident, read exactly as stored. Strictly read-only, which is the whole point: the probe
     * this replaces could create the record it was asked about.
     *
     * @since MCA: Reputation 0.4.1
     */
    public static Optional<ReputationIncidentView> findIncident(MinecraftServer server, UUID player,
                                                                CommunityKey community, UUID incidentId) {
        try {
            return ReputationService.findIncidentView(server, player, community, incidentId);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] findIncident failed; returning empty", t);
            return Optional.empty();
        }
    }

    /**
     * The oldest occurrence time this player's receipts can still answer for, or empty while nothing
     * has been forgotten. A producer whose operation predates the floor knows it must recover
     * explicitly rather than trusting a "never seen" answer (§5 F09).
     *
     * @since MCA: Reputation 0.4.1
     */
    public static OptionalLong receiptFloor(MinecraftServer server, UUID player) {
        try {
            return ReputationService.receiptFloor(server, player);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] receiptFloor failed; returning empty", t);
            return OptionalLong.empty();
        }
    }

    /**
     * Records a deed that absorbs an earlier one, as one encounter rather than two (§5 F05).
     *
     * <p>The seam the native fatal-encounter path itself uses, so a producer that owns crime files the
     * same shape of transaction: one fold, one record, one standing change. When the spec does not
     * validate the successor is still recorded on its own — a real deed is never dropped for a
     * bookkeeping mismatch.
     *
     * @since MCA: Reputation 0.4.1
     */
    public static ReputationResult recordSuperseding(ReputationRequest successor, SupersedeSpec spec) {
        if (successor != null && !integrationEnabled(successor.source())) {
            return ReputationResult.rejected(ReputationResult.Reason.DISABLED, successor.community());
        }
        return ReputationService.recordSuperseding(successor, spec);
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

    /**
     * The same, with a speaker so a {@code known_to_speaker} selector can be evaluated rather than
     * refused (§6 "Speaker-aware query").
     *
     * @since MCA: Reputation 0.4.1
     */
    public static ResolutionResult resolveBySelector(MinecraftServer server, UUID player,
                                                     CommunityKey community, IncidentQuery selector,
                                                     SpeakerContext speaker, IncidentStatus status,
                                                     ResourceLocation source) {
        if (!integrationEnabled(source)) {
            return ResolutionResult.notApplied(ResolutionResult.Reason.DISABLED);
        }
        return ReputationService.resolveBySelector(server, player, community, selector, speaker, status,
                source, gameTime(server));
    }

    /**
     * Resolves one incident named by id, exactly once per operation key (§6 "Bound resolution").
     *
     * <p>What a committed reward should use. A selector discovers an incident; this one binds to the
     * one already discovered, so a retry after a crash cannot pick a different record, and a replay of
     * the same {@code operationKey} returns the settled answer without moving anything a second time.
     * A superseded record is refused: its weight belongs to the incident that absorbed it.
     *
     * @param source names the producer; its namespace scopes the operation key
     * @since MCA: Reputation 0.4.1
     */
    public static ResolutionResult resolveBound(MinecraftServer server, UUID player, CommunityKey community,
                                                UUID incidentId, IncidentStatus status,
                                                ResourceLocation source, String operationKey) {
        if (!integrationEnabled(source)) {
            return ResolutionResult.notApplied(ResolutionResult.Reason.DISABLED);
        }
        return ReputationService.resolveBound(server, player, community, incidentId, status, source,
                operationKey, gameTime(server));
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

    /**
     * Every global title this player holds, for a caller with no village in hand.
     *
     * <p>Strictly read-only and record-safe: an offline or entirely unknown player answers with an
     * empty set, and no record is created to say so.
     *
     * @since MCA: Reputation 0.4.1
     */
    public static Set<ResourceLocation> globalTitles(MinecraftServer server, UUID player) {
        try {
            if (server == null || player == null) {
                return Set.of();
            }
            return TitleService.globalTitles(server, player);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] globalTitles failed; returning empty", t);
            return Set.of();
        }
    }

    /**
     * The highest tier this player has ever reached with a community on one ladder (§6 "Title
     * synchronization"). Empty when they have never been ranked on it.
     *
     * @since MCA: Reputation 0.4.1
     */
    public static Optional<String> highWaterTierId(MinecraftServer server, UUID player,
                                                   CommunityKey community, ResourceLocation ladder) {
        try {
            return ReputationService.tierHighWater(server, player, community, ladder);
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] highWaterTierId failed; returning empty", t);
            return Optional.empty();
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

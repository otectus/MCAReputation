package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.api.ImportResult;
import dev.otectus.mcareputation.api.IncidentQuery;
import dev.otectus.mcareputation.api.LegacyImportRequest;
import dev.otectus.mcareputation.api.ReputationIncidentView;
import dev.otectus.mcareputation.api.ReputationMirror;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.api.ReputationSnapshot;
import dev.otectus.mcareputation.api.ResolutionResult;
import dev.otectus.mcareputation.api.event.ReputationChangedEvent;
import dev.otectus.mcareputation.api.event.ReputationIncidentCreatedEvent;
import dev.otectus.mcareputation.api.event.ReputationIncidentResolvedEvent;
import dev.otectus.mcareputation.api.event.ReputationTierChangedEvent;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityMetadata;
import dev.otectus.mcareputation.incident.AwarenessResolver;
import dev.otectus.mcareputation.incident.BuiltinIncidents;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.network.SnapshotSelection;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import dev.otectus.mcareputation.state.PlayerReputationRecord;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single funnel through which every change to canonical standing passes (spec §8, §18).
 *
 * <p>"Single funnel" is not a style preference. Standing, the tier a score falls in, the high-water
 * mark that decides whether a milestone is new, the title that milestone grants, the mirror copy an
 * add-on keeps, the events other mods react to, and the message the player sees are six facts that
 * must agree. The only reliable way to keep them agreeing is for exactly one method to derive them
 * from each other, in a fixed order, every time — which is what {@link #record} does. No participating
 * mod, including this one, mutates the store directly.
 *
 * <h2>Transaction order</h2>
 *
 * <p>Fixed by §18 and by the dependencies between the steps:
 *
 * <ol>
 *   <li>assert the server thread;</li>
 *   <li>validate and bound the request;</li>
 *   <li>resolve the incident definition;</li>
 *   <li>check dedupe — an already-seen key returns the earlier outcome and stops here;</li>
 *   <li>reconcile outstanding decay, so the "before" score is the real one;</li>
 *   <li>compute visibility and the effective delta;</li>
 *   <li>create and store the incident;</li>
 *   <li>update the score and the cached community metadata;</li>
 *   <li>resolve old and new tier, and update the high-water mark;</li>
 *   <li>grant any newly earned title;</li>
 *   <li>mark the save dirty;</li>
 *   <li>notify mirrors — after the canonical commit, never before;</li>
 *   <li>post the Forge events;</li>
 *   <li>player feedback, which rides on those events (see {@code ReputationFeedback}).</li>
 * </ol>
 *
 * <p>Steps 12–14 are outside the canonical commit on purpose: a mirror or a listener that throws is
 * caught and logged, and the committed transaction still stands (§18). Validation failure in steps
 * 2–6 applies nothing at all.
 *
 * <p>Every public entry point takes a {@link MinecraftServer}; the internals run against a
 * {@link ServiceContext} so the whole transaction is exercised by unit tests with no game running.
 */
public final class ReputationService {

    /** Registered fallback sinks (§25.1). Copy-on-write: registered at setup, read on every commit. */
    private static final List<ReputationMirror> MIRRORS = new CopyOnWriteArrayList<>();

    private ReputationService() {
    }

    // ------------------------------------------------------------------
    // Mirrors
    // ------------------------------------------------------------------

    public static void registerMirror(ReputationMirror mirror) {
        if (mirror == null) {
            return;
        }
        // Dedupe by name, not equals(): mirror implementations rarely override equals, and the same
        // logical mirror registered twice would write every score twice.
        for (ReputationMirror existing : MIRRORS) {
            if (existing.mirrorName().equals(mirror.mirrorName())) {
                McaReputation.LOGGER.warn("[MCA: Reputation] a mirror named '{}' is already registered; "
                        + "ignoring the duplicate", mirror.mirrorName());
                return;
            }
        }
        MIRRORS.add(mirror);
        McaReputation.LOGGER.info("[MCA: Reputation] registered fallback mirror '{}'", mirror.mirrorName());
    }

    public static void unregisterMirror(ReputationMirror mirror) {
        MIRRORS.remove(mirror);
    }

    public static List<ReputationMirror> mirrors() {
        return List.copyOf(MIRRORS);
    }

    // ------------------------------------------------------------------
    // Recording a deed
    // ------------------------------------------------------------------

    /**
     * Records one deed. The entry point for every source of standing change.
     *
     * <p>Never throws for a caller's benefit: an integration evaluating a dialogue branch or claiming a
     * quest reward must not be able to crash because of this call (§25). A refusal comes back as a
     * {@link ReputationResult} with {@code applied = false} and a {@link ReputationResult.Reason}.
     */
    public static ReputationResult record(ReputationRequest request) {
        return recordWith(null, request);
    }

    /** Seam entry point: {@code ctx} may replace the server for tests; null derives it from the request. */
    static ReputationResult recordWith(@Nullable ServiceContext ctx, ReputationRequest request) {
        try {
            ServiceContext context = ctx != null ? ctx : ServiceContext.of(request.server());
            return recordInternal(context, request);
        } catch (Throwable t) {
            McaReputation.LOGGER.error("[MCA: Reputation] transaction failed for player {} incident {}; "
                    + "nothing was written", request == null ? "?" : request.playerId(),
                    request == null ? "?" : request.incidentType(), t);
            return ReputationResult.rejected(ReputationResult.Reason.ERROR,
                    request == null ? null : request.community());
        }
    }

    private static ReputationResult recordInternal(ServiceContext ctx, ReputationRequest request) {
        // 1. server thread
        if (!ctx.isServerThread()) {
            McaReputation.LOGGER.error("[MCA: Reputation] record() called off the server thread from {}; "
                            + "refusing. Writes are server-thread only (spec 25).",
                    request.source());
            return ReputationResult.rejected(ReputationResult.Reason.INVALID, request.community());
        }
        if (!McaReputationConfig.enabled()) {
            return ReputationResult.rejected(ReputationResult.Reason.DISABLED, request.community());
        }

        // 3. definition
        Optional<IncidentDefinition> maybeDefinition = IncidentRegistry.get(request.incidentType());
        if (maybeDefinition.isEmpty()) {
            McaReputation.LOGGER.warn("[MCA: Reputation] {} asked to record unknown incident type {}; ignoring",
                    request.source(), request.incidentType());
            return ReputationResult.rejected(ReputationResult.Reason.UNKNOWN_INCIDENT, request.community());
        }
        IncidentDefinition definition = maybeDefinition.get();

        ReputationSavedData data = ctx.data();
        PlayerReputationRecord playerRecord = data.getOrCreatePlayer(request.playerId());
        CommunityKey community = request.community();
        int minScore = McaReputationConfig.minimumScore();
        int maxScore = McaReputationConfig.maximumScore();

        // 4. dedupe — before anything is created or reconciled, so a duplicate is genuinely free
        if (request.dedupeKey().isPresent()) {
            Optional<IncidentRecord> existing =
                    playerRecord.findByDedupeKey(community, request.dedupeKey().get());
            if (existing.isPresent()) {
                CommunityReputationRecord existingCommunity = playerRecord.getOrCreate(community);
                // The refusal still reports the community's *current* standing, so bring decay up to
                // date first — a DUPLICATE answer with a stale score would disagree with the very next
                // query.
                if (McaReputationConfig.scoreDecayEnabled()
                        && existingCommunity.reconcile(request.gameTime(), minScore, maxScore)) {
                    data.setDirty();
                }
                if (McaReputationConfig.debugLogging()) {
                    McaReputation.LOGGER.debug("[MCA: Reputation] dedupe refused {} from {} (key '{}')",
                            request.incidentType(), request.source(), request.dedupeKey().get());
                }
                // Hand back the id the first attempt produced. A companion that crashed between our
                // commit and its own link write can only repair itself if the replay tells it what
                // already exists (integration spec 7.5).
                return ReputationResult.duplicate(existing.get().id(), community,
                        existingCommunity.score(), currentTierId(existingCommunity.score()));
            }
        }

        CommunityReputationRecord communityRecord = playerRecord.getOrCreate(community);

        // 5. reconcile outstanding decay so oldScore is honest
        if (McaReputationConfig.scoreDecayEnabled()) {
            communityRecord.reconcile(request.gameTime(), minScore, maxScore);
        }
        int oldScore = communityRecord.score();
        String oldTierId = currentTierId(oldScore);

        // 6. visibility and effective delta
        IncidentVisibility visibility = request.visibilityOverride().orElse(definition.visibility());
        if (visibility == IncidentVisibility.GLOBAL_RESERVED) {
            // §19.2: reserved for a future fame system; behaves as VILLAGE in this version, noted so
            // a pack author testing "global" content can see it collapse.
            McaReputation.LOGGER.debug("[MCA: Reputation] {} used reserved visibility 'global' for {}; "
                    + "treated as 'village' in this version", request.source(), request.incidentType());
        }
        int delta = definition.resolveDelta(request.deltaOverride());
        if (visibility.effective() == IncidentVisibility.PRIVATE && !definition.allowPrivateScore()) {
            // An override cannot open a scoring path the definition itself may not declare (§14.1).
            delta = 0;
        }

        // A witnessed deed nobody saw has no public consequence. Depending on the definition it is
        // either dropped entirely or kept as hidden, zero-contribution history (§19.1) — which is how
        // an unwitnessed killing stays in the world's memory without changing anyone's opinion.
        boolean unwitnessed = visibility.effective() == IncidentVisibility.WITNESSED
                && request.witnesses().isEmpty();
        if (unwitnessed) {
            if (!definition.retainUnwitnessed()) {
                if (McaReputationConfig.debugLogging()) {
                    McaReputation.LOGGER.debug("[MCA: Reputation] {} went unwitnessed and is not retained; "
                            + "no public change", request.incidentType());
                }
                return ReputationResult.notApplied(ReputationResult.Reason.UNWITNESSED, community,
                        oldScore, oldTierId);
            }
            delta = 0;
            visibility = IncidentVisibility.PRIVATE;
        }

        // 7. create and store
        IncidentRecord incident = IncidentRecord.create(UUID.randomUUID(), request.incidentType(),
                request.playerId(), community, request.gameTime(), request.source(), request.dedupeKey(),
                delta, visibility, definition.severity(), request.subjects());
        incident.addWitnesses(request.witnesses());
        incident.putContext(request.context());
        incident.setPinned(definition.pinned());
        communityRecord.addIncident(incident);
        playerRecord.indexDedupe(incident);

        // 8. score and bounds
        communityRecord.recomputeScore(minScore, maxScore);
        communityRecord.prune(McaReputationConfig.maxIncidentsPerCommunity(), request.gameTime(),
                minScore, maxScore);
        playerRecord.enforcePlayerIncidentCap(McaReputationConfig.maxIncidentsPerPlayer(),
                request.gameTime(), minScore, maxScore);
        int newScore = communityRecord.score();
        int appliedDelta = newScore - oldScore;

        // 9-10. tier, high-water, title
        @Nullable ServerPlayer player = ctx.onlinePlayer(request.playerId());
        if (player != null) {
            playerRecord.setLastKnownName(player.getGameProfile().getName());
        }
        TierOutcome tier = applyTierTransition(ctx, playerRecord, communityRecord, player,
                oldScore, newScore, request.gameTime());

        // 11. persist
        data.setDirty();

        // 12-13. mirrors, then events — both outside the canonical commit
        notifyMirrors(request.playerId(), community, communityRecord);
        ReputationIncidentView view = ReputationIncidentView.of(incident, request.gameTime());
        postSafely(ctx, new ReputationIncidentCreatedEvent(request.playerId(), player, view));
        if (appliedDelta != 0) {
            postSafely(ctx, new ReputationChangedEvent(request.playerId(), player, community, oldScore,
                    newScore, appliedDelta, incident.id(), incident.type(), request.source()));
        }
        tier.post(ctx, request.playerId(), player, community);

        if (McaReputationConfig.debugLogging()) {
            McaReputation.LOGGER.debug("[MCA: Reputation] {} recorded {} for {} in {}: {} -> {} ({}{}), tier {}",
                    request.source(), request.incidentType(), request.playerId(), community.asString(),
                    oldScore, newScore, appliedDelta >= 0 ? "+" : "", appliedDelta, tier.newTierId);
        }
        return ReputationResult.applied(incident.id(), community, oldScore, newScore, appliedDelta,
                oldTierId, tier.newTierId, tier.firstTime);
    }

    // ------------------------------------------------------------------
    // Resolving a deed
    // ------------------------------------------------------------------

    /**
     * Moves an incident to a stronger status, adjusting its contribution by the definition's
     * resolution multiplier (§15.2).
     *
     * <p>Idempotent by construction: a status that is not strictly stronger than the current one is
     * refused with {@link ResolutionResult.Reason#NOT_STRONGER} and nothing moves, which is what stops
     * a repeatable restitution quest from paying its reduction more than once.
     */
    public static ResolutionResult resolve(MinecraftServer server, UUID playerId, CommunityKey community,
                                           UUID incidentId, IncidentStatus status, ResourceLocation source,
                                           long gameTime) {
        return resolveWith(ServiceContext.of(server), playerId, community, incidentId, status, source,
                gameTime);
    }

    static ResolutionResult resolveWith(ServiceContext ctx, UUID playerId, CommunityKey community,
                                        UUID incidentId, IncidentStatus status, ResourceLocation source,
                                        long gameTime) {
        try {
            if (!ctx.isServerThread()) {
                McaReputation.LOGGER.error("[MCA: Reputation] resolve() called off the server thread from {}; "
                        + "refusing", source);
                return ResolutionResult.notApplied(ResolutionResult.Reason.INVALID);
            }
            if (!McaReputationConfig.enabled()) {
                return ResolutionResult.notApplied(ResolutionResult.Reason.DISABLED);
            }
            ReputationSavedData data = ctx.data();
            Optional<CommunityReputationRecord> maybeCommunity = data.player(playerId)
                    .flatMap(record -> record.community(community));
            if (maybeCommunity.isEmpty()) {
                return ResolutionResult.notApplied(ResolutionResult.Reason.NOT_FOUND);
            }
            CommunityReputationRecord communityRecord = maybeCommunity.get();
            Optional<IncidentRecord> maybeIncident = communityRecord.incident(incidentId);
            if (maybeIncident.isEmpty()) {
                return ResolutionResult.notApplied(ResolutionResult.Reason.NOT_FOUND);
            }
            IncidentRecord incident = maybeIncident.get();
            IncidentDefinition definition = IncidentRegistry.getOrUnknown(incident.type());

            int minScore = McaReputationConfig.minimumScore();
            int maxScore = McaReputationConfig.maximumScore();
            if (McaReputationConfig.scoreDecayEnabled()) {
                communityRecord.reconcile(gameTime, minScore, maxScore);
            }

            int oldScore = communityRecord.score();
            IncidentStatus oldStatus = incident.status();
            int oldContribution = incident.currentContribution();

            Optional<Integer> moved = incident.resolve(definition.resolution(), definition.decay(),
                    status, gameTime);
            if (moved.isEmpty()) {
                return ResolutionResult.notApplied(ResolutionResult.Reason.NOT_STRONGER);
            }
            incident.putContext("resolved_by", source.toString());
            communityRecord.recomputeScore(minScore, maxScore);
            int newScore = communityRecord.score();

            @Nullable ServerPlayer player = ctx.onlinePlayer(playerId);
            PlayerReputationRecord playerRecord = data.getOrCreatePlayer(playerId);
            TierOutcome tier = applyTierTransition(ctx, playerRecord, communityRecord, player,
                    oldScore, newScore, gameTime);
            data.setDirty();

            notifyMirrors(playerId, community, communityRecord);
            ReputationIncidentView view = ReputationIncidentView.of(incident, gameTime);
            postSafely(ctx, new ReputationIncidentResolvedEvent(playerId, player, view, oldStatus,
                    oldContribution, source));
            if (newScore != oldScore) {
                postSafely(ctx, new ReputationChangedEvent(playerId, player, community, oldScore, newScore,
                        newScore - oldScore, incident.id(), incident.type(), source));
            }
            tier.post(ctx, playerId, player, community);

            McaReputation.LOGGER.debug("[MCA: Reputation] {} resolved {} to {} for {} in {}: {} -> {}",
                    source, incident.type(), status.jsonName(), playerId, community.asString(),
                    oldScore, newScore);
            return ResolutionResult.applied(incident.id(), oldStatus, status, oldContribution,
                    incident.currentContribution(), oldScore, newScore);
        } catch (Throwable t) {
            McaReputation.LOGGER.error("[MCA: Reputation] resolve() failed for player {} incident {}; "
                    + "nothing was written", playerId, incidentId, t);
            return ResolutionResult.notApplied(ResolutionResult.Reason.ERROR);
        }
    }

    /**
     * Resolves the single incident a selector picks out (§29.6). The selection is server-side and
     * deterministic — newest first, incident id as tiebreak — so the same quest reward against the
     * same ledger always targets the same record and can never be steered by a client.
     */
    public static ResolutionResult resolveBySelector(MinecraftServer server, UUID playerId,
                                                     CommunityKey community, IncidentQuery selector,
                                                     IncidentStatus status, ResourceLocation source,
                                                     long gameTime) {
        return resolveBySelectorWith(ServiceContext.of(server), playerId, community, selector, status,
                source, gameTime);
    }

    static ResolutionResult resolveBySelectorWith(ServiceContext ctx, UUID playerId,
                                                  CommunityKey community, IncidentQuery selector,
                                                  IncidentStatus status, ResourceLocation source,
                                                  long gameTime) {
        if (selector == null || selector.isEmpty()) {
            McaReputation.LOGGER.warn("[MCA: Reputation] {} tried to resolve an incident with an empty "
                    + "selector; refusing to pick arbitrarily", source);
            return ResolutionResult.notApplied(ResolutionResult.Reason.INVALID);
        }
        if (selector.knownToSpeaker()) {
            // Awareness needs a specific villager to evaluate against, and a resolution has none.
            // Silently ignoring the flag would resolve an incident the selector's author believed
            // was filtered — refuse instead.
            McaReputation.LOGGER.warn("[MCA: Reputation] {} used known_to_speaker in a resolve "
                    + "selector; a resolution has no speaker, refusing", source);
            return ResolutionResult.notApplied(ResolutionResult.Reason.INVALID);
        }
        List<ReputationIncidentView> candidates = selector.select(recentIncidentsWith(ctx, playerId,
                community, Integer.MAX_VALUE, gameTime));
        if (candidates.isEmpty()) {
            return ResolutionResult.notApplied(ResolutionResult.Reason.NOT_FOUND);
        }
        return resolveWith(ctx, playerId, community, candidates.get(0).id(), status, source, gameTime);
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** A player's standing, or empty when they have no record for this community. */
    public static OptionalInt score(MinecraftServer server, UUID playerId, CommunityKey community) {
        if (server == null || playerId == null || community == null) {
            return OptionalInt.empty();
        }
        return ReputationSavedData.get(server).player(playerId)
                .flatMap(record -> record.community(community))
                .map(record -> OptionalInt.of(record.score()))
                .orElseGet(OptionalInt::empty);
    }

    /** A player's standing, treating an unknown community as {@code 0} — everyone starts a stranger. */
    public static int scoreOrZero(MinecraftServer server, UUID playerId, CommunityKey community) {
        return score(server, playerId, community).orElse(0);
    }

    /**
     * The full snapshot for one player and community, reconciling decay first so the numbers are
     * current. Empty when the player has no record here.
     */
    public static Optional<ReputationSnapshot> snapshot(MinecraftServer server, UUID playerId,
                                                        CommunityKey community, long gameTime) {
        if (server == null || playerId == null || community == null) {
            return Optional.empty();
        }
        return snapshotWith(ServiceContext.of(server), playerId, community, gameTime);
    }

    static Optional<ReputationSnapshot> snapshotWith(ServiceContext ctx, UUID playerId,
                                                     CommunityKey community, long gameTime) {
        if (playerId == null || community == null) {
            return Optional.empty();
        }
        ReputationSavedData data = ctx.data();
        Optional<PlayerReputationRecord> maybePlayer = data.player(playerId);
        if (maybePlayer.isEmpty()) {
            return Optional.empty();
        }
        PlayerReputationRecord playerRecord = maybePlayer.get();
        Optional<CommunityReputationRecord> maybeCommunity = playerRecord.community(community);
        if (maybeCommunity.isEmpty()) {
            return Optional.empty();
        }
        CommunityReputationRecord communityRecord = maybeCommunity.get();
        if (ctx.isServerThread() && McaReputationConfig.scoreDecayEnabled()
                && communityRecord.reconcile(gameTime, McaReputationConfig.minimumScore(),
                        McaReputationConfig.maximumScore())) {
            data.setDirty();
        }
        return Optional.of(buildSnapshot(playerRecord, communityRecord, gameTime));
    }

    private static ReputationSnapshot buildSnapshot(PlayerReputationRecord playerRecord,
                                                    CommunityReputationRecord communityRecord, long gameTime) {
        ReputationTierSet ladder = ReputationTiers.getDefault();
        int score = communityRecord.score();
        List<ReputationIncidentView> views = communityRecord.incidentsNewestFirst().stream()
                .map(incident -> ReputationIncidentView.of(incident, gameTime))
                .toList();
        return new ReputationSnapshot(
                communityRecord.key(),
                communityRecord.metadata(),
                score,
                communityRecord.baseline(),
                ReputationTiers.DEFAULT_ID,
                ladder.tierFor(score),
                ladder.nextTier(score),
                communityRecord.tierHighWater(ReputationTiers.DEFAULT_ID),
                communityRecord.titles(),
                playerRecord.globalTitles(),
                views,
                views.size());
    }

    /** Every community this player has a record for, best standing first. */
    public static List<ReputationSnapshot> knownCommunities(MinecraftServer server, UUID playerId,
                                                            long gameTime) {
        return knownCommunitiesWith(ServiceContext.of(server), playerId, gameTime);
    }

    static List<ReputationSnapshot> knownCommunitiesWith(ServiceContext ctx, UUID playerId, long gameTime) {
        ReputationSavedData data = ctx.data();
        Optional<PlayerReputationRecord> maybePlayer = data.player(playerId);
        if (maybePlayer.isEmpty()) {
            return List.of();
        }
        PlayerReputationRecord playerRecord = maybePlayer.get();
        // Same reconcile discipline as snapshot(): the community list feeds the screen's selector and
        // the Journal, and §15.1 promises no read path shows a stale, un-decayed number.
        if (ctx.isServerThread() && McaReputationConfig.scoreDecayEnabled()) {
            boolean changed = false;
            int minScore = McaReputationConfig.minimumScore();
            int maxScore = McaReputationConfig.maximumScore();
            for (CommunityReputationRecord community : playerRecord.communities()) {
                changed |= community.reconcile(gameTime, minScore, maxScore);
            }
            if (changed) {
                data.setDirty();
            }
        }
        List<ReputationSnapshot> out = new ArrayList<>();
        for (CommunityReputationRecord community : playerRecord.communitiesByStanding()) {
            out.add(buildSnapshot(playerRecord, community, gameTime));
        }
        return out;
    }

    /**
     * The community to answer for when nobody named one: where the player is standing, but only if
     * they have a history there; otherwise the standing they actually have (§27.2, §28.2).
     *
     * <p>Lives here rather than inside the packet handler because two callers now ask the same
     * question — the standing screen's reply and the scoreboard/tab-list display — and DIAGNOSIS.md
     * §2 hop 7b is what happens when that question is answered in two places. {@link SnapshotSelection}
     * still holds the policy; this method is only the store lookups it needs.
     *
     * @param here the community the player is standing in, if any
     */
    public static Optional<CommunityKey> unpromptedCommunity(MinecraftServer server, UUID playerId,
                                                             Optional<CommunityKey> here) {
        long gameTime = server.overworld().getGameTime();
        boolean knowsHere = here.isPresent()
                && ReputationSavedData.get(server).knows(playerId, here.get());
        Optional<CommunityKey> bestKnown = knownCommunities(server, playerId, gameTime).stream()
                .findFirst()
                .map(ReputationSnapshot::community);
        return SnapshotSelection.unprompted(here, knowsHere, bestKnown);
    }

    /** Recent incidents, newest first, capped at {@code limit}. */
    public static List<ReputationIncidentView> recentIncidents(MinecraftServer server, UUID playerId,
                                                               CommunityKey community, int limit,
                                                               long gameTime) {
        return recentIncidentsWith(ServiceContext.of(server), playerId, community, limit, gameTime);
    }

    static List<ReputationIncidentView> recentIncidentsWith(ServiceContext ctx, UUID playerId,
                                                            CommunityKey community, int limit,
                                                            long gameTime) {
        ReputationSavedData data = ctx.data();
        return data.player(playerId)
                .flatMap(record -> record.community(community))
                .map(record -> {
                    // Reconcile first, like snapshot(): a deed list showing pre-decay contributions
                    // would disagree with the score printed beside it.
                    if (ctx.isServerThread() && McaReputationConfig.scoreDecayEnabled()
                            && record.reconcile(gameTime, McaReputationConfig.minimumScore(),
                                    McaReputationConfig.maximumScore())) {
                        data.setDirty();
                    }
                    return record.incidentsNewestFirst().stream()
                            .limit(Math.max(0, limit))
                            .map(incident -> ReputationIncidentView.of(incident, gameTime))
                            .toList();
                })
                .orElseGet(List::of);
    }

    public static Optional<IncidentRecord> incident(MinecraftServer server, UUID playerId,
                                                    CommunityKey community, UUID incidentId) {
        return incidentWith(ServiceContext.of(server), playerId, community, incidentId);
    }

    static Optional<IncidentRecord> incidentWith(ServiceContext ctx, UUID playerId,
                                                 CommunityKey community, UUID incidentId) {
        return ctx.data().player(playerId)
                .flatMap(record -> record.community(community))
                .flatMap(record -> record.incident(incidentId));
    }

    /**
     * The most recent incident of a type naming this subject within a time window. Backs the
     * assault → killing upgrade (§20.2) and the assault coalescing window (§20.1).
     */
    public static Optional<IncidentRecord> findRecent(MinecraftServer server, UUID playerId,
                                                      CommunityKey community, ResourceLocation type,
                                                      @Nullable UUID subjectUuid, long gameTime,
                                                      long withinTicks) {
        return findRecentWith(ServiceContext.of(server), playerId, community, type, subjectUuid,
                gameTime, withinTicks);
    }

    static Optional<IncidentRecord> findRecentWith(ServiceContext ctx, UUID playerId,
                                                   CommunityKey community, ResourceLocation type,
                                                   @Nullable UUID subjectUuid, long gameTime,
                                                   long withinTicks) {
        return ctx.data().player(playerId)
                .flatMap(record -> record.community(community))
                .flatMap(record -> record.incidentsNewestFirst().stream()
                        .filter(incident -> incident.type().equals(type))
                        .filter(incident -> gameTime - incident.createdGameTime() <= withinTicks)
                        .filter(incident -> subjectUuid == null || incident.subjects().stream()
                                .anyMatch(subject -> subject.uuid().map(subjectUuid::equals).orElse(false)))
                        .findFirst());
    }

    /** Whether a villager knows about an incident (§19.3). Reputation's half of the gossip contract. */
    public static boolean villagerKnows(MinecraftServer server, UUID playerId, CommunityKey community,
                                        UUID incidentId, UUID villagerUuid, boolean resident, long gameTime) {
        return incident(server, playerId, community, incidentId)
                .map(incident -> AwarenessResolver.knows(incident, villagerUuid, resident, gameTime,
                        McaReputationConfig.minRumorDelayTicks(), McaReputationConfig.maxRumorDelayTicks()))
                .orElse(false);
    }

    // ------------------------------------------------------------------
    // Administrative and maintenance
    // ------------------------------------------------------------------

    /**
     * Sets a player's standing directly by adjusting the non-decaying baseline (§17.1).
     *
     * <p>Baseline is deliberately separate from the ledger: an administrator setting a number is not
     * a deed, and dressing it up as one would put a fictional entry in the player's history and make
     * the ledger stop explaining the score. Every call is logged with executor context by the command.
     */
    public static ReputationResult setScore(MinecraftServer server, UUID playerId, CommunityKey community,
                                            int targetScore, ResourceLocation source, long gameTime) {
        return adjustBaseline(ServiceContext.of(server), playerId, community, targetScore, true, source,
                gameTime);
    }

    /** Adds to a player's standing through the baseline. See {@link #setScore}. */
    public static ReputationResult addScore(MinecraftServer server, UUID playerId, CommunityKey community,
                                            int delta, ResourceLocation source, long gameTime) {
        return adjustBaseline(ServiceContext.of(server), playerId, community, delta, false, source,
                gameTime);
    }

    static ReputationResult adjustBaseline(ServiceContext ctx, UUID playerId,
                                           CommunityKey community, int amount, boolean absolute,
                                           ResourceLocation source, long gameTime) {
        try {
            if (!ctx.isServerThread()) {
                return ReputationResult.rejected(ReputationResult.Reason.INVALID, community);
            }
            int minScore = McaReputationConfig.minimumScore();
            int maxScore = McaReputationConfig.maximumScore();
            ReputationSavedData data = ctx.data();
            PlayerReputationRecord playerRecord = data.getOrCreatePlayer(playerId);
            CommunityReputationRecord communityRecord = playerRecord.getOrCreate(community);
            if (McaReputationConfig.scoreDecayEnabled()) {
                communityRecord.reconcile(gameTime, minScore, maxScore);
            }

            int oldScore = communityRecord.score();
            String oldTierId = currentTierId(oldScore);
            if (absolute) {
                // Target the whole score, so the baseline absorbs whatever the ledger currently
                // contributes and the player ends up exactly where the administrator asked. The
                // ledger's true, unclamped sum — not "score minus baseline", which is distorted
                // whenever the clamp is engaged and would land the player somewhere else.
                communityRecord.setBaseline((long) amount - communityRecord.contributionSum(),
                        minScore, maxScore);
            } else {
                communityRecord.addBaseline(amount, minScore, maxScore);
            }
            int newScore = communityRecord.score();

            @Nullable ServerPlayer player = ctx.onlinePlayer(playerId);
            TierOutcome tier = applyTierTransition(ctx, playerRecord, communityRecord, player,
                    oldScore, newScore, gameTime);
            data.setDirty();

            notifyMirrors(playerId, community, communityRecord);
            if (newScore != oldScore) {
                postSafely(ctx, new ReputationChangedEvent(playerId, player, community, oldScore, newScore,
                        newScore - oldScore, null, null, source));
            }
            tier.post(ctx, playerId, player, community);

            McaReputation.LOGGER.info("[MCA: Reputation] AUDIT {} {} baseline for {} in {}: {} -> {}",
                    source, absolute ? "set" : "adjusted", playerId, community.asString(), oldScore, newScore);
            // The synthetic id names the whole adjustment — player and community included, so two
            // same-tick commands against different targets do not report the same id.
            return ReputationResult.applied(UUID.nameUUIDFromBytes(
                            ("baseline:" + playerId + ":" + community.asString() + ":" + source + ":" + gameTime)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    community, oldScore, newScore, newScore - oldScore, oldTierId, tier.newTierId,
                    tier.firstTime);
        } catch (Throwable t) {
            McaReputation.LOGGER.error("[MCA: Reputation] baseline adjustment failed for {} in {}",
                    playerId, community, t);
            return ReputationResult.rejected(ReputationResult.Reason.ERROR, community);
        }
    }

    /** Caches freshly resolved village facts against a community the player already knows (§12.3). */
    public static void cacheCommunityMetadata(MinecraftServer server, UUID playerId, CommunityKey community,
                                              CommunityMetadata metadata) {
        cacheCommunityMetadataWith(ServiceContext.of(server), playerId, community, metadata);
    }

    static void cacheCommunityMetadataWith(ServiceContext ctx, UUID playerId, CommunityKey community,
                                           CommunityMetadata metadata) {
        ReputationSavedData data = ctx.data();
        data.player(playerId).flatMap(record -> record.community(community)).ifPresent(record -> {
            CommunityMetadata merged = record.metadata()
                    .refreshed(metadata.name(), metadata.center(), metadata.lastResolvedGameTime());
            if (!merged.equals(record.metadata())) {
                record.setMetadata(merged);
                data.setDirty();
            }
        });
    }

    /** Brings one player's decay up to date across every community they know (§15.1). */
    public static boolean reconcile(MinecraftServer server, UUID playerId, long gameTime) {
        return ReputationSavedData.get(server).reconcilePlayer(playerId, gameTime);
    }

    // ------------------------------------------------------------------
    // Legacy import
    // ------------------------------------------------------------------

    /**
     * Applies a one-time import of pre-Reputation standing (§32.2).
     *
     * <p>The migration marker is written <b>after</b> the store has successfully mutated, so a crash
     * mid-import leaves the player eligible to retry rather than marked done with nothing copied.
     * Once the marker exists, this method is a no-op forever — which is the guarantee that legacy
     * standing is never added twice, no matter how many times login or a command triggers it.
     */
    public static ImportResult importLegacy(LegacyImportRequest request) {
        return importLegacyWith(null, request);
    }

    /** Seam entry point: {@code ctx} may replace the server for tests; null derives it from the request. */
    static ImportResult importLegacyWith(@Nullable ServiceContext ctx, LegacyImportRequest request) {
        try {
            ServiceContext context = ctx != null ? ctx : ServiceContext.of(request.server());
            if (!context.isServerThread()) {
                return ImportResult.notApplied(ImportResult.Reason.ERROR);
            }
            if (!McaReputationConfig.migrateLegacyQuestsData()) {
                return ImportResult.notApplied(ImportResult.Reason.DISABLED);
            }
            ReputationSavedData data = context.data();
            // A preview must be pure. Consult without creating — and below, never write the marker on
            // a dry run: a preview that marked the player migrated would make the real import
            // impossible forever (§32.2).
            boolean alreadyMigrated = data.player(request.playerId())
                    .map(record -> record.hasMigrated(request.sourceId()))
                    .orElse(false);
            if (alreadyMigrated) {
                return ImportResult.notApplied(ImportResult.Reason.ALREADY_MIGRATED);
            }

            List<String> notes = new ArrayList<>();
            int baselineTotal = 0;
            int titleCount = 0;
            int highWaterCount = 0;
            for (var entry : request.baselines().entrySet()) {
                baselineTotal += entry.getValue();
                if (request.dryRun()) {
                    notes.add("would set baseline " + entry.getValue() + " for "
                            + entry.getKey().asString());
                }
            }
            for (var entry : request.tierHighWater().entrySet()) {
                highWaterCount += entry.getValue().size();
            }
            for (var entry : request.villageTitles().entrySet()) {
                titleCount += entry.getValue().size();
            }
            titleCount += request.globalTitles().size();

            if (request.dryRun()) {
                if (!request.hasAnything()) {
                    return ImportResult.notApplied(ImportResult.Reason.NOTHING_TO_IMPORT);
                }
                return ImportResult.dryRun(request.baselines().size(), baselineTotal, titleCount,
                        highWaterCount, notes);
            }

            PlayerReputationRecord playerRecord = data.getOrCreatePlayer(request.playerId());
            if (!request.hasAnything()) {
                // Still mark it done: "this player had nothing to import" is a completed migration,
                // and re-checking it on every login forever would be wasted work.
                playerRecord.markMigrated(request.sourceId(), request.sourceVersion());
                data.setDirty();
                return ImportResult.notApplied(ImportResult.Reason.NOTHING_TO_IMPORT);
            }

            int minScore = McaReputationConfig.minimumScore();
            int maxScore = McaReputationConfig.maximumScore();
            @Nullable ServerPlayer player = context.onlinePlayer(request.playerId());

            // Imported high-water first: it records what the legacy system already celebrated, and it
            // must be in place before any tier transition below decides "first time".
            for (var entry : request.tierHighWater().entrySet()) {
                for (var ladderEntry : entry.getValue().entrySet()) {
                    playerRecord.getOrCreate(entry.getKey())
                            .setTierHighWater(ladderEntry.getKey(), ladderEntry.getValue());
                }
            }

            record CommunityOutcome(CommunityKey community, int oldScore, int newScore, TierOutcome tier) {
            }
            List<CommunityOutcome> outcomes = new ArrayList<>();
            IncidentDefinition markerDefinition =
                    IncidentRegistry.getOrUnknown(BuiltinIncidents.LEGACY_BALANCE);
            for (var entry : request.baselines().entrySet()) {
                CommunityKey community = entry.getKey();
                int legacyScore = entry.getValue();
                CommunityReputationRecord communityRecord = playerRecord.getOrCreate(community);
                int oldScore = communityRecord.score();
                communityRecord.addBaseline(legacyScore, minScore, maxScore);
                request.communityName(community).ifPresent(name -> communityRecord.setMetadata(
                        communityRecord.metadata().refreshed(name, Optional.empty(), 0L)));
                int newScore = communityRecord.score();

                // The ledger line explaining the imported number (§32.2). Zero-delta: the score
                // itself lives in the baseline; this exists so the history can say where it came from.
                IncidentRecord marker = IncidentRecord.create(UUID.randomUUID(),
                        BuiltinIncidents.LEGACY_BALANCE,
                        request.playerId(), community, 0L,
                        BuiltinIncidents.SOURCE_MIGRATION,
                        Optional.of("legacy:" + request.sourceId() + ":" + community.asString()),
                        0, markerDefinition.visibility(), markerDefinition.severity(), List.of());
                marker.putContext("amount", String.valueOf(legacyScore));
                marker.putContext("source", request.sourceId());
                communityRecord.addIncident(marker);
                playerRecord.indexDedupe(marker);

                outcomes.add(new CommunityOutcome(community, oldScore, newScore,
                        applyTierTransition(context, playerRecord, communityRecord, player,
                                oldScore, newScore, 0L)));
            }

            // Titles migrate silently: they were earned and celebrated in the legacy system, so no
            // granted events fire for them here.
            for (var entry : request.villageTitles().entrySet()) {
                for (ResourceLocation title : entry.getValue()) {
                    playerRecord.getOrCreate(entry.getKey()).grantTitle(title);
                }
            }
            for (ResourceLocation title : request.globalTitles()) {
                playerRecord.grantGlobalTitle(title);
            }

            // Marker last: a crash before this point leaves the player retryable.
            playerRecord.markMigrated(request.sourceId(), request.sourceVersion());
            data.setDirty();

            // Mirrors, then events — both outside the canonical commit, like every other mutation.
            // ReputationChangedEvent documents that it fires for a legacy import, and tier
            // transitions must announce themselves the same way they would for a deed.
            for (CommunityOutcome outcome : outcomes) {
                playerRecord.community(outcome.community())
                        .ifPresent(record -> notifyMirrors(request.playerId(), outcome.community(), record));
            }
            for (CommunityOutcome outcome : outcomes) {
                if (outcome.newScore() != outcome.oldScore()) {
                    postSafely(context, new ReputationChangedEvent(request.playerId(), player,
                            outcome.community(), outcome.oldScore(), outcome.newScore(),
                            outcome.newScore() - outcome.oldScore(), null, null,
                            BuiltinIncidents.SOURCE_MIGRATION));
                }
                outcome.tier().post(context, request.playerId(), player, outcome.community());
            }

            McaReputation.LOGGER.info("[MCA: Reputation] migrated {} for player {}: {}",
                    request.sourceId(), request.playerId(),
                    "baseline total " + baselineTotal + " across " + request.baselines().size()
                            + " communit(ies), " + titleCount + " title(s)");
            return ImportResult.applied(request.baselines().size(), baselineTotal, titleCount,
                    highWaterCount, notes);
        } catch (Throwable t) {
            McaReputation.LOGGER.error("[MCA: Reputation] legacy import failed for player {}; nothing "
                    + "was written and the player remains eligible to retry", request.playerId(), t);
            return ImportResult.notApplied(ImportResult.Reason.ERROR);
        }
    }

    // ------------------------------------------------------------------
    // Shared internals
    // ------------------------------------------------------------------

    /** The tier id for a score on the default ladder. */
    public static String currentTierId(int score) {
        return ReputationTiers.getDefault().tierFor(score).id();
    }

    /**
     * The tier bookkeeping every mutation shares: detect the transition, advance the high-water mark
     * on a new personal best, and grant that tier's title exactly once.
     *
     * <p>Returns a deferred outcome rather than posting immediately so the caller controls event
     * ordering — the score change is always announced before the tier change that followed from it.
     */
    private static TierOutcome applyTierTransition(ServiceContext ctx, PlayerReputationRecord playerRecord,
                                                   CommunityReputationRecord communityRecord,
                                                   @Nullable ServerPlayer player,
                                                   int oldScore, int newScore, long gameTime) {
        ReputationTierSet ladder = ReputationTiers.getDefault();
        ReputationTierSet.Transition transition = ladder.transition(oldScore, newScore);
        String newTierId = transition.to().id();
        if (!transition.changed()) {
            return new TierOutcome(false, transition.from().id(), newTierId, 0, 0, false);
        }

        String highWater = communityRecord.tierHighWater(ReputationTiers.DEFAULT_ID).orElse(null);
        if (highWater == null) {
            // Seed from the tier the player already stood in: the tier you started with is not a new
            // personal best when you return to it after a dip, and without the seed the first upward
            // crossing back into it would fire a first-time toast and grant its title.
            highWater = transition.from().id();
            communityRecord.setTierHighWater(ReputationTiers.DEFAULT_ID, highWater);
        }
        boolean firstTime = transition.upward() && ladder.isNewHighWater(newTierId, highWater);
        if (firstTime) {
            communityRecord.setTierHighWater(ReputationTiers.DEFAULT_ID, newTierId);
            if (McaReputationConfig.tierTitlesEnabled()) {
                transition.to().grantsTitle().ifPresent(title -> TitleService.grant(ctx,
                        playerRecord.playerId(), player, communityRecord.key(), title));
            }
        }
        return new TierOutcome(true, transition.from().id(), newTierId,
                ladder.indexOf(transition.from().id()), ladder.indexOf(newTierId), firstTime);
    }

    /** A pending tier transition, posted after the score change it followed from. */
    private record TierOutcome(boolean changed, String oldTierId, String newTierId,
                               int oldIndex, int newIndex, boolean firstTime) {

        void post(ServiceContext ctx, UUID playerId, @Nullable ServerPlayer player, CommunityKey community) {
            if (changed) {
                postSafely(ctx, new ReputationTierChangedEvent(playerId, player, community,
                        ReputationTiers.DEFAULT_ID, oldTierId, newTierId, oldIndex, newIndex, firstTime));
            }
        }
    }

    /**
     * Notifies every registered mirror. Failures are contained: §18 requires the canonical commit to
     * remain valid when integration code throws, so a broken add-on costs a log line and a stale
     * fallback copy, never the player's actual standing.
     */
    private static void notifyMirrors(UUID playerId, CommunityKey community,
                                      CommunityReputationRecord record) {
        if (MIRRORS.isEmpty() || !McaReputationConfig.mirrorQuestsFallbackState()) {
            return;
        }
        String highWater = record.tierHighWater(ReputationTiers.DEFAULT_ID).orElse(null);
        for (ReputationMirror mirror : MIRRORS) {
            try {
                mirror.mirrorScore(playerId, community, record.score(), ReputationTiers.DEFAULT_ID, highWater);
                for (ResourceLocation title : record.titles()) {
                    mirror.mirrorVillageTitle(playerId, community, title);
                }
            } catch (Throwable t) {
                McaReputation.LOGGER.error("[MCA: Reputation] mirror '{}' threw; the canonical commit stands",
                        mirror.mirrorName(), t);
            }
        }
    }

    /** Posts an event, containing any listener failure so one bad add-on cannot unwind a commit. */
    static void postSafely(ServiceContext ctx, net.neoforged.bus.api.Event event) {
        try {
            ctx.post(event);
        } catch (Throwable t) {
            McaReputation.LOGGER.error("[MCA: Reputation] a listener threw handling {}; the commit stands",
                    event.getClass().getSimpleName(), t);
        }
    }
}

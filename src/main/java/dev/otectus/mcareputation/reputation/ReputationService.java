package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.api.DeliveryOutcome;
import dev.otectus.mcareputation.api.ExternalGossipCandidate;
import dev.otectus.mcareputation.api.GossipStory;
import dev.otectus.mcareputation.api.ImportResult;
import dev.otectus.mcareputation.api.IncidentDelivery;
import dev.otectus.mcareputation.api.IncidentQuery;
import dev.otectus.mcareputation.api.LegacyImportRequest;
import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.api.ReceiptView;
import dev.otectus.mcareputation.api.ReputationIncidentView;
import dev.otectus.mcareputation.api.ReputationMirror;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.api.ReputationSnapshot;
import dev.otectus.mcareputation.api.ResolutionResult;
import dev.otectus.mcareputation.api.SpeakerContext;
import dev.otectus.mcareputation.api.StandingChange;
import dev.otectus.mcareputation.api.SupersedeSpec;
import dev.otectus.mcareputation.api.event.ReputationChangedEvent;
import dev.otectus.mcareputation.api.event.ReputationIncidentCreatedEvent;
import dev.otectus.mcareputation.api.event.ReputationIncidentResolvedEvent;
import dev.otectus.mcareputation.api.event.ReputationTierChangedEvent;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityMetadata;
import dev.otectus.mcareputation.incident.AwarenessResolver;
import dev.otectus.mcareputation.incident.BuiltinIncidents;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentDisplay;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.network.SnapshotSelection;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import dev.otectus.mcareputation.state.OperationReceipt;
import dev.otectus.mcareputation.state.PlayerReputationRecord;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
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
        if (request == null) {
            McaReputation.LOGGER.error("[MCA: Reputation] record() was given no request; nothing was written");
            return ReputationResult.rejected(ReputationResult.Reason.ERROR, null);
        }
        // One implementation, two doors: a plain request is a delivery whose operation identity comes
        // from its own source and dedupe key. Without a dedupe key there is no identity and no receipt,
        // which is exactly what record() has always done.
        return deliverWith(ctx, IncidentDelivery.of(request)).result();
    }

    // ------------------------------------------------------------------
    // Delivery
    // ------------------------------------------------------------------

    /**
     * Records one deed under a producer-owned operation identity, and answers with a receipt (§5 F03).
     *
     * <p>The difference from {@link #record} is what happens on a replay. A keyed delivery is answered
     * from the receipt store first, so an operation that produced <em>no</em> incident — an unwitnessed
     * deed, an invalid request — is still remembered and still answers the same way, which a ledger
     * lookup cannot do.
     */
    public static DeliveryOutcome deliver(IncidentDelivery delivery) {
        return deliverWith(null, delivery);
    }

    /** Seam entry point: {@code ctx} may replace the server for tests. */
    static DeliveryOutcome deliverWith(@Nullable ServiceContext ctx, IncidentDelivery delivery) {
        if (delivery == null) {
            return DeliveryOutcome.of(ReceiptOutcome.REFUSED_INVALID,
                    ReputationResult.rejected(ReputationResult.Reason.INVALID, null));
        }
        ReputationRequest request = delivery.request();
        try {
            ServiceContext context = ctx != null ? ctx : ServiceContext.of(request.server());
            return deliverInternal(context, delivery);
        } catch (Throwable t) {
            McaReputation.LOGGER.error("[MCA: Reputation] transaction failed for player {} incident {}; "
                    + "nothing was written", request.playerId(), request.incidentType(), t);
            // Deliberately no receipt: a contained internal failure is the one refusal a producer
            // should be able to retry, and a stored terminal answer would make it permanent.
            return DeliveryOutcome.of(ReceiptOutcome.REFUSED_INVALID,
                    ReputationResult.rejected(ReputationResult.Reason.ERROR, request.community()));
        }
    }

    private static DeliveryOutcome deliverInternal(ServiceContext ctx, IncidentDelivery delivery) {
        ReputationRequest request = delivery.request();
        if (!delivery.keyed()) {
            ReputationResult result = recordInternal(ctx, request);
            return DeliveryOutcome.of(outcomeFor(ctx, request, result), result);
        }

        ReputationSavedData data = ctx.data();
        UUID playerId = request.playerId();
        CommunityKey community = request.community();
        String namespace = delivery.producerNamespace();
        String operationKey = delivery.operationKey();
        long now = ctx.now();

        // 1. the receipt index, consulted before the ledger: it is the only index that remembers a
        //    delivery which produced nothing. Never creates a player record.
        Optional<PlayerReputationRecord> maybePlayer = data.player(playerId);
        if (maybePlayer.isPresent()) {
            PlayerReputationRecord playerRecord = maybePlayer.get();
            Optional<OperationReceipt> stored = playerRecord.findReceipt(namespace, community, operationKey);
            if (stored.isEmpty()) {
                // Pre-receipt keys carry no namespace of their own.
                stored = playerRecord.findLegacyReceipt(community, operationKey);
            }
            if (stored.isPresent()) {
                return replay(ctx, data, playerRecord, stored.get(), request, now);
            }

            // 2. the legacy path: a retained incident under this key that predates receipts. It gets
            //    one synthesised now, so the next replay is answered by the index rather than a scan.
            Optional<IncidentRecord> existing = playerRecord.findByDedupeKey(community, operationKey);
            if (existing.isPresent()) {
                OperationReceipt synthesised = new OperationReceipt(namespace, playerId, community,
                        operationKey, ReceiptOutcome.APPLIED, Optional.of(existing.get().id()),
                        existing.get().createdGameTime(), existing.get().appliedGameTime());
                playerRecord.recordReceipt(synthesised);
                data.setDirty();
                return replay(ctx, data, playerRecord, synthesised, request, now);
            }
        }

        // 3. a genuinely new operation: the ordinary transaction, then the receipt in the same
        //    mutation as the ledger write.
        ReputationResult result = recordInternal(ctx, request);
        ReceiptOutcome outcome = outcomeFor(ctx, request, result);
        if (!outcome.isTerminal() || result.reason() == ReputationResult.Reason.ERROR) {
            return DeliveryOutcome.of(outcome, result);
        }
        PlayerReputationRecord owner = data.getOrCreatePlayer(playerId);
        OperationReceipt receipt = new OperationReceipt(namespace, playerId, community, operationKey,
                outcome, result.incidentId(), request.gameTime(), now);
        owner.recordReceipt(receipt);
        data.setDirty();
        return DeliveryOutcome.of(outcome, result, receipt.toView());
    }

    /** Answers a replayed operation from its stored receipt, without touching the ledger. */
    private static DeliveryOutcome replay(ServiceContext ctx, ReputationSavedData data,
                                          PlayerReputationRecord playerRecord, OperationReceipt receipt,
                                          ReputationRequest request, long now) {
        CommunityKey community = request.community();
        // The reported standing must be the current one, or the replay disagrees with the very next
        // query. Through the gate, so answering a replay cannot age a protected village.
        publishReconcile(ctx, request.playerId(), community,
                ReconciliationService.reconcile(ctx, data, request.playerId(), community, now,
                        ChangeCause.DECAY, ReconciliationService.Intent.QUERY));
        int score = playerRecord.community(community)
                .map(CommunityReputationRecord::score).orElse(0);
        if (McaReputationConfig.debugLogging()) {
            McaReputation.LOGGER.debug("[MCA: Reputation] replayed receipt {} for {} (key '{}')",
                    receipt.outcome(), request.source(), receipt.operationKey());
        }
        if (receipt.outcome() == ReceiptOutcome.REFUSED_INVALID) {
            // A terminal refusal stays refused: replaying it can only ever produce the same answer.
            return DeliveryOutcome.of(ReceiptOutcome.REFUSED_INVALID,
                    ReputationResult.rejected(ReputationResult.Reason.INVALID, community),
                    receipt.toView());
        }
        return DeliveryOutcome.of(ReceiptOutcome.DUPLICATE,
                ReputationResult.duplicate(receipt.incidentId().orElse(null), community, score,
                        currentTierId(score)),
                receipt.toView());
    }

    /**
     * The delivery-contract row one ordinary result corresponds to (§5 F03).
     *
     * <p>An applied deed that produced only a private, zero-contribution record is reported as
     * {@code ACCEPTED_NO_PUBLIC_INCIDENT} rather than {@code APPLIED}: the producer's operation was
     * accepted, nothing public came of it, and the receipt still names the record that was retained.
     */
    private static ReceiptOutcome outcomeFor(ServiceContext ctx, ReputationRequest request,
                                             ReputationResult result) {
        if (result.applied()) {
            boolean publicRecord = result.incidentId()
                    .flatMap(id -> incidentWith(ctx, request.playerId(), request.community(), id))
                    .map(incident -> incident.visibility().effective() != IncidentVisibility.PRIVATE)
                    .orElse(true);
            return publicRecord ? ReceiptOutcome.APPLIED : ReceiptOutcome.ACCEPTED_NO_PUBLIC_INCIDENT;
        }
        return switch (result.reason()) {
            case DUPLICATE -> ReceiptOutcome.DUPLICATE;
            case DISABLED -> ReceiptOutcome.REFUSED_DISABLED;
            case CAPACITY -> ReceiptOutcome.REFUSED_CAPACITY;
            case UNWITNESSED -> ReceiptOutcome.ACCEPTED_NO_PUBLIC_INCIDENT;
            default -> ReceiptOutcome.REFUSED_INVALID;
        };
    }

    // ------------------------------------------------------------------
    // Read-only lookups (DD7)
    // ------------------------------------------------------------------

    /** The receipt for one operation, exactly then legacy. Creates nothing and ages nothing. */
    public static Optional<ReceiptView> findReceipt(MinecraftServer server, String namespace,
                                                    UUID playerId, CommunityKey community,
                                                    String operationKey) {
        return findReceiptWith(ServiceContext.of(server), namespace, playerId, community, operationKey);
    }

    static Optional<ReceiptView> findReceiptWith(ServiceContext ctx, String namespace, UUID playerId,
                                                 CommunityKey community, String operationKey) {
        Optional<PlayerReputationRecord> player = ctx.data().player(playerId);
        if (player.isEmpty()) {
            return Optional.empty();
        }
        Optional<OperationReceipt> hit = player.get().findReceipt(namespace, community, operationKey);
        if (hit.isEmpty()) {
            hit = player.get().findLegacyReceipt(community, operationKey);
        }
        return hit.map(OperationReceipt::toView);
    }

    /**
     * One incident as the API sees it, read strictly as stored (DD7). This is the replacement for the
     * write-capable probe a companion used to reach for: {@link ReconciliationService.Intent#INSPECT}
     * means no record is created, nothing is aged, and no event is posted.
     */
    public static Optional<ReputationIncidentView> findIncidentView(MinecraftServer server, UUID playerId,
                                                                    CommunityKey community, UUID incidentId) {
        return findIncidentViewWith(ServiceContext.of(server), playerId, community, incidentId);
    }

    static Optional<ReputationIncidentView> findIncidentViewWith(ServiceContext ctx, UUID playerId,
                                                                 CommunityKey community, UUID incidentId) {
        long now = ctx.now();
        ReconciliationService.reconcile(ctx, ctx.data(), playerId, community, now, ChangeCause.DECAY,
                ReconciliationService.Intent.INSPECT);
        return incidentWith(ctx, playerId, community, incidentId)
                .map(incident -> ReputationIncidentView.of(incident, now));
    }

    /** The oldest occurrence this player's receipts can still answer for; empty while none were lost. */
    public static OptionalLong receiptFloor(MinecraftServer server, UUID playerId) {
        return receiptFloorWith(ServiceContext.of(server), playerId);
    }

    static OptionalLong receiptFloorWith(ServiceContext ctx, UUID playerId) {
        return ctx.data().player(playerId)
                .map(PlayerReputationRecord::receiptFloor)
                .orElseGet(OptionalLong::empty);
    }

    private static ReputationResult recordInternal(ServiceContext ctx, ReputationRequest request) {
        Commit commit = commit(ctx, request);
        if (!commit.created()) {
            return commit.refusal();
        }
        // 9-10. tier, high-water, title
        @Nullable ServerPlayer player = ctx.onlinePlayer(request.playerId());
        TierOutcome tier = applyTierTransition(ctx, commit.playerRecord(), commit.community(), player,
                commit.oldScore(), commit.newScore(), commit.now());

        // 12-13. mirrors, then events — both outside the canonical commit, through the one envelope
        IncidentRecord incident = commit.incident();
        ReputationIncidentView view = ReputationIncidentView.of(incident, commit.now());
        publishStandingChange(ctx,
                standingChange(request.playerId(), commit.community(), commit.oldScore(),
                        commit.newScore(), tier, ChangeCause.DEED, false),
                commit.community(), player, tier,
                new ReputationIncidentCreatedEvent(request.playerId(), player, view),
                incident.id(), incident.type(), request.source());

        int appliedDelta = commit.newScore() - commit.oldScore();
        if (McaReputationConfig.debugLogging()) {
            McaReputation.LOGGER.debug("[MCA: Reputation] {} recorded {} for {} in {}: {} -> {} ({}{}), tier {}",
                    request.source(), request.incidentType(), request.playerId(),
                    request.community().asString(), commit.oldScore(), commit.newScore(),
                    appliedDelta >= 0 ? "+" : "", appliedDelta, tier.newTierId);
        }
        return ReputationResult.applied(incident.id(), request.community(), commit.oldScore(),
                commit.newScore(), appliedDelta, currentTierId(commit.oldScore()), tier.newTierId,
                tier.firstTime);
    }

    /**
     * What the canonical half of a record transaction produced: either a refusal, or the committed
     * incident and the scores around it, with the tier transition and the publication still to come.
     *
     * <p>Split out so {@link #recordSuperseding} can run the same creation path with its publication
     * deferred, and then announce one net change instead of two (§5 F05, DD8).
     */
    private record Commit(@Nullable ReputationResult refusal, @Nullable IncidentRecord incident,
                          @Nullable PlayerReputationRecord playerRecord,
                          @Nullable CommunityReputationRecord community,
                          int oldScore, int newScore, long now) {

        boolean created() {
            return incident != null;
        }
    }

    /** Steps 1-11 of the §18 transaction: everything up to and including the canonical commit. */
    private static Commit commit(ServiceContext ctx, ReputationRequest request) {
        // 1. server thread
        if (!ctx.isServerThread()) {
            McaReputation.LOGGER.error("[MCA: Reputation] record() called off the server thread from {}; "
                            + "refusing. Writes are server-thread only (spec 25).",
                    request.source());
            return refused(ReputationResult.rejected(ReputationResult.Reason.INVALID, request.community()));
        }
        if (!McaReputationConfig.enabled()) {
            return refused(ReputationResult.rejected(ReputationResult.Reason.DISABLED, request.community()));
        }

        // 3. definition
        Optional<IncidentDefinition> maybeDefinition = IncidentRegistry.get(request.incidentType());
        if (maybeDefinition.isEmpty()) {
            McaReputation.LOGGER.warn("[MCA: Reputation] {} asked to record unknown incident type {}; ignoring",
                    request.source(), request.incidentType());
            return refused(ReputationResult.rejected(ReputationResult.Reason.UNKNOWN_INCIDENT,
                    request.community()));
        }
        IncidentDefinition definition = maybeDefinition.get();

        ReputationSavedData data = ctx.data();
        PlayerReputationRecord playerRecord = data.getOrCreatePlayer(request.playerId());
        CommunityKey community = request.community();
        // "Now" is the world clock; the request carries when the deed happened (§5 F10, DD4). A
        // future occurrence time is a producer clock error and clamps to now rather than banking
        // negative age; an older one is honest history and is aged below before it reaches the score.
        long now = ctx.now();
        long occurredAt = Math.min(request.gameTime(), now);
        if (request.gameTime() > now && McaReputationConfig.debugLogging()) {
            McaReputation.LOGGER.debug("[MCA: Reputation] {} filed {} with occurrence time {} ahead of the "
                    + "world clock {}; clamped", request.source(), request.incidentType(),
                    request.gameTime(), now);
        }
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
                // query. Through the gate, so replaying a deed cannot age a protected village.
                publishReconcile(ctx, request.playerId(), community,
                        ReconciliationService.reconcile(ctx, data, request.playerId(), community,
                                now, ChangeCause.DECAY,
                                ReconciliationService.Intent.QUERY));
                if (McaReputationConfig.debugLogging()) {
                    McaReputation.LOGGER.debug("[MCA: Reputation] dedupe refused {} from {} (key '{}')",
                            request.incidentType(), request.source(), request.dedupeKey().get());
                }
                // Hand back the id the first attempt produced. A companion that crashed between our
                // commit and its own link write can only repair itself if the replay tells it what
                // already exists.
                return refused(ReputationResult.duplicate(existing.get().id(), community,
                        existingCommunity.score(), currentTierId(existingCommunity.score())));
            }
        }

        // 4b. capacity, before anything is created (§5 F09, D5). Silently losing live contribution to
        // satisfy a display cap is the defect; refusing the deed outright is honest, and because
        // nothing has been written yet the refusal costs no partial commit, no receipt and no event.
        long receiptHorizon = ctx.policy().receiptRetentionTicks();
        Optional<CommunityReputationRecord> knownCommunity = playerRecord.community(community);
        boolean noRoom = !playerRecord.canAdmitCommunity(community)
                || knownCommunity.map(record -> !record.canAdmit(
                        McaReputationConfig.maxIncidentsPerCommunity(), now, receiptHorizon))
                        .orElse(false)
                || (playerRecord.totalIncidentCount() >= McaReputationConfig.maxIncidentsPerPlayer()
                        && !playerRecord.hasEvictableIncident(now, receiptHorizon));
        if (noRoom) {
            McaReputation.LOGGER.warn("[MCA: Reputation] refused {} from {} for player {} in {}: the "
                            + "ledger is full and nothing in it may be evicted. Clear a pin or raise "
                            + "maxIncidentsPerCommunity.", request.incidentType(), request.source(),
                    request.playerId(), community.asString());
            return refused(ReputationResult.notApplied(ReputationResult.Reason.CAPACITY, community,
                    knownCommunity.map(CommunityReputationRecord::score).orElse(0),
                    currentTierId(knownCommunity.map(CommunityReputationRecord::score).orElse(0))));
        }

        CommunityReputationRecord communityRecord = playerRecord.getOrCreate(community);

        // 5. reconcile outstanding decay so oldScore is honest
        publishReconcile(ctx, request.playerId(), community,
                ReconciliationService.reconcile(ctx, data, request.playerId(), community,
                        now, ChangeCause.DECAY, ReconciliationService.Intent.MUTATE));
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
                return refused(ReputationResult.notApplied(ReputationResult.Reason.UNWITNESSED, community,
                        oldScore, oldTierId));
            }
            delta = 0;
            visibility = IncidentVisibility.PRIVATE;
        }

        // 7. create and store
        IncidentRecord incident = IncidentRecord.create(UUID.randomUUID(), request.incidentType(),
                request.playerId(), community, occurredAt, now, request.source(), request.dedupeKey(),
                delta, visibility, definition.severity(), request.subjects());
        incident.addWitnesses(request.witnesses());
        incident.putContext(request.context());
        incident.setPinned(definition.pinned());
        // Age the arriving contribution to the present before it ever reaches the score: a deed
        // delivered late is worth what it is worth now, not what it was worth when it happened, or the
        // mirrors, the toast and the tier event all announce a value it no longer earns.
        incident.reconcile(definition.decay(), now);
        communityRecord.addIncident(incident);
        playerRecord.indexDedupe(incident);

        // 8. score and bounds
        communityRecord.recomputeScore(minScore, maxScore);
        communityRecord.prune(McaReputationConfig.maxIncidentsPerCommunity(), now, minScore, maxScore,
                receiptHorizon);
        playerRecord.enforcePlayerIncidentCap(McaReputationConfig.maxIncidentsPerPlayer(),
                now, minScore, maxScore);
        int newScore = communityRecord.score();

        @Nullable ServerPlayer namedPlayer = ctx.onlinePlayer(request.playerId());
        if (namedPlayer != null) {
            playerRecord.setLastKnownName(namedPlayer.getGameProfile().getName());
        }

        // 11. persist
        data.setDirty();
        return new Commit(null, incident, playerRecord, communityRecord, oldScore, newScore, now);
    }

    /** A transaction that never created anything: the refusal is the whole outcome. */
    private static Commit refused(ReputationResult result) {
        return new Commit(result, null, null, null, 0, 0, 0L);
    }

    // ------------------------------------------------------------------
    // Superseding a precursor
    // ------------------------------------------------------------------

    /**
     * Records a deed that absorbs an earlier one, as one encounter rather than two (§5 F05, DD8).
     *
     * <p>The native assault → killing upgrade and any future producer-owned crime pipeline share this
     * one seam, which is what stops the two implementations from disagreeing. A beating worth
     * {@code -8} followed by a killing worth {@code -40} totals {@code -40}: the precursor is folded,
     * the successor carries the whole figure, and exactly one {@link ChangeCause#SUPERSEDE} change is
     * published — never a refund promotion followed by a charge.
     *
     * <p>A spec that does not validate is not an error and never costs the player's deed: the successor
     * is recorded plainly, the precursor keeps its weight, and the reason is logged at debug.
     */
    public static ReputationResult recordSuperseding(ReputationRequest successor, SupersedeSpec spec) {
        return recordSupersedingWith(null, successor, spec);
    }

    /** Seam entry point: {@code ctx} may replace the server for tests; null derives it from the request. */
    static ReputationResult recordSupersedingWith(@Nullable ServiceContext ctx, ReputationRequest successor,
                                                  SupersedeSpec spec) {
        try {
            ServiceContext context = ctx != null ? ctx : ServiceContext.of(successor.server());
            return supersedeInternal(context, successor, spec);
        } catch (Throwable t) {
            McaReputation.LOGGER.error("[MCA: Reputation] superseding transaction failed for player {} "
                            + "incident {}; nothing was written",
                    successor == null ? "?" : successor.playerId(),
                    successor == null ? "?" : successor.incidentType(), t);
            return ReputationResult.rejected(ReputationResult.Reason.ERROR,
                    successor == null ? null : successor.community());
        }
    }

    private static ReputationResult supersedeInternal(ServiceContext ctx, ReputationRequest successor,
                                                      SupersedeSpec spec) {
        ReputationSavedData data = ctx.data();
        CommunityKey community = successor.community();
        long now = ctx.now();
        Optional<IncidentRecord> maybePrecursor = spec == null || spec.precursorIncidentId() == null
                ? Optional.empty()
                : data.player(successor.playerId())
                        .flatMap(record -> record.community(community))
                        .flatMap(record -> record.incident(spec.precursorIncidentId()));
        Optional<String> refusal = supersedeRefusal(maybePrecursor, successor, spec, now);
        if (refusal.isPresent()) {
            // A real deed is never dropped because its supersede terms did not hold.
            if (McaReputationConfig.debugLogging()) {
                McaReputation.LOGGER.debug("[MCA: Reputation] {} asked to supersede {} with {} but {}; "
                                + "recording the successor on its own", successor.source(),
                        spec == null ? "?" : spec.precursorIncidentId(), successor.incidentType(),
                        refusal.get());
            }
            return recordInternal(ctx, successor);
        }
        IncidentRecord precursor = maybePrecursor.orElseThrow();

        int minScore = McaReputationConfig.minimumScore();
        int maxScore = McaReputationConfig.maximumScore();
        CommunityReputationRecord communityRecord = data.player(successor.playerId())
                .flatMap(record -> record.community(community))
                .orElseThrow();
        // The "before" score of the whole encounter, taken before the fold: what the one published
        // change reports moving from.
        publishReconcile(ctx, successor.playerId(), community,
                ReconciliationService.reconcile(ctx, data, successor.playerId(), community, now,
                        ChangeCause.DECAY, ReconciliationService.Intent.MUTATE));
        int oldScore = communityRecord.score();

        // Fold first, so the successor's own delta lands on a ledger that no longer double-counts the
        // lead-up. The pre-fold weight is snapshotted: an unseen killing must not refund the beating
        // the village did see.
        int foldedSettled = precursor.settledDelta();
        int foldedCurrent = precursor.currentContribution();
        precursor.foldInto(null, now);
        communityRecord.recomputeScore(minScore, maxScore);

        Commit commit = commit(ctx, successor);
        if (!commit.created()) {
            precursor.restoreContribution(foldedSettled, foldedCurrent, now);
            communityRecord.recomputeScore(minScore, maxScore);
            data.setDirty();
            int score = communityRecord.score();
            String tierId = currentTierId(score);
            return new ReputationResult(false, commit.refusal().incidentId(), community, score, score, 0,
                    tierId, tierId, false, false, commit.refusal().reason());
        }
        IncidentRecord incident = commit.incident();
        if (incident.contributes()) {
            precursor.linkSuccessor(incident.id());
        } else {
            precursor.restoreContribution(foldedSettled, foldedCurrent, now);
        }
        communityRecord.recomputeScore(minScore, maxScore);
        int newScore = communityRecord.score();

        @Nullable ServerPlayer player = ctx.onlinePlayer(successor.playerId());
        TierOutcome tier = applyTierTransition(ctx, commit.playerRecord(), communityRecord, player,
                oldScore, newScore, now);
        data.setDirty();

        // One publication for one encounter: the net old → new transition, cause SUPERSEDE.
        ReputationIncidentView view = ReputationIncidentView.of(incident, now);
        publishStandingChange(ctx,
                standingChange(successor.playerId(), communityRecord, oldScore, newScore, tier,
                        ChangeCause.SUPERSEDE, false),
                communityRecord, player, tier,
                new ReputationIncidentCreatedEvent(successor.playerId(), player, view),
                incident.id(), incident.type(), successor.source());

        McaReputation.LOGGER.debug("[MCA: Reputation] {} superseded {} with {} for {} in {}: {} -> {}",
                successor.source(), precursor.id(), incident.id(), successor.playerId(),
                community.asString(), oldScore, newScore);
        return ReputationResult.applied(incident.id(), community, oldScore, newScore,
                newScore - oldScore, currentTierId(oldScore), tier.newTierId, tier.firstTime);
    }

    /**
     * Why these two deeds may not be treated as one encounter, or empty when they may. Validation runs
     * before anything is folded or created, so a refusal costs nothing.
     */
    private static Optional<String> supersedeRefusal(Optional<IncidentRecord> maybePrecursor,
                                                     ReputationRequest successor, SupersedeSpec spec,
                                                     long now) {
        if (spec == null || spec.precursorIncidentId() == null) {
            return Optional.of("no precursor was named");
        }
        if (maybePrecursor.isEmpty()) {
            return Optional.of("the precursor is not in this player's ledger for this community");
        }
        IncidentRecord precursor = maybePrecursor.get();
        if (precursor.isSuperseded()) {
            return Optional.of("the precursor was already superseded");
        }
        if (precursor.status() == IncidentStatus.DISPROVEN) {
            return Optional.of("the precursor was disproven");
        }
        long occurredAt = Math.min(successor.gameTime(), now);
        if (Math.abs(occurredAt - precursor.createdGameTime()) > Math.max(0L, spec.maxWindowTicks())) {
            return Optional.of("the precursor is outside the " + spec.maxWindowTicks() + "-tick window");
        }
        if (spec.requireSharedSubject() && !sharesSubject(precursor, successor)) {
            return Optional.of("the two deeds name no subject in common");
        }
        return Optional.empty();
    }

    /** Whether the successor and the precursor are about at least one of the same subjects. */
    private static boolean sharesSubject(IncidentRecord precursor, ReputationRequest successor) {
        for (var subject : successor.subjects()) {
            Optional<UUID> uuid = subject.uuid();
            if (uuid.isEmpty()) {
                continue;
            }
            for (var candidate : precursor.subjects()) {
                if (candidate.uuid().map(uuid.get()::equals).orElse(false)) {
                    return true;
                }
            }
        }
        return false;
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
            // Freeze-respecting: while frozen this only skips the clock forward, so a permitted
            // explicit resolution still softens the incident but never runs hidden decay first
            // (§5 F07).
            publishReconcile(ctx, playerId, community,
                    ReconciliationService.reconcile(ctx, data, playerId, community, gameTime,
                            ChangeCause.DECAY, ReconciliationService.Intent.MUTATE));

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

            ReputationIncidentView view = ReputationIncidentView.of(incident, gameTime);
            publishStandingChange(ctx,
                    standingChange(playerId, communityRecord, oldScore, newScore, tier,
                            ChangeCause.RESOLUTION, false),
                    communityRecord, player, tier,
                    new ReputationIncidentResolvedEvent(playerId, player, view, oldStatus,
                            oldContribution, source),
                    incident.id(), incident.type(), source);

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

    /** As above, with a speaker so a {@code known_to_speaker} selector can be evaluated. */
    public static ResolutionResult resolveBySelector(MinecraftServer server, UUID playerId,
                                                     CommunityKey community, IncidentQuery selector,
                                                     @Nullable SpeakerContext speaker,
                                                     IncidentStatus status, ResourceLocation source,
                                                     long gameTime) {
        return resolveBySelectorWith(ServiceContext.of(server), playerId, community, selector, speaker,
                status, source, gameTime);
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
    // Speaker-aware queries and bound resolution (F12)
    // ------------------------------------------------------------------

    /**
     * The incidents a selector picks out, evaluated on behalf of one villager.
     *
     * <p>{@link IncidentQuery#knownToSpeaker()} is a real constraint and this is the only path that can
     * honour it: awareness belongs to a villager, not to an incident. Without a speaker the query is
     * answered with nothing rather than with everything (Sec. 6 "Speaker-aware query") - an over-broad
     * answer is the failure mode a knowledge filter exists to prevent.
     */
    public static List<ReputationIncidentView> selectIncidents(MinecraftServer server, UUID playerId,
                                                               CommunityKey community, IncidentQuery query,
                                                               @Nullable SpeakerContext speaker,
                                                               long gameTime) {
        return selectIncidentsWith(ServiceContext.of(server), playerId, community, query, speaker, gameTime);
    }

    static List<ReputationIncidentView> selectIncidentsWith(ServiceContext ctx, UUID playerId,
                                                            CommunityKey community, IncidentQuery query,
                                                            @Nullable SpeakerContext speaker,
                                                            long gameTime) {
        if (query == null || playerId == null || community == null) {
            return List.of();
        }
        if (query.knownToSpeaker() && (speaker == null || speaker.speakerId() == null)) {
            McaReputation.LOGGER.debug("[MCA: Reputation] a known_to_speaker selector arrived with no "
                    + "speaker; answering with nothing rather than with everything");
            return List.of();
        }
        // The ordinary ledger read, which reconciles and already drops superseded records.
        List<ReputationIncidentView> candidates =
                recentIncidentsWith(ctx, playerId, community, Integer.MAX_VALUE, gameTime);
        if (query.knownToSpeaker()) {
            int min = McaReputationConfig.minRumorDelayTicks();
            int max = McaReputationConfig.maxRumorDelayTicks();
            candidates = candidates.stream()
                    .filter(view -> incidentWith(ctx, playerId, community, view.id())
                            .map(record -> AwarenessResolver.knows(record, speaker.speakerId(),
                                    speaker.resident(), gameTime, min, max))
                            .orElse(false))
                    .toList();
        }
        return query.select(candidates);
    }

    /** As {@link #resolveBySelector}, with a speaker so {@code known_to_speaker} can be evaluated. */
    static ResolutionResult resolveBySelectorWith(ServiceContext ctx, UUID playerId, CommunityKey community,
                                                  IncidentQuery selector, @Nullable SpeakerContext speaker,
                                                  IncidentStatus status, ResourceLocation source,
                                                  long gameTime) {
        if (selector == null || selector.isEmpty()) {
            McaReputation.LOGGER.warn("[MCA: Reputation] {} tried to resolve an incident with an empty "
                    + "selector; refusing to pick arbitrarily", source);
            return ResolutionResult.notApplied(ResolutionResult.Reason.INVALID);
        }
        if (selector.knownToSpeaker() && (speaker == null || speaker.speakerId() == null)) {
            McaReputation.LOGGER.warn("[MCA: Reputation] {} used known_to_speaker in a resolve selector "
                    + "with no speaker; refusing", source);
            return ResolutionResult.notApplied(ResolutionResult.Reason.INVALID);
        }
        List<ReputationIncidentView> candidates =
                selectIncidentsWith(ctx, playerId, community, selector, speaker, gameTime);
        if (candidates.isEmpty()) {
            return ResolutionResult.notApplied(ResolutionResult.Reason.NOT_FOUND);
        }
        return resolveWith(ctx, playerId, community, candidates.get(0).id(), status, source, gameTime);
    }

    /**
     * Resolves one exact incident, exactly once (Sec. 6 "Bound resolution").
     *
     * <p>The difference from {@link #resolve} is the operation key. A committed reward binds to an
     * incident id it already holds and to an identity it owns, so a producer that crashed between the
     * transition and storing our answer replays the same key and is told what happened rather than
     * moving the incident a second time. A superseded record is refused outright: its weight belongs to
     * the incident that absorbed it, and resolving it would hand some of that weight back.
     */
    public static ResolutionResult resolveBound(MinecraftServer server, UUID playerId, CommunityKey community,
                                                UUID incidentId, IncidentStatus status,
                                                ResourceLocation source, String operationKey, long gameTime) {
        return resolveBoundWith(ServiceContext.of(server), playerId, community, incidentId, status, source,
                operationKey, gameTime);
    }

    static ResolutionResult resolveBoundWith(ServiceContext ctx, UUID playerId, CommunityKey community,
                                             UUID incidentId, IncidentStatus status, ResourceLocation source,
                                             String operationKey, long gameTime) {
        try {
            if (playerId == null || community == null || incidentId == null || status == null
                    || source == null) {
                return ResolutionResult.notApplied(ResolutionResult.Reason.INVALID);
            }
            if (operationKey == null || operationKey.isBlank()) {
                // No identity to be idempotent under; the plain resolution is already idempotent by
                // status strength, and there is nothing to file a receipt against.
                return resolveWith(ctx, playerId, community, incidentId, status, source, gameTime);
            }
            ReputationSavedData data = ctx.data();
            String namespace = source.getNamespace();
            Optional<PlayerReputationRecord> maybePlayer = data.player(playerId);
            if (maybePlayer.isPresent()) {
                Optional<OperationReceipt> stored =
                        maybePlayer.get().findReceipt(namespace, community, operationKey);
                if (stored.isEmpty()) {
                    stored = maybePlayer.get().findLegacyReceipt(community, operationKey);
                }
                if (stored.isPresent()) {
                    return replayBound(ctx, playerId, community, stored.get());
                }
            }

            Optional<IncidentRecord> target = incidentWith(ctx, playerId, community, incidentId);
            if (target.isEmpty()) {
                // Deliberately no receipt: an id we have never seen may still arrive, and a stored
                // terminal refusal would make a recoverable miss permanent.
                return ResolutionResult.notApplied(ResolutionResult.Reason.NOT_FOUND);
            }
            if (target.get().isSuperseded()) {
                recordBoundReceipt(ctx, playerId, community, namespace, operationKey,
                        ReceiptOutcome.REFUSED_INVALID, Optional.of(incidentId), gameTime);
                McaReputation.LOGGER.debug("[MCA: Reputation] {} tried to resolve superseded incident {}; "
                        + "refused", source, incidentId);
                return ResolutionResult.notApplied(ResolutionResult.Reason.INVALID);
            }

            ResolutionResult result = resolveWith(ctx, playerId, community, incidentId, status, source,
                    gameTime);
            ReceiptOutcome outcome = switch (result.reason()) {
                case APPLIED -> ReceiptOutcome.APPLIED;
                // Settled, not refused: the incident is already at an equal or stronger state, so the
                // operation is complete and must never be attempted again.
                case NOT_STRONGER -> ReceiptOutcome.ACCEPTED_NO_PUBLIC_INCIDENT;
                case INVALID -> ReceiptOutcome.REFUSED_INVALID;
                // NOT_FOUND, DISABLED and ERROR are retryable and store nothing.
                default -> null;
            };
            if (outcome != null) {
                recordBoundReceipt(ctx, playerId, community, namespace, operationKey, outcome,
                        Optional.of(incidentId), gameTime);
            }
            return result;
        } catch (Throwable t) {
            McaReputation.LOGGER.error("[MCA: Reputation] resolveBound() failed for player {} incident {}; "
                    + "nothing was written", playerId, incidentId, t);
            return ResolutionResult.notApplied(ResolutionResult.Reason.ERROR);
        }
    }

    /** Answers a replayed bound resolution from its receipt: the current state, and nothing moved. */
    private static ResolutionResult replayBound(ServiceContext ctx, UUID playerId, CommunityKey community,
                                                OperationReceipt receipt) {
        if (receipt.outcome() == ReceiptOutcome.REFUSED_INVALID) {
            return ResolutionResult.notApplied(ResolutionResult.Reason.INVALID);
        }
        int score = ctx.data().score(playerId, community);
        return receipt.incidentId()
                .flatMap(id -> incidentWith(ctx, playerId, community, id))
                .map(record -> new ResolutionResult(false, Optional.of(record.id()), record.status(),
                        record.status(), record.currentContribution(), record.currentContribution(),
                        score, score, ResolutionResult.Reason.NOT_STRONGER))
                .orElseGet(() -> ResolutionResult.notApplied(ResolutionResult.Reason.NOT_STRONGER));
    }

    private static void recordBoundReceipt(ServiceContext ctx, UUID playerId, CommunityKey community,
                                           String namespace, String operationKey, ReceiptOutcome outcome,
                                           Optional<UUID> incidentId, long gameTime) {
        ReputationSavedData data = ctx.data();
        data.getOrCreatePlayer(playerId).recordReceipt(new OperationReceipt(namespace, playerId, community,
                operationKey, outcome, incidentId, gameTime, ctx.now()));
        data.setDirty();
    }

    // ------------------------------------------------------------------
    // Gossip story (F15 seam)
    // ------------------------------------------------------------------

    /**
     * The newest thing this villager could tell about this player, including a correction they should
     * acknowledge rather than repeat (Sec. 6 "Gossip story").
     *
     * <p>Ordinary deeds carry a {@link ExternalGossipCandidate} for the line itself. A disproven deed,
     * or one a later incident absorbed, carries none: there is a change of belief to speak to and no
     * baseline fact to state.
     */
    public static Optional<GossipStory> gossipStory(MinecraftServer server, UUID playerId,
                                                    CommunityKey community, UUID villagerId,
                                                    boolean resident, long gameTime) {
        return gossipStoryWith(ServiceContext.of(server), playerId, community, villagerId, resident,
                gameTime);
    }

    static Optional<GossipStory> gossipStoryWith(ServiceContext ctx, UUID playerId, CommunityKey community,
                                                 UUID villagerId, boolean resident, long gameTime) {
        if (playerId == null || community == null || villagerId == null) {
            return Optional.empty();
        }
        Optional<PlayerReputationRecord> maybePlayer = ctx.data().player(playerId);
        if (maybePlayer.isEmpty()) {
            return Optional.empty();
        }
        PlayerReputationRecord playerRecord = maybePlayer.get();
        Optional<CommunityReputationRecord> maybeCommunity = playerRecord.community(community);
        if (maybeCommunity.isEmpty()) {
            return Optional.empty();
        }
        CommunityReputationRecord communityRecord = maybeCommunity.get();
        CommunityMetadata metadata = communityRecord.metadata();
        int minDelay = McaReputationConfig.minRumorDelayTicks();
        int maxDelay = McaReputationConfig.maxRumorDelayTicks();

        for (IncidentRecord incident : communityRecord.incidentsNewestFirst()) {
            IncidentDefinition definition = IncidentRegistry.getOrUnknown(incident.type());
            boolean correction = incident.isSuperseded() || incident.status() == IncidentStatus.DISPROVEN;
            boolean tellable = !incident.isSuperseded() && AwarenessResolver.canTell(incident, definition,
                    villagerId, resident, gameTime, 0L, minDelay, maxDelay);
            if (!tellable && !(correction && AwarenessResolver.knows(incident, villagerId, resident,
                    gameTime, minDelay, maxDelay))) {
                continue;
            }
            Optional<ExternalGossipCandidate> candidate = tellable
                    ? Optional.of(new ExternalGossipCandidate(incident.id(), incident.type(),
                            incident.createdGameTime(), incident.ageTicks(gameTime), community.asString(),
                            metadata.name(), definition.gossip().tone().orElse(""),
                            definition.gossip().phrase().orElse(""),
                            IncidentDisplay.gossipArguments(definition, incident,
                                    playerRecord.lastKnownName()),
                            incident.currentContribution()))
                    : Optional.empty();
            return Optional.of(new GossipStory(incident.id(), incident.type(), community,
                    incident.status(), incident.storyRevision(), incident.currentContribution(),
                    incident.baseDelta(), incident.createdGameTime(), incident.isSuperseded(),
                    incident.supersededBy(), incident.status() == IncidentStatus.DISPROVEN, candidate));
        }
        return Optional.empty();
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
        if (ctx.isServerThread()) {
            publishReconcile(ctx, playerId, community,
                    ReconciliationService.reconcile(ctx, data, playerId, community, gameTime,
                            ChangeCause.DECAY, ReconciliationService.Intent.QUERY));
        }
        return Optional.of(buildSnapshot(playerRecord, communityRecord, gameTime));
    }

    private static ReputationSnapshot buildSnapshot(PlayerReputationRecord playerRecord,
                                                    CommunityReputationRecord communityRecord, long gameTime) {
        ReputationTierSet ladder = ReputationTiers.getDefault();
        int score = communityRecord.score();
        List<ReputationIncidentView> views = ledger(communityRecord).stream()
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
        // the Journal, and §15.1 promises no read path shows a stale, un-decayed number. Every
        // community here is one the caller is asking about — no other player's, and no community this
        // list does not return.
        if (ctx.isServerThread()) {
            for (CommunityKey key : List.copyOf(playerRecord.communityKeys())) {
                publishReconcile(ctx, playerId, key,
                        ReconciliationService.reconcile(ctx, data, playerId, key, gameTime,
                                ChangeCause.DECAY, ReconciliationService.Intent.QUERY));
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
        if (ctx.isServerThread()) {
            // Reconcile first, like snapshot(): a deed list showing pre-decay contributions would
            // disagree with the score printed beside it. Only this community, and only through the gate.
            publishReconcile(ctx, playerId, community,
                    ReconciliationService.reconcile(ctx, data, playerId, community, gameTime,
                            ChangeCause.DECAY, ReconciliationService.Intent.QUERY));
        }
        return data.player(playerId)
                .flatMap(record -> record.community(community))
                .map(record -> {
                    return ledger(record).stream()
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
            publishReconcile(ctx, playerId, community,
                    ReconciliationService.reconcile(ctx, data, playerId, community, gameTime,
                            ChangeCause.DECAY, ReconciliationService.Intent.MUTATE));

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

            publishStandingChange(ctx,
                    standingChange(playerId, communityRecord, oldScore, newScore, tier,
                            ChangeCause.ADMIN, false),
                    communityRecord, player, tier, null, null, null, source);

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
        return reconcileWith(ServiceContext.of(server), playerId, gameTime);
    }

    /**
     * Brings one community's decay up to date, and nothing else (§5 F07).
     *
     * <p>What an opinion query wants: reconciling every community a player knows so that one villager
     * can have a view about one village is work nobody asked for.
     */
    public static void reconcileCommunity(MinecraftServer server, UUID playerId, CommunityKey community,
                                          long gameTime) {
        ServiceContext ctx = ServiceContext.of(server);
        publishReconcile(ctx, playerId, community, ReconciliationService.reconcile(ctx, ctx.data(),
                playerId, community, gameTime, ChangeCause.DECAY, ReconciliationService.Intent.QUERY));
    }

    /**
     * The sweep, through the gate and the standing-change envelope.
     *
     * <p>Periodic reconciliation used to move scores without telling anybody, so a mirror or a
     * scoreboard could sit on a number the ledger had already left behind (§5 F07). Each community
     * whose score actually moved now publishes one quiet {@link ChangeCause#DECAY} change: mirrors and
     * displays follow, deed feedback does not.
     */
    static boolean reconcileWith(ServiceContext ctx, UUID playerId, long gameTime) {
        ReputationSavedData data = ctx.data();
        return ReconciliationService.reconcilePlayer(ctx.policy(), data, playerId, gameTime,
                (community, outcome) -> publishReconcile(ctx, playerId, community, outcome));
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
                Optional<CommunityReputationRecord> record = playerRecord.community(outcome.community());
                if (record.isEmpty()) {
                    continue;
                }
                publishStandingChange(context,
                        standingChange(request.playerId(), record.get(), outcome.oldScore(),
                                outcome.newScore(), outcome.tier(), ChangeCause.IMPORT, false),
                        record.get(), player, outcome.tier(), null, null, null,
                        BuiltinIncidents.SOURCE_MIGRATION);
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

    /**
     * The incidents any caller is allowed to be shown or to select against: newest first by occurrence,
     * minus anything a successor absorbed.
     *
     * <p>One collection point, so a superseded record is never an amends candidate, a resolution
     * selector target, a gossip candidate, or a line on the standing screen (§5 F08). It stays in
     * the store as chronology, and the admin tooling can still see it.
     */
    private static List<IncidentRecord> ledger(CommunityReputationRecord record) {
        return record.incidentsNewestFirst().stream()
                .filter(incident -> !incident.isSuperseded())
                .toList();
    }

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
        return applyTierTransition(ctx, playerRecord, communityRecord, player, oldScore, newScore,
                gameTime, false);
    }

    /**
     * The ladder this transition runs on. One place, so the high-water map's key and the ladder the
     * thresholds came from can never disagree - the persisted map has always been keyed by ladder, and
     * only the reader hard-coded the default.
     */
    private static ResourceLocation ladderId() {
        return ReputationTiers.DEFAULT_ID;
    }

    /** A player's high-water tier on one ladder, read-only: it creates nothing and reconciles nothing. */
    public static Optional<String> tierHighWater(MinecraftServer server, UUID playerId,
                                                 CommunityKey community, ResourceLocation ladder) {
        if (server == null) {
            return Optional.empty();
        }
        return tierHighWaterWith(ServiceContext.of(server), playerId, community, ladder);
    }

    static Optional<String> tierHighWaterWith(ServiceContext ctx, UUID playerId, CommunityKey community,
                                              ResourceLocation ladder) {
        if (playerId == null || community == null) {
            return Optional.empty();
        }
        return ctx.data().player(playerId)
                .flatMap(record -> record.community(community))
                .flatMap(record -> record.tierHighWater(ladder == null ? ladderId() : ladder));
    }

    /**
     * @param quiet a background cause: the high-water mark does not advance, no title is granted, and
     *              the transition is never reported as a first time. Ageing into a tier is not the same
     *              as earning it.
     */
    private static TierOutcome applyTierTransition(ServiceContext ctx, PlayerReputationRecord playerRecord,
                                                   CommunityReputationRecord communityRecord,
                                                   @Nullable ServerPlayer player,
                                                   int oldScore, int newScore, long gameTime,
                                                   boolean quiet) {
        ResourceLocation ladderId = ladderId();
        ReputationTierSet ladder = ReputationTiers.getDefault();
        ReputationTierSet.Transition transition = ladder.transition(oldScore, newScore);
        String newTierId = transition.to().id();
        if (!transition.changed()) {
            return new TierOutcome(false, transition.from().id(), newTierId, 0, 0, false);
        }

        String highWater = communityRecord.tierHighWater(ladderId).orElse(null);
        if (highWater == null) {
            // Seed from the tier the player already stood in: the tier you started with is not a new
            // personal best when you return to it after a dip, and without the seed the first upward
            // crossing back into it would fire a first-time toast and grant its title.
            highWater = transition.from().id();
            communityRecord.setTierHighWater(ladderId, highWater);
        }
        boolean firstTime = !quiet && transition.upward() && ladder.isNewHighWater(newTierId, highWater);
        if (firstTime) {
            int firstUnearned = ladder.indexOf(highWater) + 1;
            communityRecord.setTierHighWater(ladderId, newTierId);
            if (McaReputationConfig.tierTitlesEnabled()) {
                // Every milestone the jump passed through, not only the one it landed on (F13). A
                // single admin set from 0 to 300 crosses Honored on its way to Revered, and the badge
                // for a tier you have stood in is not owed to how you got there. One change, one tier
                // notification, several badges.
                grantCrossedMilestones(ctx, playerRecord, communityRecord, player, ladder,
                        firstUnearned, ladder.indexOf(newTierId));
            }
        }
        return new TierOutcome(true, transition.from().id(), newTierId,
                ladder.indexOf(transition.from().id()), ladder.indexOf(newTierId), firstTime);
    }

    /**
     * Grants the title of every ladder rung from {@code fromIndex} to {@code toIndex} inclusive that
     * declares one. Grants are idempotent, so a milestone the player already holds costs a lookup and
     * nothing else.
     */
    private static void grantCrossedMilestones(ServiceContext ctx, PlayerReputationRecord playerRecord,
                                               CommunityReputationRecord communityRecord,
                                               @Nullable ServerPlayer player, ReputationTierSet ladder,
                                               int fromIndex, int toIndex) {
        for (int i = Math.max(0, fromIndex); i <= toIndex && i < ladder.size(); i++) {
            ladder.tiers().get(i).grantsTitle().ifPresent(title -> TitleService.grant(ctx,
                    playerRecord.playerId(), player, communityRecord.key(), title));
        }
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
     * The standing-change envelope every canonical mutation publishes through (§6 "Standing change").
     *
     * <p>One semantic event, one publication, in the §18 order: mirrors first, then the incident event
     * that explains the change, then the change itself, then the tier transition that followed from it.
     * A quiet cause still reaches mirrors and still updates the displayed tier — what it never does is
     * replay deed feedback or claim a milestone.
     */
    private static void publishStandingChange(ServiceContext ctx, StandingChange change,
                                              CommunityReputationRecord record,
                                              @Nullable ServerPlayer player,
                                              @Nullable TierOutcome tier,
                                              @Nullable net.minecraftforge.eventbus.api.Event incidentEvent,
                                              @Nullable UUID incidentId,
                                              @Nullable ResourceLocation incidentType,
                                              ResourceLocation source) {
        notifyMirrors(change, record);
        if (incidentEvent != null) {
            postSafely(ctx, incidentEvent);
        }
        if (change.scoreChanged()) {
            postSafely(ctx, new ReputationChangedEvent(change.player(), player, change.community(),
                    change.oldScore(), change.newScore(), change.delta(), incidentId, incidentType,
                    source, change.cause(), change.quiet()));
        }
        if (tier != null) {
            tier.post(ctx, change.player(), player, change.community());
        }
    }

    /** The envelope for a change the reconciliation gate made: always decay, always quiet. */
    private static void publishReconcile(ServiceContext ctx, UUID playerId, CommunityKey community,
                                         ReconciliationService.ReconcileOutcome outcome) {
        if (!outcome.scoreChanged()) {
            return;
        }
        ReputationSavedData data = ctx.data();
        Optional<PlayerReputationRecord> maybePlayer = data.player(playerId);
        if (maybePlayer.isEmpty()) {
            return;
        }
        PlayerReputationRecord playerRecord = maybePlayer.get();
        Optional<CommunityReputationRecord> maybeCommunity = playerRecord.community(community);
        if (maybeCommunity.isEmpty()) {
            return;
        }
        CommunityReputationRecord record = maybeCommunity.get();
        @Nullable ServerPlayer player = ctx.onlinePlayer(playerId);
        TierOutcome tier = applyTierTransition(ctx, playerRecord, record, player, outcome.oldScore(),
                outcome.newScore(), 0L, true);
        publishStandingChange(ctx, new StandingChange(playerId, community, outcome.oldScore(),
                        outcome.newScore(), tier.oldTierId(), tier.newTierId(), ReputationTiers.DEFAULT_ID,
                        record.tierHighWater(ReputationTiers.DEFAULT_ID).orElse(null),
                        outcome.revision(), ChangeCause.DECAY, true),
                record, player, tier, null, null, null, BuiltinIncidents.SOURCE_CORE);
    }

    /** Builds the envelope for a change the service itself made, bumping the revision when it moved. */
    private static StandingChange standingChange(UUID playerId, CommunityReputationRecord record,
                                                 int oldScore, int newScore, TierOutcome tier,
                                                 ChangeCause cause, boolean quiet) {
        return new StandingChange(playerId, record.key(), oldScore, newScore, tier.oldTierId(),
                tier.newTierId(), ReputationTiers.DEFAULT_ID,
                record.tierHighWater(ReputationTiers.DEFAULT_ID).orElse(null),
                newScore == oldScore ? record.revision() : record.bumpRevision(), cause, quiet);
    }

    /**
     * Notifies every registered mirror. Failures are contained: §18 requires the canonical commit to
     * remain valid when integration code throws, so a broken add-on costs a log line and a stale
     * fallback copy, never the player's actual standing.
     */
    private static void notifyMirrors(StandingChange change, CommunityReputationRecord record) {
        if (MIRRORS.isEmpty() || !McaReputationConfig.mirrorQuestsFallbackState()) {
            return;
        }
        for (ReputationMirror mirror : MIRRORS) {
            try {
                // mirrorStanding, not mirrorScore: the default body calls the old method, so a mirror
                // written against 0.4.0 keeps working unchanged.
                mirror.mirrorStanding(change);
                for (ResourceLocation title : record.titles()) {
                    mirror.mirrorVillageTitle(change.player(), change.community(), title);
                }
            } catch (Throwable t) {
                McaReputation.LOGGER.error("[MCA: Reputation] mirror '{}' threw; the canonical commit stands",
                        mirror.mirrorName(), t);
            }
        }
    }

    /** Posts an event, containing any listener failure so one bad add-on cannot unwind a commit. */
    static void postSafely(ServiceContext ctx, net.minecraftforge.eventbus.api.Event event) {
        try {
            ctx.post(event);
        } catch (Throwable t) {
            McaReputation.LOGGER.error("[MCA: Reputation] a listener threw handling {}; the commit stands",
                    event.getClass().getSimpleName(), t);
        }
    }
}

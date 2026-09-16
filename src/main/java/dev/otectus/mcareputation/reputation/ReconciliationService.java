package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import dev.otectus.mcareputation.state.PlayerReputationRecord;
import dev.otectus.mcareputation.state.ReputationSavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The one policy-aware reconciliation gate (§5 F07, DD1).
 *
 * <p>Decay immunity used to be checked in {@link ReputationSavedData#reconcilePlayer} while seven
 * service paths called {@link CommunityReputationRecord#reconcile} directly, so a screen opening, an
 * opinion query or a refused duplicate could age a village that was supposed to be protected. Every
 * one of those paths now comes through here, with an explicit {@link Intent}, and the policy exists
 * once.
 *
 * <h2>Freeze</h2>
 *
 * <p>Frozen means the clock advances and the ledger does not (DD2): {@code lastReconciledGameTime}
 * moves forward, {@code decayElapsedTicks} does not. Skipping the community entirely — which is what
 * the old immunity check did — banks the paused interval and pays it out as a catch-up burst the
 * moment protection is lifted, which is the opposite of what §5 F07 asks for.
 */
public final class ReconciliationService {

    /** Why the gate was entered, which decides what it is allowed to do. */
    public enum Intent {

        /** The caller is about to write; the record may have just been created. */
        MUTATE,

        /** A read that may age contributions, but must never create a record. */
        QUERY,

        /** Diagnostics: stored state exactly as it is, no mutation, no creation. */
        INSPECT
    }

    /**
     * What one pass through the gate did.
     *
     * @param contributionDelta the change in the ledger's unclamped contribution sum
     * @param frozen            whether the policy forbade ageing this community
     */
    public record ReconcileOutcome(int oldScore, int newScore, String oldTierId, String newTierId,
                                   long contributionDelta, boolean frozen, long revision,
                                   ChangeCause cause) {

        public boolean scoreChanged() {
            return newScore != oldScore;
        }

        public boolean tierChanged() {
            return !oldTierId.equals(newTierId);
        }
    }

    private ReconciliationService() {
    }

    /** The service-side entry point: the policy comes from the transaction's own snapshot. */
    static ReconcileOutcome reconcile(ServiceContext ctx, ReputationSavedData data, UUID player,
                                      CommunityKey community, long gameTime, ChangeCause cause,
                                      Intent intent) {
        return reconcile(ctx.policy(), data, player, community, gameTime, cause, intent);
    }

    /**
     * The gate itself. Never creates a player or a community record: an absent record answers with the
     * neutral view, which is what §5 F14 requires of a question that should not grow the save.
     */
    public static ReconcileOutcome reconcile(ReputationPolicy policy, ReputationSavedData data,
                                             UUID player, CommunityKey community, long gameTime,
                                             ChangeCause cause, Intent intent) {
        boolean frozen = isFrozen(policy, data, community);
        Optional<CommunityReputationRecord> maybe = data == null || player == null || community == null
                ? Optional.empty()
                : data.player(player).flatMap(record -> record.community(community));
        if (maybe.isEmpty()) {
            String neutral = ReputationService.currentTierId(0);
            return new ReconcileOutcome(0, 0, neutral, neutral, 0L, frozen, 0L, cause);
        }
        CommunityReputationRecord record = maybe.get();
        int oldScore = record.score();
        String oldTierId = ReputationService.currentTierId(oldScore);
        if (intent == Intent.INSPECT) {
            return new ReconcileOutcome(oldScore, oldScore, oldTierId, oldTierId, 0L, frozen,
                    record.revision(), cause);
        }

        long contributionBefore = record.contributionSum();
        long clockBefore = record.lastReconciledGameTime();
        boolean moved;
        if (frozen) {
            // Clock only. Nothing ages, so nothing can be repaid when the freeze lifts.
            record.freezeTo(gameTime);
            moved = record.lastReconciledGameTime() != clockBefore;
        } else {
            moved = record.reconcile(gameTime, policy.minimumScore(), policy.maximumScore());
        }
        int newScore = record.refreshScoreOnly(policy.minimumScore(), policy.maximumScore());
        if (moved) {
            // The persisted clock moved even when the score did not; a crash before the next write
            // would otherwise re-age the interval that was just skipped.
            data.setDirty();
        }
        long revision = newScore == oldScore ? record.revision() : record.bumpRevision();
        return new ReconcileOutcome(oldScore, newScore, oldTierId,
                ReputationService.currentTierId(newScore), record.contributionSum() - contributionBefore,
                frozen, revision, cause);
    }

    /**
     * Whether background ageing is switched off for this community: the master switch, the decay
     * switch, and persisted per-community immunity all mean the same thing here.
     */
    public static boolean isFrozen(ReputationPolicy policy, ReputationSavedData data,
                                   CommunityKey community) {
        if (policy == null || !policy.enabled() || !policy.scoreDecayEnabled()) {
            return true;
        }
        return data != null && data.isDecayImmune(community);
    }

    /**
     * One player, every community they know, through the gate — plus the whole-player incident cap.
     *
     * <p>The single implementation behind both {@link ReputationSavedData#reconcilePlayer} and the
     * service's periodic sweep, so login, the sweep, the command and the API cannot drift apart.
     * {@code onChange} is called for each community whose score actually moved; the sweep publishes a
     * quiet standing change from it, everyone else passes a no-op.
     *
     * @return true when anything moved and the store was marked dirty
     */
    public static boolean reconcilePlayer(ReputationPolicy policy, ReputationSavedData data,
                                          UUID playerId, long gameTime,
                                          BiConsumer<CommunityKey, ReconcileOutcome> onChange) {
        Optional<PlayerReputationRecord> maybe = data.player(playerId);
        if (maybe.isEmpty()) {
            return false;
        }
        PlayerReputationRecord record = maybe.get();
        boolean changed = false;
        List<CommunityKey> keys = new ArrayList<>(record.communityKeys());
        for (CommunityKey key : keys) {
            ReconcileOutcome outcome = reconcile(policy, data, playerId, key, gameTime,
                    ChangeCause.DECAY, Intent.MUTATE);
            if (outcome.scoreChanged()) {
                changed = true;
                onChange.accept(key, outcome);
            } else if (outcome.contributionDelta() != 0) {
                changed = true;
            }
        }
        // Cap enforcement mutates the store (prunes incidents, folds weight into baselines) just as
        // decay does; either kind of change must mark the save dirty or a crash loses it.
        if (record.enforcePlayerIncidentCap(policy.maxIncidentsPerPlayer(), gameTime,
                policy.minimumScore(), policy.maximumScore()) > 0) {
            changed = true;
        }
        // Receipts age out on the same sweep as everything else, so the horizon is enforced without a
        // second schedule and without touching an offline player's record.
        if (record.pruneReceipts(gameTime, policy.receiptRetentionTicks()) > 0) {
            changed = true;
        }
        if (changed) {
            data.setDirty();
        }
        return changed;
    }
}

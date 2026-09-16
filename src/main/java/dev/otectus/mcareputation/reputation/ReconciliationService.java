package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.state.AdmissionPreflight;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import dev.otectus.mcareputation.state.PlayerReputationRecord;
import dev.otectus.mcareputation.state.ProfileFreezeLog;
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
 *
 * <h2>Two channels</h2>
 *
 * <p>Profiles add a second clock to move, and the two freeze independently (§12.2): profile aging
 * stops when profiles are switched off while scalar standing keeps ageing under its own rules.
 * Each channel is advanced here and nowhere else, from the same policy snapshot and the same
 * evaluation time, so a profile answer can never be produced by a path that skipped the gate.
 *
 * <p>The profile channel also has a problem the scalar one does not have to solve. A lazy skip is only
 * as good as the observation that performs it: if nobody reads a record for the whole interval in
 * which profiles were disabled, its own clock cannot tell "nobody looked" from "the interval
 * counted". {@link ProfileFreezeLog} is §12.2's bounded policy epochs — this gate reports the
 * profile-frozen state it observed on every entry, and {@link #observeProfilePolicy} lets a config
 * reload report the transition as it happens, so the disabled interval is subtracted from the ageing
 * of a record that was never here to skip it.
 *
 * <p>Per-community decay immunity keeps the scalar channel's existing lazy-skip semantics in both
 * channels: it is persisted, per-community state, and the global epoch log deliberately does not
 * record it, because one village being protected must not freeze every other village's profile
 * aging. An immunity interval that no query observed therefore behaves exactly as it does for the
 * scalar score today — a known limitation, recorded here rather than papered over (§12.2's "record
 * any unavoidable limitation").
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
     * @param contributionDelta     the change in the ledger's unclamped contribution sum
     * @param frozen                whether the policy forbade ageing this community's score
     * @param profileMagnitudeDelta the change in the ledger's total profile subunit magnitude; never
     *                              positive, because aging and settlement only ever reduce it
     * @param profileFrozen         whether the policy forbade ageing this community's profile channel
     * @param profileRecognition    public recognition after this pass, already capped by the policy
     * @param profileRecognitionDelta the change in that public integer; the transition §15's
     *                              profile-only event has to report, and computable only by the pass
     *                              that saw both sides of it
     * @param profileRevision       the community record's profile revision after this pass
     */
    public record ReconcileOutcome(int oldScore, int newScore, String oldTierId, String newTierId,
                                   long contributionDelta, boolean frozen, long revision,
                                   ChangeCause cause, long profileMagnitudeDelta,
                                   boolean profileFrozen, int profileRecognition,
                                   int profileRecognitionDelta, long profileRevision) {

        public boolean scoreChanged() {
            return newScore != oldScore;
        }

        public boolean tierChanged() {
            return !oldTierId.equals(newTierId);
        }

        /** Whether profile evidence moved on this pass. */
        public boolean profileChanged() {
            return profileMagnitudeDelta != 0L;
        }

        /**
         * Whether this pass moved profile evidence and nothing the standing channel reports.
         *
         * <p>The distinction is the publication contract, not a nicety. Recognition and facets fade on
         * their own authored lifetimes, so a village can change what it is able to say about a player
         * on a day when the score, the tier and the revision all stand still. That has to be
         * publishable as a profile change on its own; announcing it as a standing change would emit a
         * {@code StandingChange} whose old and new scores are identical, which every consumer keyed on
         * standing would either ignore or, worse, treat as a real movement. P5's
         * {@code ReputationProfileChangedEvent} is the other half of this.
         */
        public boolean profileOnlyChange() {
            return profileChanged() && !scoreChanged() && !tierChanged();
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
        boolean profileFrozen = isProfileFrozen(policy, data, community);
        // The epoch observation comes first and happens for every intent, including INSPECT: it
        // records what the *policy* was at this evaluation time, touches no record and no dirty flag,
        // and missing a transition is the one failure mode the log exists to prevent (I04 is about
        // stored state, which this is not).
        observeProfilePolicy(policy, data, gameTime);
        Optional<CommunityReputationRecord> maybe = data == null || player == null || community == null
                ? Optional.empty()
                : data.player(player).flatMap(record -> record.community(community));
        if (maybe.isEmpty()) {
            String neutral = ReputationService.currentTierId(0);
            return new ReconcileOutcome(0, 0, neutral, neutral, 0L, frozen, 0L, cause, 0L,
                    profileFrozen, 0, 0, 0L);
        }
        CommunityReputationRecord record = maybe.get();
        int oldScore = record.score();
        String oldTierId = ReputationService.currentTierId(oldScore);
        if (intent == Intent.INSPECT) {
            // Neither clock, neither ledger, no tracker, no dirty flag (I04, DD7): a diagnostic that
            // promises to show stored state must not be the thing that changes it.
            return new ReconcileOutcome(oldScore, oldScore, oldTierId, oldTierId, 0L, frozen,
                    record.revision(), cause, 0L, profileFrozen,
                    publicRecognition(policy, record.recognitionSubunits()), 0,
                    record.profileRevision());
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

        // The second channel, on the same evaluation time and from the same snapshot — but its freeze
        // is answered in two different ways, because the two halves of the decision are known to
        // different degrees.
        //
        // Per-community immunity is persisted, per-community state that this gate can see at any
        // later read, so it is skipped exactly as the scalar channel skips it: the clock moves, the
        // evidence does not. The global switches are the half no record can observe for itself, and
        // for those the bounded policy epochs are strictly better than a skip — they let this pass
        // charge the interval that really was active and subtract the interval that really was frozen,
        // which is what makes the observed and unobserved cases of §12.2 produce the same answer
        // instead of two defensible-sounding ones.
        boolean immune = data.isDecayImmune(community);
        CommunityReputationRecord.ProfileReconcileResult profile = immune
                ? record.freezeProfilesTo(gameTime)
                : record.reconcileProfiles(gameTime, data.profileFreezeLog());
        // Credit windows retire on this schedule too. Their contract is world time and their own
        // monotonic watermark (§10.5), so neither freeze reaches them: a window that has ended no
        // longer restricts anything, and a window that has not is left alone whatever is switched off.
        int retiredTrackers = record.dropExpiredCreditTrackers(gameTime);

        if (moved || profile.moved() || retiredTrackers > 0) {
            // The persisted clock moved even when the score did not; a crash before the next write
            // would otherwise re-age the interval that was just skipped.
            data.setDirty();
        }
        // The standing revision is deliberately not bumped by a profile-only change: it identifies the
        // standing a consumer cached, and moving it would invalidate every standing cache in the world
        // on a day nothing about standing happened. The profile channel has its own counter, bumped
        // here and only when profile evidence really moved, which is what a profile-dependent
        // consumer invalidates against (§15).
        long revision = newScore == oldScore ? record.revision() : record.bumpRevision();
        int recognitionAfter = publicRecognition(policy, profile.recognitionAfter());
        int recognitionDelta = recognitionAfter - publicRecognition(policy, profile.recognitionBefore());
        long profileRevision = profile.unitsMoved()
                ? record.bumpProfileRevision()
                : record.profileRevision();
        return new ReconcileOutcome(oldScore, newScore, oldTierId,
                ReputationService.currentTierId(newScore), record.contributionSum() - contributionBefore,
                frozen, revision, cause, profile.magnitudeDelta(), profileFrozen, recognitionAfter,
                recognitionDelta, profileRevision);
    }

    /**
     * §7.1's public recognition for a subunit total, under this policy's ceiling.
     *
     * <p>Quantized once, here and in the read model, from the same cap: a gate that reported an
     * uncapped figure would publish a recognition transition no query could reproduce.
     */
    private static int publicRecognition(ReputationPolicy policy, long subunits) {
        int capped = policy == null
                ? dev.otectus.mcareputation.profile.ProfileMath.MAX_RECOGNITION
                : Math.max(0, policy.recognitionCap());
        return Math.min(dev.otectus.mcareputation.profile.ProfileMath.publicRecognition(subunits),
                capped);
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
     * Whether profile aging is switched off globally: the master switch, the decay switch and §20's
     * {@code enableProfiles}.
     *
     * <p>Global on purpose. This is the half of the freeze decision that a record cannot observe for
     * itself, and therefore the half {@link ProfileFreezeLog} has to remember; per-community immunity
     * is persisted state that is visible at any later read and is added by {@link #isProfileFrozen}.
     */
    public static boolean isProfileAgingDisabled(ReputationPolicy policy) {
        return policy == null || !policy.enabled() || !policy.scoreDecayEnabled()
                || !policy.profilesEnabled();
    }

    /**
     * Whether profile aging is switched off for this community: the global switches of
     * {@link #isProfileAgingDisabled} plus the same per-community immunity the score obeys.
     *
     * <p>§20 is explicit that existing global decay disablement and community immunity apply through
     * the canonical gate to profile aging too, so a protected village forgets nothing in either
     * channel.
     */
    public static boolean isProfileFrozen(ReputationPolicy policy, ReputationSavedData data,
                                          CommunityKey community) {
        return isProfileAgingDisabled(policy) || isFrozen(policy, data, community);
    }

    /**
     * Records the profile-frozen state of {@code policy} in the store's bounded policy epochs
     * (§12.2).
     *
     * <p>Called on every gate entry, and callable directly by a config-reload hook that knows a
     * transition just happened. Both matter: the gate covers the ordinary case where something is
     * reading records anyway, and the explicit call covers the case §12.2 insists on — a whole
     * disabled interval with no intervening read, where there is no record access to piggyback on.
     * Cheap and idempotent: an unchanged state writes nothing.
     */
    public static void observeProfilePolicy(ReputationPolicy policy, ReputationSavedData data,
                                            long gameTime) {
        if (data == null) {
            return;
        }
        data.profileFreezeLog().observe(isProfileAgingDisabled(policy), gameTime);
    }

    /**
     * One player, every community they know, through the gate — plus the whole-player incident cap.
     *
     * <p>The single implementation behind both {@link ReputationSavedData#reconcilePlayer} and the
     * service's periodic sweep, so login, the sweep, the command and the API cannot drift apart.
     * {@code onChange} is called for each community whose score actually moved, and for each one whose
     * profile evidence moved on its own; the sweep publishes a quiet standing change from the former
     * and a quiet profile change from the latter, everyone else passes a no-op.
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
            } else if (outcome.contributionDelta() != 0 || outcome.profileChanged()) {
                // Profile evidence that faded without moving the score is still a change to the save,
                // and it is the one change no standing event can describe. The callback is handed it
                // too, because the alternative is a fading recognition value that nothing outside this
                // store ever hears about; the publisher recognises it from profileOnlyChange() and
                // emits the profile envelope alone, never a StandingChange with identical scores
                // (§15).
                changed = true;
                if (outcome.profileOnlyChange()) {
                    onChange.accept(key, outcome);
                }
            }
        }
        // Cap enforcement mutates the store (prunes incidents, folds weight into baselines) just as
        // decay does; either kind of change must mark the save dirty or a crash loses it.
        if (record.enforcePlayerIncidentCap(policy.minimumScore(), policy.maximumScore(),
                AdmissionPreflight.of(policy, gameTime)) > 0) {
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

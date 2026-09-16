package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityMetadata;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import dev.otectus.mcareputation.reputation.ReputationMath;
import dev.otectus.mcareputation.reputation.ReputationPolicy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One player's standing with one community (spec §13.4).
 *
 * <h2>The invariant</h2>
 *
 * <pre>{@code score = clamp(baseline + sum(currentContribution of retained incidents))}</pre>
 *
 * <p>{@link #score} is a <b>cache</b>. §13.4 is explicit that a corrupted cached score must never
 * become authoritative, so {@link #recomputeScore} can rebuild it from the baseline and the ledger at
 * any moment, and {@link #load} does exactly that on every world load rather than trusting the number
 * it just read. Pruning is safe for a stricter reason than it once was: {@link #prune} only ever drops
 * records that already carry no weight, so the invariant holds without the baseline fold the older
 * policy relied on. A full ledger in which everything still carries weight refuses the next deed
 * instead ({@link #canAdmit}); folding live weight to satisfy a display cap preserved today's number
 * while silently changing tomorrow's decay, and §2.3 withdrew it.
 *
 * <p>{@code baseline} is standing that did not come from a deed in this ledger: an administrator's
 * {@code /mcareputation set}, or a legacy Quests balance imported by migration (§32.2). Keeping it
 * separate from incidents is what lets migration preserve the number a player used to see without
 * inventing fictional deeds to explain it.
 */
public final class CommunityReputationRecord {

    private final CommunityKey key;

    /** Insertion-ordered: the ledger reads as a chronology, and pruning "oldest first" is well defined. */
    private final Map<UUID, IncidentRecord> incidents = new LinkedHashMap<>();

    private final Map<ResourceLocation, String> tierHighWater = new LinkedHashMap<>();
    private final Set<ResourceLocation> titles = new LinkedHashSet<>();

    /**
     * Repeat-credit accounting (§10.5). Policy state, not standing: it lives here because the
     * allowance a shipped policy shares is per player per community, and it is deliberately not
     * rebuilt from the ledger or the receipts, both of which expire on their own schedules.
     */
    private final CreditWindowTrackers credit = CreditWindowTrackers.empty();

    private CommunityMetadata metadata = CommunityMetadata.EMPTY;
    private int baseline;
    private int score;
    private long lastReconciledGameTime;
    // Bumped by the service on every real score change, so a consumer can tell one change from a
    // repeat of the same one. Persisted since format 2, or a restart would replay old revisions.
    private long revision;
    // The profile channel's counter. Not persisted; see profileRevision().
    private long profileRevision;

    public CommunityReputationRecord(CommunityKey key) {
        this.key = key;
    }

    public CommunityKey key() {
        return key;
    }

    public CommunityMetadata metadata() {
        return metadata;
    }

    public void setMetadata(CommunityMetadata metadata) {
        this.metadata = metadata == null ? CommunityMetadata.EMPTY : metadata;
    }

    public int baseline() {
        return baseline;
    }

    public int score() {
        return score;
    }

    public long lastReconciledGameTime() {
        return lastReconciledGameTime;
    }

    /** The in-memory change counter; zero for a record that has not moved since it was loaded. */
    public long revision() {
        return revision;
    }

    /** @return the new revision. Called by the service after a change it actually published. */
    public long bumpRevision() {
        return ++revision;
    }

    /**
     * The profile channel's own change counter (§15's three revisions).
     *
     * <p>Separate from {@link #revision()} because the two invalidate different caches: profile
     * evidence fades on its own authored lifetimes, so a village can change what it is able to say
     * about a player on a day the score, the tier and the standing revision all stand still. Bumping
     * the standing revision for that would invalidate every standing cache in the world on a day
     * nothing about standing happened.
     *
     * <p><b>In memory only</b>, deliberately: persisting it would add a field to a save layout this
     * version does not change, and a restart is already an invalidation boundary for a consumer that
     * caches profile-dependent state — §15 asks consumers to re-evaluate at an interaction boundary
     * rather than to trust a number across a session.
     */
    public long profileRevision() {
        return profileRevision;
    }

    /** @return the new profile revision. Called by the gate and the service after a real change. */
    public long bumpProfileRevision() {
        return ++profileRevision;
    }

    /**
     * Whether this record holds nothing worth saving.
     *
     * <p>The credit trackers count. A record whose only content is a live anti-farm counter must be
     * written, or a restart would drop the counter and hand back the allowance it had spent — the
     * eviction exploit of §10.5 arriving through the save path instead of through a cap sweep.
     */
    public boolean isEmpty() {
        return baseline == 0 && incidents.isEmpty() && titles.isEmpty() && tierHighWater.isEmpty()
                && credit.isEmpty();
    }

    /** Chronological view, oldest first. Unmodifiable. */
    public Collection<IncidentRecord> incidents() {
        return Collections.unmodifiableCollection(incidents.values());
    }

    /**
     * Newest first — the order the UI, the API's {@code recentIncidents}, and gossip selection want.
     *
     * <p>By <em>occurrence</em> time, not insertion order: a backdated delivery arrives after deeds it
     * predates, and history that reads out of order is history nobody can follow. The incident id is a
     * stable tie-break so two deeds in the same tick always list the same way round.
     */
    public List<IncidentRecord> incidentsNewestFirst() {
        List<IncidentRecord> ordered = new ArrayList<>(incidents.values());
        ordered.sort(Comparator.comparingLong(IncidentRecord::createdGameTime).reversed()
                .thenComparing(IncidentRecord::id));
        return ordered;
    }

    public Optional<IncidentRecord> incident(UUID id) {
        return Optional.ofNullable(incidents.get(id));
    }

    public int incidentCount() {
        return incidents.size();
    }

    public Set<ResourceLocation> titles() {
        return Collections.unmodifiableSet(titles);
    }

    public boolean hasTitle(ResourceLocation title) {
        return titles.contains(title);
    }

    /** @return true when newly added (§17.4: grants are idempotent and only then post an event). */
    public boolean grantTitle(ResourceLocation title) {
        if (title == null || titles.size() >= ReputationBounds.MAX_TITLES) {
            return false;
        }
        return titles.add(title);
    }

    /** @return true when it was present and removed. */
    public boolean revokeTitle(ResourceLocation title) {
        return titles.remove(title);
    }

    public Optional<String> tierHighWater(ResourceLocation ladder) {
        return Optional.ofNullable(tierHighWater.get(ladder));
    }

    public Map<ResourceLocation, String> allTierHighWater() {
        return Collections.unmodifiableMap(tierHighWater);
    }

    public void setTierHighWater(ResourceLocation ladder, String tierId) {
        if (ladder != null && tierId != null) {
            tierHighWater.put(ladder, tierId);
        }
    }

    // --- repeat credit ------------------------------------------------------

    /**
     * The repeat-credit counters for this player in this community (§10.5).
     *
     * <p>Exposed rather than wrapped because the two callers want different halves of it: the staged
     * transaction reserves and consumes an allowance, and the capacity diagnostic reads the window
     * identities. Neither is allowed to decide a <em>percentage</em> — that is {@code CreditResolver}
     * against the frozen {@code CreditPolicy}, and keeping the decision out of here is what stops a
     * schedule from being reimplemented next to the counters it reads.
     */
    public CreditWindowTrackers creditTrackers() {
        return credit;
    }

    /**
     * Where an operation would land in its credit window, creating nothing (I04).
     *
     * <p>This is the read a staged operation takes <em>before</em> anything is written, so a refusal
     * cannot have spent an allowance. {@link CreditWindowTrackers#consume} is the matching write, and
     * the two agree by construction: both ignore trackers whose window has already ended at the same
     * evaluation time.
     */
    public CreditWindowTrackers.CreditWindow peekCreditWindow(ResourceLocation group,
                                                              Optional<String> subjectKey,
                                                              boolean subjectRequired,
                                                              long acceptanceTime) {
        return credit.peek(group, subjectKey, subjectRequired, acceptanceTime);
    }

    // --- score --------------------------------------------------------------

    /**
     * Recomputes and stores the score from baseline plus every retained contribution. Cheap (the
     * ledger is capped at 64 entries per community), so it is called after every mutation rather than
     * incrementally patched — an incremental cache is exactly the kind of thing that drifts.
     */
    public int recomputeScore(int minScore, int maxScore) {
        List<Integer> contributions = new ArrayList<>(incidents.size());
        for (IncidentRecord incident : incidents.values()) {
            contributions.add(incident.currentContribution());
        }
        score = ReputationMath.totalScore(baseline, contributions, minScore, maxScore);
        return score;
    }

    /**
     * Sets the baseline. Clamped by {@link ReputationMath#clampBaseline}, <b>not</b> the score
     * window: the baseline may hold overflow beyond the visible clamp so that
     * {@code clamp(baseline + contributions)} can land exactly where an administrator or a fold
     * asked, whatever the ledger currently sums to. Only the visible score is window-clamped.
     */
    public void setBaseline(long value, int minScore, int maxScore) {
        baseline = ReputationMath.clampBaseline(value);
        recomputeScore(minScore, maxScore);
    }

    /** Adds to the baseline. Same wide clamp as {@link #setBaseline}. */
    public void addBaseline(int delta, int minScore, int maxScore) {
        baseline = ReputationMath.clampBaseline((long) baseline + delta);
        recomputeScore(minScore, maxScore);
    }

    /**
     * The unclamped sum of every retained incident's current contribution. This — not
     * {@code score - baseline}, which is distorted whenever the clamp is engaged — is what an
     * absolute {@code /mcareputation set} must subtract to land exactly on its target.
     */
    public long contributionSum() {
        long sum = 0;
        for (IncidentRecord incident : incidents.values()) {
            sum += incident.currentContribution();
        }
        return sum;
    }

    // --- incidents ----------------------------------------------------------

    public void addIncident(IncidentRecord incident) {
        incidents.put(incident.id(), incident);
    }

    /**
     * Brings every incident's decay up to date and rewrites the score.
     *
     * <p>Lazy by design (§15.1): this runs on query, mutation, login, screen open, and a rate-limited
     * online sweep — never on a tick over all saved players. Idempotent, so the overlapping triggers
     * cost at most a pass over one player's ledger.
     *
     * @return true when anything actually moved
     */
    public boolean reconcile(long gameTime, int minScore, int maxScore) {
        boolean changed = false;
        for (IncidentRecord incident : incidents.values()) {
            IncidentDefinition definition = IncidentRegistry.getOrUnknown(incident.type());
            if (incident.reconcile(definition.decay(), gameTime) != 0) {
                changed = true;
            }
            // Expiry is assigned here, during reconciliation — nothing else ever computes it — so a
            // fully decayed record past retention actually reads "expired" in the ledger instead of
            // claiming ACTIVE forever.
            if (incident.status() == IncidentStatus.ACTIVE
                    && incident.isExpired(definition, gameTime)) {
                incident.markExpired(gameTime);
                changed = true;
            }
        }
        if (gameTime > lastReconciledGameTime) {
            lastReconciledGameTime = gameTime;
        }
        int before = score;
        recomputeScore(minScore, maxScore);
        return changed || before != score;
    }

    /**
     * The community half of a decay freeze: advance every incident's clock, and this record's, without
     * ageing anything (§5 F07, DD2).
     *
     * <p>The score cannot move, so it is deliberately not recomputed here. What this buys is the
     * no-catch-up guarantee: when the freeze is lifted, the paused interval has already been skipped
     * rather than banked.
     */
    public void freezeTo(long gameTime) {
        for (IncidentRecord incident : incidents.values()) {
            incident.skipDecayTo(gameTime);
        }
        if (gameTime > lastReconciledGameTime) {
            lastReconciledGameTime = gameTime;
        }
    }

    // --- the profile channel (§12.2) ----------------------------------------

    /**
     * What one profile pass over this ledger did.
     *
     * <p>The two recognition sums are here rather than recomputed by the caller because only this
     * pass can see both sides of it: recognition fades on the deeds' own lifetimes, so the value
     * before the pass no longer exists once the pass has run. §15's profile-only change event needs
     * the transition, and a caller folding the ledger afterwards could only ever report the new value
     * twice.
     *
     * @param magnitudeDelta      change in total profile subunit magnitude across every channel
     * @param clockMoved          whether any record's profile observation clock advanced
     * @param recognitionBefore   the ledger's recognition subunits before the pass
     * @param recognitionAfter    the same sum after it
     */
    public record ProfileReconcileResult(long magnitudeDelta, boolean clockMoved,
                                         long recognitionBefore, long recognitionAfter) {

        /** Whether anything persisted moved, which is what decides {@code setDirty}. */
        public boolean moved() {
            return magnitudeDelta != 0L || clockMoved;
        }

        /** Whether profile evidence actually changed value, as opposed to only its clock advancing. */
        public boolean unitsMoved() {
            return magnitudeDelta != 0L;
        }
    }

    /**
     * Ages every profile payload in this ledger and settles it, charging only the active ticks
     * (§12.2).
     *
     * <p>Separate from {@link #reconcile} rather than folded into it, because the two channels freeze
     * independently: profiles may be switched off while scalar standing keeps ageing, and the gate has
     * to be able to say "age one, not the other" without a second policy appearing inside this class.
     *
     * <p>Records with no profile payload are skipped entirely — they have no profile channel, and
     * moving a clock they never use would start writing a field the format-2 ledger did not have,
     * which is precisely the byte-identity the golden fixtures check.
     *
     * @param freezeLog the bounded policy epochs (§12.2), or {@code null} to charge the whole interval
     */
    public ProfileReconcileResult reconcileProfiles(long gameTime,
                                                    @Nullable ProfileFreezeLog freezeLog) {
        long delta = 0L;
        boolean clockMoved = false;
        long recognitionBefore = recognitionSubunits();
        for (IncidentRecord incident : incidents.values()) {
            if (!incident.hasProfileEvidence()) {
                continue;
            }
            long observed = incident.lastProfileObservedGameTime();
            long frozen = freezeLog == null ? 0L : freezeLog.frozenTicksBetween(observed, gameTime);
            delta += incident.reconcileProfile(gameTime, frozen);
            if (incident.lastProfileObservedGameTime() != observed) {
                clockMoved = true;
            }
        }
        return new ProfileReconcileResult(delta, clockMoved, recognitionBefore, recognitionSubunits());
    }

    /**
     * This ledger's recognition contribution, in subunits (§7.1).
     *
     * <p>Exactly the summation the public read model performs, including the two exclusions: a folded
     * record's weight was absorbed by its successor and a disproven one evidences nothing, though both
     * keep their frozen units on disk. Two different answers to "how well known is this player" would
     * be worse than none.
     */
    public long recognitionSubunits() {
        long total = 0L;
        for (IncidentRecord incident : incidents.values()) {
            if (incident.isSuperseded() || incident.status() == IncidentStatus.DISPROVEN) {
                continue;
            }
            Optional<dev.otectus.mcareputation.profile.IncidentProfileEvidence> evidence =
                    incident.profileEvidence();
            if (evidence.isEmpty()) {
                continue;
            }
            for (var channel : evidence.get().channels()) {
                if (channel.isRecognition()) {
                    total = dev.otectus.mcareputation.profile.ProfileMath.add(total, channel.current());
                }
            }
        }
        return total;
    }

    /**
     * The profile half of a freeze: advance the observation clock of every payload without ageing
     * anything (§12.2, §20).
     *
     * <p>The exact analogue of {@link #freezeTo} for the second channel, and the gate uses it for the
     * one half of the freeze decision the epoch log deliberately does not track: per-community decay
     * immunity. Skipping the interval as it passes is what makes lifting the freeze cost no catch-up
     * for every interval a query observed, which is exactly the guarantee the scalar channel offers
     * for the same flag.
     */
    public ProfileReconcileResult freezeProfilesTo(long gameTime) {
        boolean clockMoved = false;
        for (IncidentRecord incident : incidents.values()) {
            if (!incident.hasProfileEvidence()) {
                continue;
            }
            long observed = incident.lastProfileObservedGameTime();
            incident.skipProfileTo(gameTime);
            if (incident.lastProfileObservedGameTime() != observed) {
                clockMoved = true;
            }
        }
        // Nothing aged, so the recognition sum is the same on both sides by construction; reading it
        // once and reporting it twice is the honest way to say that.
        long recognition = recognitionSubunits();
        return new ProfileReconcileResult(0L, clockMoved, recognition, recognition);
    }

    /**
     * Retires every credit tracker whose window has ended at this evaluation time (§10.5).
     *
     * <p>Called from the reconciliation gate, so the counters are cleaned on the same schedule as
     * everything else rather than only when the next deed happens to arrive. It cannot free a slot
     * that still restricts an operation — {@link CreditWindowTrackers#dropExpired} is the only removal
     * path and it measures each window from its own monotonic watermark — so this is cleanup, never
     * the eviction exploit that would hand back a spent allowance.
     *
     * @return how many trackers were removed
     */
    public int dropExpiredCreditTrackers(long gameTime) {
        return credit.dropExpired(gameTime);
    }

    /**
     * Rebuilds the cached score and nothing else. The reconciliation gate needs the bounds re-applied
     * after a freeze or a config change without ageing a single contribution.
     */
    public int refreshScoreOnly(int minScore, int maxScore) {
        return recomputeScore(minScore, maxScore);
    }

    /**
     * Whether this record may be dropped to make room (§5 F09).
     *
     * <p>Four things are not evictable at any cap. <b>Pinned</b> history, as before. Anything that
     * still <b>contributes</b>, because folding live weight into the baseline preserves today's number
     * while silently changing tomorrow's - the fold does not decay, so the trajectory, the per-villager
     * opinion and the amends that were still available all move. And a recent <b>open negative</b>
     * record, which is the case a player can still make right and a producer may still hold a receipt
     * against; it becomes evictable once it has aged past the receipt horizon. And, since profiles,
     * anything holding <b>live profile subunits</b>, which stay future-relevant after the scalar
     * contribution has decayed to zero (§12.3).
     *
     * <p>Read at {@link AdmissionPreflight#evaluationTime()} and nowhere else. The age comparison is
     * the reason that matters: asked twice at two clock readings, the same ledger gives two answers,
     * and a refusal that disagrees with the eviction pass that follows it is how a cap gets exceeded.
     */
    private boolean evictable(IncidentRecord incident, AdmissionPreflight preflight) {
        if (incident.pinned() || incident.contributes()) {
            return false;
        }
        // Four, since profiles: live profile subunits are future-relevant evidence even when the
        // scalar contribution has decayed to nothing and the displayed facet value clamps to zero
        // (§12.3, I06). Dropping such a record keeps today's number and quietly changes what the
        // village is able to say about the player, and what a later opposing deed is weighed against.
        if (preflight.protectsLiveProfileEvidence() && incident.hasLiveProfileEvidence()) {
            return false;
        }
        boolean open = incident.status() == IncidentStatus.ACTIVE && !incident.isSuperseded();
        return !(open && incident.baseDelta() < 0
                && incident.ageTicks(preflight.evaluationTime()) < preflight.receiptHorizonTicks());
    }

    /**
     * Whether one more incident can be admitted without exceeding the cap: either there is room, or
     * something in the ledger is evictable. When this is false the deed is refused with
     * {@code Reason.CAPACITY} <em>before</em> anything is written (D5), rather than the cap being
     * quietly exceeded and live history evicted to pay for it.
     *
     * <p>Answer this <em>after</em> the operation's reconciliation pass, never before. Eligibility is
     * a function of age, so a ledger full of contributions that decay to zero at the evaluation time
     * is admissible - and a preflight that runs first sees them still live and refuses every deed,
     * permanently, because nothing in the refusal path ever reaches reconciliation to age them
     * (§3.2, §11.1 step 4).
     */
    public boolean canAdmit(AdmissionPreflight preflight) {
        if (incidents.size() < preflight.maxIncidentsPerCommunity()) {
            return true;
        }
        return hasEvictableIncident(preflight);
    }

    /** How many records could be dropped to make room; what the capacity diagnostic reports (D5). */
    public int evictableIncidentCount(AdmissionPreflight preflight) {
        int count = 0;
        for (IncidentRecord incident : incidents.values()) {
            if (evictable(incident, preflight)) {
                count++;
            }
        }
        return count;
    }

    /** Whether anything in this ledger could be dropped at all. */
    public boolean hasEvictableIncident(AdmissionPreflight preflight) {
        for (IncidentRecord incident : incidents.values()) {
            if (evictable(incident, preflight)) {
                return true;
            }
        }
        return false;
    }

    /** The cap sweep at the default receipt horizon. */
    public List<IncidentRecord> prune(int maxIncidents, long gameTime, int minScore, int maxScore) {
        return prune(minScore, maxScore, AdmissionPreflight.ofLoose(maxIncidents, gameTime,
                ReputationPolicy.DEFAULT_RECEIPT_RETENTION_TICKS));
    }

    /**
     * Enforces the per-community incident cap in the priority order of §13.5.
     *
     * <p>The ordering is the whole point: history is discarded in the order it stops mattering.
     * <b>Nothing live is dropped at all.</b> {@link #evictable} refuses any record that still
     * contributes, so the four passes below choose only between records already worth zero and the
     * baseline fold is a belt-and-braces guard that cannot fire — the earlier policy of folding live
     * weight to satisfy a display cap is what §2.3 withdrew, because it preserved today's number while
     * changing tomorrow's decay, speaker knowledge and resolution options. A ledger with nothing
     * evictable is refused admission instead (see {@link #canAdmit}); losing the <em>explanation</em>
     * for standing is acceptable when the ledger is full, losing the evidence is not.
     *
     * <p>Pinned incidents are never dropped. If a ledger somehow consists entirely of pinned entries
     * the cap is exceeded rather than violated, and the situation is logged; only an administrator
     * clearing the pin can resolve it.
     *
     * @return the incidents that were removed, oldest first
     */
    public List<IncidentRecord> prune(int minScore, int maxScore, AdmissionPreflight preflight) {
        int maxIncidents = preflight.maxIncidentsPerCommunity();
        long gameTime = preflight.evaluationTime();
        List<IncidentRecord> removed = new ArrayList<>();
        if (incidents.size() <= maxIncidents) {
            return removed;
        }

        List<java.util.function.Predicate<IncidentRecord>> passes = List.of(
                // 1. expired, zero-contribution, past retention
                incident -> !incident.contributes()
                        && incident.isExpired(IncidentRegistry.getOrUnknown(incident.type()), gameTime),
                // 2. resolved, zero-contribution
                incident -> !incident.contributes() && incident.status().isResolved(),
                // 3. non-notable, zero-contribution
                incident -> !incident.contributes() && !incident.severity().isNotable(),
                // 4. anything not pinned, oldest first
                incident -> true);

        // Folds accumulate unclamped and land on the baseline once, after the sweep. Clamping each
        // fold individually would violate "clamp last, once" (ReputationMath): with the baseline near
        // the ceiling a clamped fold silently discards weight, and the surviving incidents would then
        // move the score — exactly what this method promises never happens.
        long folded = 0;
        for (java.util.function.Predicate<IncidentRecord> pass : passes) {
            for (IncidentRecord candidate : new ArrayList<>(incidents.values())) {
                if (incidents.size() <= maxIncidents) {
                    break;
                }
                if (!evictable(candidate, preflight) || !pass.test(candidate)) {
                    continue;
                }
                // Retained weight can no longer reach this point - evictable() rejects anything that
                // still contributes - but the fold stays, because a pass must never be able to move
                // the score by dropping what it admitted.
                if (candidate.contributes()) {
                    folded += candidate.currentContribution();
                    // Absorbed, not superseded: no successor took this weight over, the baseline did.
                    candidate.absorbIntoBaseline(gameTime);
                }
                incidents.remove(candidate.id());
                removed.add(candidate);
            }
            if (incidents.size() <= maxIncidents) {
                break;
            }
        }
        if (folded != 0) {
            baseline = ReputationMath.clampBaseline(baseline + folded);
        }

        if (incidents.size() > maxIncidents) {
            McaReputation.LOGGER.warn(
                    "[MCA: Reputation] community {} holds {} incidents, above the cap of {}, because the "
                            + "remainder are pinned, still count, or are still open. Clear a pin with "
                            + "/mcareputation incident pin <player> <uuid> false.",
                    key.asString(), incidents.size(), maxIncidents);
        }
        if (!removed.isEmpty()) {
            recomputeScore(minScore, maxScore);
            McaReputation.LOGGER.warn("[MCA: Reputation] pruned {} incident(s) from community {} at the {} cap",
                    removed.size(), key.asString(), maxIncidents);
        }
        return removed;
    }

    // --- persistence --------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("key", key.save());
        CompoundTag meta = metadata.save();
        if (!meta.isEmpty()) {
            tag.put("meta", meta);
        }
        tag.putInt("baseline", baseline);
        tag.putInt("score", score);
        tag.putLong("reconciled", lastReconciledGameTime);
        // Format 2: a consumer that caches by revision must not see it restart at zero after a reload.
        if (revision != 0) {
            tag.putLong("revision", revision);
        }

        if (!incidents.isEmpty()) {
            ListTag list = new ListTag();
            incidents.values().forEach(incident -> list.add(incident.save()));
            tag.put("incidents", list);
        }
        if (!tierHighWater.isEmpty()) {
            CompoundTag hw = new CompoundTag();
            tierHighWater.forEach((ladder, tier) -> hw.putString(ladder.toString(), tier));
            tag.put("tierHighWater", hw);
        }
        if (!titles.isEmpty()) {
            ListTag list = new ListTag();
            titles.forEach(title -> list.add(StringTag.valueOf(title.toString())));
            tag.put("titles", list);
        }
        // Format 3, written only when something is tracked, so a ledger that has never seen a
        // credited deed is byte-identical to what format 2 produced.
        CompoundTag creditTag = credit.save();
        if (!creditTag.isEmpty()) {
            tag.put("credit", creditTag);
        }
        return tag;
    }

    /**
     * Reads one community record. Empty only when the key itself is unusable; every softer failure —
     * a malformed incident, an unparseable ladder id, a bad title — skips that one entry and keeps the
     * rest (§13.6).
     *
     * <p>The stored {@code score} is deliberately <b>not</b> trusted: it is recomputed from baseline
     * plus contributions, which is the §13.4 invariant and also repairs a save damaged by a crash
     * mid-write.
     */
    public static Optional<CommunityReputationRecord> load(CompoundTag tag, int minScore, int maxScore) {
        Optional<CommunityKey> key = CommunityKey.load(tag.getCompound("key"));
        if (key.isEmpty()) {
            McaReputation.LOGGER.debug("[MCA: Reputation] skipping community record with an unreadable key");
            return Optional.empty();
        }
        CommunityReputationRecord record = new CommunityReputationRecord(key.get());
        record.metadata = CommunityMetadata.load(tag.getCompound("meta"));
        // The wide baseline clamp, not the score window: a baseline holding fold overflow (see
        // setBaseline) must survive a save/load cycle bit-for-bit or the reload changes the score.
        record.baseline = ReputationMath.clampBaseline(tag.getInt("baseline"));
        record.lastReconciledGameTime = tag.getLong("reconciled");
        record.revision = tag.getLong("revision");

        // §19.4: a malformed profile payload is quarantined and its scalar incident is kept. The path
        // names the incident so an operator can find it; the reason comes from the reader.
        IncidentRecord.QuarantineSink payloadSink = (incidentId, reason, payload) ->
                SaveQuarantine.holdProfilePayload("community/" + record.key.asString() + "/incidents/"
                        + incidentId + "/profile", reason, payload);

        ListTag incidentList = tag.getList("incidents", Tag.TAG_COMPOUND);
        for (int i = 0; i < incidentList.size(); i++) {
            CompoundTag entry = incidentList.getCompound(i);
            try {
                IncidentRecord.load(entry, payloadSink).ifPresentOrElse(
                        record::addIncident,
                        () -> {
                            McaReputation.LOGGER.debug(
                                    "[MCA: Reputation] quarantining malformed incident in community {}",
                                    record.key.asString());
                            SaveQuarantine.hold("community/" + record.key.asString() + "/incidents",
                                    "unreadable incident entry", entry);
                        });
            } catch (Throwable t) {
                McaReputation.LOGGER.debug("[MCA: Reputation] quarantining incident that threw while "
                        + "loading in {}", record.key.asString(), t);
                SaveQuarantine.hold("community/" + record.key.asString() + "/incidents",
                        "incident threw while loading: " + t, entry);
            }
        }

        CompoundTag hw = tag.getCompound("tierHighWater");
        for (String ladderId : hw.getAllKeys()) {
            if (record.tierHighWater.size() >= ReputationBounds.MAX_LADDER_HIGH_WATER) {
                break;
            }
            ResourceLocation ladder = ResourceLocation.tryParse(ladderId);
            if (ladder != null) {
                record.tierHighWater.put(ladder, hw.getString(ladderId));
            }
        }

        record.credit.absorb(CreditWindowTrackers.load(tag.getCompound("credit")));

        ListTag titleList = tag.getList("titles", Tag.TAG_STRING);
        for (int i = 0; i < titleList.size() && record.titles.size() < ReputationBounds.MAX_TITLES; i++) {
            ResourceLocation title = ResourceLocation.tryParse(titleList.getString(i));
            if (title != null) {
                record.titles.add(title);
            }
        }

        // §5 F09: the load path applies the *runtime* admission policy rather than truncating. What a
        // prune would have evicted anyway is absorbed into the baseline exactly as it would have been;
        // what may not be evicted is kept even above the cap, because dropping it and calling the
        // resulting score a repair is the defect this replaces.
        int onDisk = record.incidents.size();
        if (onDisk > ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY) {
            record.recomputeScore(minScore, maxScore);
            record.prune(ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY, record.lastReconciledGameTime,
                    minScore, maxScore);
            if (record.incidents.size() > ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY) {
                McaReputation.LOGGER.warn("[MCA: Reputation] community {} carried {} incidents on disk and "
                                + "{} of them may not be evicted, so the {} cap is exceeded rather than "
                                + "history being discarded", record.key.asString(), onDisk,
                        record.incidents.size(), ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY);
            }
        }

        int cached = tag.getInt("score");
        int recomputed = record.recomputeScore(minScore, maxScore);
        if (cached != recomputed) {
            McaReputation.LOGGER.warn(
                    "[MCA: Reputation] repaired cached score for community {}: stored {}, recomputed {} "
                            + "from baseline {} plus {} incident(s)",
                    record.key.asString(), cached, recomputed, record.baseline, record.incidents.size());
        }
        return Optional.of(record);
    }

    /** Deterministic ordering for UI lists and command suggestions: highest standing first, then key. */
    public static final Comparator<CommunityReputationRecord> BY_STANDING =
            Comparator.comparingInt(CommunityReputationRecord::score).reversed()
                    .thenComparing(CommunityReputationRecord::key);
}

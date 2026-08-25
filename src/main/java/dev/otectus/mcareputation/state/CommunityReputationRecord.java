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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

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
 * it just read. That is also what makes pruning safe: dropping an incident that still carries weight
 * would silently change the score, so {@link #prune} folds any remaining contribution into the
 * baseline first and the invariant holds across the operation.
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

    private CommunityMetadata metadata = CommunityMetadata.EMPTY;
    private int baseline;
    private int score;
    private long lastReconciledGameTime;

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

    public boolean isEmpty() {
        return baseline == 0 && incidents.isEmpty() && titles.isEmpty() && tierHighWater.isEmpty();
    }

    /** Chronological view, oldest first. Unmodifiable. */
    public Collection<IncidentRecord> incidents() {
        return Collections.unmodifiableCollection(incidents.values());
    }

    /** Newest first — the order the UI, the API's {@code recentIncidents}, and gossip selection want. */
    public List<IncidentRecord> incidentsNewestFirst() {
        List<IncidentRecord> ordered = new ArrayList<>(incidents.values());
        Collections.reverse(ordered);
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
     * Enforces the per-community incident cap in the priority order of §13.5.
     *
     * <p>The ordering is the whole point: history is discarded in the order it stops mattering.
     * Anything still carrying weight has that weight folded into the baseline first, so the player's
     * score is bit-for-bit unchanged by pruning — losing the <em>explanation</em> for standing is
     * acceptable when the ledger is full, silently losing the standing itself is not.
     *
     * <p>Pinned incidents are never dropped. If a ledger somehow consists entirely of pinned entries
     * the cap is exceeded rather than violated, and the situation is logged; only an administrator
     * clearing the pin can resolve it.
     *
     * @return the incidents that were removed, oldest first
     */
    public List<IncidentRecord> prune(int maxIncidents, long gameTime, int minScore, int maxScore) {
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
                if (candidate.pinned() || !pass.test(candidate)) {
                    continue;
                }
                // Fold before dropping: the score must not move because history was trimmed.
                if (candidate.contributes()) {
                    folded += candidate.currentContribution();
                    candidate.foldInto(null, gameTime);
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
                            + "remainder are pinned. Clear a pin with /mcareputation incident pin <player> <uuid> false.",
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

        ListTag incidentList = tag.getList("incidents", Tag.TAG_COMPOUND);
        for (int i = 0; i < incidentList.size()
                && record.incidents.size() < ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY; i++) {
            try {
                IncidentRecord.load(incidentList.getCompound(i)).ifPresentOrElse(
                        record::addIncident,
                        () -> McaReputation.LOGGER.debug(
                                "[MCA: Reputation] skipping malformed incident in community {}",
                                record.key.asString()));
            } catch (Throwable t) {
                McaReputation.LOGGER.debug("[MCA: Reputation] skipping incident that threw while loading in {}",
                        record.key.asString(), t);
            }
        }
        if (incidentList.size() > ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY) {
            // §13.5/§34: the load path enforces the same ceiling as the write path, so a hand-edited
            // or corrupted save cannot smuggle an unbounded ledger into memory.
            McaReputation.LOGGER.warn("[MCA: Reputation] community {} carried {} incidents on disk, "
                            + "above the {} cap; the oldest beyond the cap were not loaded",
                    record.key.asString(), incidentList.size(),
                    ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY);
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

        ListTag titleList = tag.getList("titles", Tag.TAG_STRING);
        for (int i = 0; i < titleList.size() && record.titles.size() < ReputationBounds.MAX_TITLES; i++) {
            ResourceLocation title = ResourceLocation.tryParse(titleList.getString(i));
            if (title != null) {
                record.titles.add(title);
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

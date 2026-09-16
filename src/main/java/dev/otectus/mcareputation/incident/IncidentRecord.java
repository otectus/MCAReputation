package dev.otectus.mcareputation.incident;

import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import dev.otectus.mcareputation.reputation.ReputationMath;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One deed, as the server remembers it (spec §14).
 *
 * <p>A record is created once and then only ever changes in the narrow ways §14 allows: its status,
 * its current contribution, its updated time, its witness set (a killing unions in the witnesses of
 * the assault it upgrades, §20.2), its context, and whether it is pinned. Everything that identifies
 * <em>what happened</em> — who, where, when, how much it was originally worth, who saw it — is final.
 * That is what makes the ledger an explanation of the score rather than a second copy of it.
 *
 * <h2>The three deltas</h2>
 *
 * <p>Three numbers sound alike and are not:
 *
 * <ul>
 *   <li>{@link #baseDelta()} — what this deed was worth when it happened. Immutable. Resolution
 *       multipliers are always applied to <em>this</em>, which is what makes resolving idempotent no
 *       matter how much decay has already run.</li>
 *   <li>{@link #settledDelta()} — what it is worth after any resolution. Equal to the base while
 *       {@code ACTIVE}.</li>
 *   <li>{@link #currentContribution()} — what it is worth right now, after decay. This is the only
 *       one that feeds the score sum in §13.4.</li>
 * </ul>
 *
 * <h2>Monotonic time</h2>
 *
 * <p>Decay is driven by {@link #decayElapsedTicks()}, a counter that only ever moves forward, rather
 * than by {@code now - created}. {@link #reconcile} advances it by the <em>positive</em> difference
 * since the last reconciliation and ignores anything else, so {@code /time set} into the past adds
 * nothing and can never hand back contribution the player already lost (§15.1, §33 rule 13).
 */
public final class IncidentRecord {

    // --- immutable identity -------------------------------------------------

    private final UUID id;
    private final ResourceLocation type;
    private final UUID player;
    private final CommunityKey community;
    /** When the deed happened, which for a backdated delivery is older than when it was filed. */
    private final long createdGameTime;
    /** When the deed entered the ledger; equal to {@link #createdGameTime} for a live deed. */
    private final long appliedGameTime;
    private final ResourceLocation source;
    private final Optional<String> dedupeKey;
    private final int baseDelta;
    private final IncidentVisibility visibility;
    private final IncidentSeverity severity;
    private final List<IncidentSubject> subjects;

    // --- bounded, mutable detail -------------------------------------------

    private final Set<UUID> witnesses = new LinkedHashSet<>();
    private final Map<String, String> context = new LinkedHashMap<>();

    // --- mutable state ------------------------------------------------------

    private IncidentStatus status;
    private int settledDelta;
    private int currentContribution;
    private long updatedGameTime;
    private long decayElapsedTicks;
    private long lastReconciledGameTime;
    private boolean pinned;
    private boolean superseded;
    @Nullable
    private UUID supersededBy;
    private long storyRevision;

    private IncidentRecord(UUID id, ResourceLocation type, UUID player, CommunityKey community,
                           long createdGameTime, long appliedGameTime, ResourceLocation source,
                           Optional<String> dedupeKey,
                           int baseDelta, IncidentVisibility visibility, IncidentSeverity severity,
                           List<IncidentSubject> subjects) {
        this.id = id;
        this.type = type;
        this.player = player;
        this.community = community;
        this.createdGameTime = createdGameTime;
        this.appliedGameTime = appliedGameTime;
        this.source = source;
        this.dedupeKey = dedupeKey;
        this.baseDelta = baseDelta;
        this.visibility = visibility;
        this.severity = severity;
        this.subjects = List.copyOf(subjects.subList(0, Math.min(subjects.size(), ReputationBounds.MAX_SUBJECTS)));
        this.status = IncidentStatus.ACTIVE;
        this.settledDelta = baseDelta;
        this.currentContribution = baseDelta;
        this.updatedGameTime = createdGameTime;
        this.decayElapsedTicks = 0L;
        this.lastReconciledGameTime = createdGameTime;
        this.pinned = false;
        this.superseded = false;
        this.supersededBy = null;
        this.storyRevision = 0L;
    }

    /**
     * Creates a fresh, {@code ACTIVE} record contributing its full delta. Callers go through
     * {@code ReputationService}, never here directly — the service is the only place allowed to decide
     * a delta, resolve witnesses, and commit the surrounding transaction (§8, §18).
     */
    public static IncidentRecord create(UUID id, ResourceLocation type, UUID player, CommunityKey community,
                                        long gameTime, ResourceLocation source, Optional<String> dedupeKey,
                                        int delta, IncidentVisibility visibility, IncidentSeverity severity,
                                        List<IncidentSubject> subjects) {
        return create(id, type, player, community, gameTime, gameTime, source, dedupeKey, delta,
                visibility, severity, subjects);
    }

    /**
     * As {@link #create}, for a deed whose occurrence time is older than the moment it was filed: a
     * backdated delivery names both times, and the pair is what lets the ledger age the arriving
     * contribution before it ever reaches the score.
     */
    public static IncidentRecord create(UUID id, ResourceLocation type, UUID player, CommunityKey community,
                                        long occurredGameTime, long appliedGameTime,
                                        ResourceLocation source, Optional<String> dedupeKey,
                                        int delta, IncidentVisibility visibility, IncidentSeverity severity,
                                        List<IncidentSubject> subjects) {
        return new IncidentRecord(id, type, player, community, occurredGameTime, appliedGameTime, source,
                boundDedupe(dedupeKey), delta, visibility, severity, subjects);
    }

    private static Optional<String> boundDedupe(Optional<String> raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return raw.map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> s.length() <= ReputationBounds.MAX_DEDUPE_KEY_LENGTH
                        ? s
                        : s.substring(0, ReputationBounds.MAX_DEDUPE_KEY_LENGTH));
    }

    // --- accessors ----------------------------------------------------------

    public UUID id() {
        return id;
    }

    public ResourceLocation type() {
        return type;
    }

    public UUID player() {
        return player;
    }

    public CommunityKey community() {
        return community;
    }

    public long createdGameTime() {
        return createdGameTime;
    }

    /** When this record entered the ledger; equal to {@link #createdGameTime()} unless backdated. */
    public long appliedGameTime() {
        return appliedGameTime;
    }

    public long updatedGameTime() {
        return updatedGameTime;
    }

    public ResourceLocation source() {
        return source;
    }

    public Optional<String> dedupeKey() {
        return dedupeKey;
    }

    public int baseDelta() {
        return baseDelta;
    }

    public int settledDelta() {
        return settledDelta;
    }

    public int currentContribution() {
        return currentContribution;
    }

    public IncidentVisibility visibility() {
        return visibility;
    }

    public IncidentSeverity severity() {
        return severity;
    }

    public IncidentStatus status() {
        return status;
    }

    public List<IncidentSubject> subjects() {
        return subjects;
    }

    public long decayElapsedTicks() {
        return decayElapsedTicks;
    }

    public boolean pinned() {
        return pinned;
    }

    public void setPinned(boolean value) {
        this.pinned = value;
    }

    /** Unmodifiable view; witnesses are added only through {@link #addWitnesses}. */
    public Set<UUID> witnesses() {
        return Collections.unmodifiableSet(witnesses);
    }

    /** Unmodifiable view; context is written only through {@link #putContext}. */
    public Map<String, String> context() {
        return Collections.unmodifiableMap(context);
    }

    public Optional<String> context(String key) {
        return Optional.ofNullable(context.get(normalizeContextKey(key)));
    }

    public boolean isWitness(UUID villager) {
        return villager != null && witnesses.contains(villager);
    }

    /**
     * True when this incident currently counts toward the player's standing. A superseded record never
     * does: its weight was absorbed by the incident that replaced it (§5 F08, DD3).
     */
    public boolean contributes() {
        return !superseded && currentContribution != 0;
    }

    /** True when another incident absorbed this one; terminal, and separate from {@link #status()}. */
    public boolean isSuperseded() {
        return superseded;
    }

    /** The incident that absorbed this one, when it is known. */
    public Optional<UUID> supersededBy() {
        return Optional.ofNullable(supersededBy);
    }

    /**
     * How many times what the village believes about this deed has actually changed (§6 "Gossip
     * story"). Resolution, supersession, and the rollback of one move it; decay never does, which is
     * the whole distinction — a contribution shrinking by a point a day is not news.
     */
    public long storyRevision() {
        return storyRevision;
    }

    /** The first subject carrying this role, if any. */
    public Optional<IncidentSubject> subjectWithRole(String role) {
        return subjects.stream().filter(s -> s.hasRole(role)).findFirst();
    }

    // --- bounded mutation ---------------------------------------------------

    /**
     * Adds witnesses, keeping the deterministic order the resolver produced and stopping at the cap.
     * Used at creation and again when a killing unions in the witnesses of the assault it upgrades.
     *
     * @return how many were newly added
     */
    public int addWitnesses(Iterable<UUID> candidates) {
        int added = 0;
        for (UUID candidate : candidates) {
            if (witnesses.size() >= ReputationBounds.MAX_WITNESSES) {
                break;
            }
            if (candidate != null && witnesses.add(candidate)) {
                added++;
            }
        }
        return added;
    }

    /**
     * Writes a context entry, silently dropping it once the key cap is reached rather than growing the
     * save without bound. Existing keys may always be overwritten — §20.1 updates an assault's
     * accumulated damage on later hits in the same coalescing window.
     */
    public void putContext(String key, String value) {
        String normalized = normalizeContextKey(key);
        if (normalized.isEmpty() || value == null) {
            return;
        }
        if (!context.containsKey(normalized) && context.size() >= ReputationBounds.MAX_CONTEXT_KEYS) {
            return;
        }
        String bounded = value.length() <= ReputationBounds.MAX_CONTEXT_VALUE_LENGTH
                ? value
                : value.substring(0, ReputationBounds.MAX_CONTEXT_VALUE_LENGTH);
        context.put(normalized, bounded);
    }

    public void putContext(Map<String, String> entries) {
        if (entries != null) {
            entries.forEach(this::putContext);
        }
    }

    private static String normalizeContextKey(String key) {
        if (key == null) {
            return "";
        }
        String trimmed = key.strip().toLowerCase(Locale.ROOT);
        return trimmed.length() <= ReputationBounds.MAX_CONTEXT_KEY_LENGTH
                ? trimmed
                : trimmed.substring(0, ReputationBounds.MAX_CONTEXT_KEY_LENGTH);
    }

    public void touch(long gameTime) {
        this.updatedGameTime = Math.max(this.updatedGameTime, gameTime);
    }

    // --- decay and resolution -----------------------------------------------

    /**
     * Brings the contribution up to date for the current game time under {@code policy}.
     *
     * <p>Idempotent: calling it twice at the same time changes nothing the second time. Monotonic:
     * only forward time movement counts, so a rewound clock is a no-op rather than a refund.
     *
     * @return the change to apply to the cached community score ({@code new - old}); {@code 0} when
     *         nothing moved
     */
    public int reconcile(DecayPolicy policy, long gameTime) {
        if (superseded) {
            // Terminal: a folded record holds no weight, so there is nothing left to age.
            return 0;
        }
        if (gameTime > lastReconciledGameTime) {
            decayElapsedTicks += gameTime - lastReconciledGameTime;
            lastReconciledGameTime = gameTime;
        }
        int expected = policy == null
                ? settledDelta
                : policy.contributionAt(settledDelta, decayElapsedTicks);
        if (expected == currentContribution) {
            return 0;
        }
        int change = expected - currentContribution;
        currentContribution = expected;
        touch(gameTime);
        return change;
    }

    /**
     * Advances the decay clock without ageing anything: the per-record half of a decay freeze
     * (§5 F07).
     *
     * <p>Forward only, and it moves {@code lastReconciledGameTime} alone. {@code decayElapsedTicks},
     * {@code settledDelta}, {@code currentContribution} and {@code status} are left exactly where they
     * were, which is what makes lifting a freeze cost no catch-up decay: the paused interval was never
     * counted, so it can never be repaid.
     */
    public void skipDecayTo(long gameTime) {
        if (gameTime > lastReconciledGameTime) {
            lastReconciledGameTime = gameTime;
        }
    }

    /**
     * Applies a resolution.
     *
     * <p>Rejected — returning empty and mutating nothing — when the transition is not strictly
     * stronger than the current status, which is what makes a repeated quest turn-in or a replayed
     * apology click harmless (§15.2, §33 rule 14). {@link IncidentStatus#DISPROVEN} can never be
     * downgraded.
     *
     * @return the change to apply to the cached community score, or empty when the resolution was a
     *         no-op
     */
    public Optional<Integer> resolve(ResolutionPolicy resolution, DecayPolicy decay,
                                     IncidentStatus newStatus, long gameTime) {
        if (superseded) {
            // A superseded record is not an amends candidate: recomputing settledDelta from baseDelta
            // would hand back weight the successor already carries (§5 F08).
            return Optional.empty();
        }
        if (newStatus == null || !status.canTransitionTo(newStatus)) {
            return Optional.empty();
        }
        int before = currentContribution;
        status = newStatus;
        storyRevision++;
        // Always scaled from the ORIGINAL delta, never from the already-decayed value, so the outcome
        // of "atone" does not depend on how long the player took to get around to it.
        settledDelta = resolution == null
                ? ReputationMath.scaleTowardZero(baseDelta, 1.0f)
                : resolution.settledDelta(baseDelta, newStatus);
        // A resolution can only ever move the contribution toward zero; if decay had already taken it
        // below the resolved value, keep the smaller magnitude.
        int decayed = decay == null ? settledDelta : decay.contributionAt(settledDelta, decayElapsedTicks);
        currentContribution = towardZeroMin(decayed, before);
        touch(gameTime);
        return Optional.of(currentContribution - before);
    }

    /** The value of {@code a} and {@code b} closest to zero, preserving sign semantics. */
    private static int towardZeroMin(int a, int b) {
        return Math.abs(a) <= Math.abs(b) ? a : b;
    }

    /**
     * Marks this record as fully absorbed by another incident: its contribution becomes {@code 0} and
     * it stops decaying, while the record itself stays as chronology.
     *
     * <p>This is how a killing upgrades the assault that preceded it (§20.2) without the two stacking:
     * the assault's weight is folded into the killing so the pair totals the killing's target, and the
     * assault line still appears in the ledger. The state is terminal (§5 F08): the record stops
     * decaying, stops resolving, and never contributes again.
     *
     * @param successor the absorbing incident, or {@code null} when its id is not known yet — see
     *                  {@link #linkSuccessor}
     * @return the change to apply to the cached community score
     */
    public int foldInto(@Nullable UUID successor, long gameTime) {
        int change = absorbIntoBaseline(gameTime);
        superseded = true;
        storyRevision++;
        linkSuccessor(successor);
        return change;
    }

    /**
     * Zeroes this record's weight without the terminal supersede semantics: the §13.5 pruning fold,
     * where the contribution moves into the baseline and the record itself is about to be dropped.
     *
     * @return the change to apply to the cached community score
     */
    public int absorbIntoBaseline(long gameTime) {
        int before = currentContribution;
        settledDelta = 0;
        currentContribution = 0;
        touch(gameTime);
        return -before;
    }

    /**
     * Names the incident that absorbed this one, for the caller that learns the id only after the
     * successor has been committed. The {@code superseded_by} context entry is written too, because
     * readers older than this field still look for it.
     */
    public void linkSuccessor(@Nullable UUID successor) {
        if (successor == null) {
            return;
        }
        supersededBy = successor;
        putContext(BuiltinIncidents.CONTEXT_SUPERSEDED_BY, successor.toString());
    }

    /**
     * Adopts a supersession a pre-flag record carries only as a {@code superseded_by} context entry.
     * The v1 to v2 saved-data migration calls this and nothing else does: the record's stored weight is
     * left exactly as it is, so the migration cannot move a score.
     *
     * <p>A successor that has already been pruned, or a link that is not a UUID, is tolerated — §5 F08
     * asks for links to be validated, not for a pruned successor to be required.
     *
     * @return true when this record was not already terminal
     */
    public boolean adoptLegacySupersession() {
        if (superseded) {
            return false;
        }
        Optional<String> link = context(BuiltinIncidents.CONTEXT_SUPERSEDED_BY);
        if (link.isEmpty()) {
            return false;
        }
        superseded = true;
        storyRevision++;
        try {
            supersededBy = UUID.fromString(link.get());
        } catch (IllegalArgumentException ignored) {
            // a link we cannot parse still tells us the record is terminal; only the identity is lost
        }
        return true;
    }

    /**
     * Restores a contribution captured before {@link #foldInto}, un-doing the fold. This is the
     * rollback seam for the §20.2 kill upgrade <b>only</b>: the assault is folded before the killing
     * is recorded so the pair totals the killing's target, and when the killing then turns out to
     * carry no public weight — refused, duplicate, or retained unwitnessed — the assault's already
     * witnessed penalty must come back rather than being silently refunded.
     */
    public void restoreContribution(int settledDelta, int currentContribution, long gameTime) {
        this.settledDelta = settledDelta;
        this.currentContribution = currentContribution;
        this.superseded = false;
        this.supersededBy = null;
        this.storyRevision++;
        context.remove(BuiltinIncidents.CONTEXT_SUPERSEDED_BY);
        touch(gameTime);
    }

    /**
     * Marks an {@code ACTIVE} record as {@code EXPIRED}: decay ran its contribution to zero and its
     * retention window elapsed, so only the chronology remains. Assigned by reconciliation, not by a
     * resolution, which is why it bypasses {@link IncidentStatus#canTransitionTo} — and why a genuine
     * resolution arriving later may still replace it (§15.2).
     */
    public void markExpired(long gameTime) {
        if (status == IncidentStatus.ACTIVE) {
            status = IncidentStatus.EXPIRED;
            touch(gameTime);
        }
    }

    /**
     * True when this record has run its course and holds no score: decayed or resolved to zero, past
     * its retention window, and neither pinned nor notable. Only these are eligible for the first
     * pruning passes in §13.5.
     */
    public boolean isExpired(IncidentDefinition definition, long gameTime) {
        if (pinned || currentContribution != 0) {
            return false;
        }
        if (definition == null || definition.retentionTicks().isEmpty()) {
            return false;
        }
        return decayElapsedTicks + Math.max(0L, gameTime - lastReconciledGameTime)
                >= definition.retentionTicks().get();
    }

    /** Age in ticks for UI and gossip max-age filters; monotonic, like decay. */
    public long ageTicks(long gameTime) {
        return Math.max(decayElapsedTicks, Math.max(0L, gameTime - createdGameTime));
    }

    // --- persistence --------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("type", type.toString());
        tag.putUUID("player", player);
        tag.put("community", community.save());
        tag.putLong("created", createdGameTime);
        tag.putLong("applied", appliedGameTime);
        tag.putLong("updated", updatedGameTime);
        tag.putString("source", source.toString());
        dedupeKey.ifPresent(k -> tag.putString("dedupe", k));
        tag.putInt("base", baseDelta);
        tag.putInt("settled", settledDelta);
        tag.putInt("current", currentContribution);
        tag.putString("visibility", visibility.jsonName());
        tag.putString("severity", severity.jsonName());
        tag.putString("status", status.jsonName());
        tag.putLong("decayElapsed", decayElapsedTicks);
        tag.putLong("reconciled", lastReconciledGameTime);
        if (pinned) {
            tag.putBoolean("pinned", true);
        }
        if (superseded) {
            tag.putBoolean("superseded", true);
        }
        if (supersededBy != null) {
            tag.putUUID("supersededBy", supersededBy);
        }
        // Format 2, written only when it has moved: a record that never changed its story is
        // byte-identical to what the previous serializer produced.
        if (storyRevision != 0L) {
            tag.putLong("storyRevision", storyRevision);
        }

        if (!subjects.isEmpty()) {
            ListTag subjectList = new ListTag();
            subjects.forEach(s -> subjectList.add(s.save()));
            tag.put("subjects", subjectList);
        }
        if (!witnesses.isEmpty()) {
            ListTag witnessList = new ListTag();
            witnesses.forEach(w -> witnessList.add(net.minecraft.nbt.NbtUtils.createUUID(w)));
            tag.put("witnesses", witnessList);
        }
        if (!context.isEmpty()) {
            CompoundTag contextTag = new CompoundTag();
            context.forEach(contextTag::putString);
            tag.put("context", contextTag);
        }
        return tag;
    }

    /**
     * Reads one record. Returns empty — never throws — when the entry is unusable, because §13.6
     * requires a malformed incident to be skipped without discarding its siblings. Individual soft
     * fields (status, visibility, severity, subjects, witnesses) degrade to safe values instead of
     * failing the whole record; only a missing identity is fatal to it.
     */
    public static Optional<IncidentRecord> load(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("id") || !tag.hasUUID("player")) {
            return Optional.empty();
        }
        ResourceLocation type = ResourceLocation.tryParse(tag.getString("type"));
        ResourceLocation source = ResourceLocation.tryParse(tag.getString("source"));
        Optional<CommunityKey> community = CommunityKey.load(tag.getCompound("community"));
        if (type == null || source == null || community.isEmpty()) {
            return Optional.empty();
        }

        List<IncidentSubject> subjects = new ArrayList<>();
        ListTag subjectList = tag.getList("subjects", Tag.TAG_COMPOUND);
        for (int i = 0; i < subjectList.size() && subjects.size() < ReputationBounds.MAX_SUBJECTS; i++) {
            subjects.add(IncidentSubject.load(subjectList.getCompound(i)));
        }

        IncidentRecord record = new IncidentRecord(
                tag.getUUID("id"), type, tag.getUUID("player"), community.get(),
                tag.getLong("created"),
                // Absent for anything written before backdated delivery existed: the deed was filed
                // when it happened, which is exactly what the two times being equal means.
                tag.contains("applied") ? tag.getLong("applied") : tag.getLong("created"),
                source,
                // Same bounding as create(): a hand-edited or corrupt key must not round-trip wider
                // than a fresh one could ever be, and a blank key must read as "no key".
                boundDedupe(tag.contains("dedupe") ? Optional.of(tag.getString("dedupe")) : Optional.empty()),
                tag.getInt("base"),
                // Unknown enum strings fall back safely (§13.6). Visibility fails *closed* to PRIVATE
                // so a corrupted entry can never accidentally publish something.
                IncidentVisibility.byNameOr(tag.getString("visibility"), IncidentVisibility.PRIVATE),
                IncidentSeverity.byName(tag.getString("severity")).orElse(IncidentSeverity.MINOR),
                subjects);

        record.status = IncidentStatus.byNameOr(tag.getString("status"), IncidentStatus.ACTIVE);
        record.settledDelta = tag.contains("settled") ? tag.getInt("settled") : record.baseDelta;
        record.currentContribution = tag.contains("current") ? tag.getInt("current") : record.settledDelta;
        record.updatedGameTime = tag.getLong("updated");
        record.decayElapsedTicks = Math.max(0L, tag.getLong("decayElapsed"));
        record.lastReconciledGameTime = tag.contains("reconciled")
                ? tag.getLong("reconciled")
                : record.createdGameTime;
        record.pinned = tag.getBoolean("pinned");
        // Absent means "not superseded": a legacy record carrying only the superseded_by context entry
        // keeps its stored weight until a migration decides otherwise.
        record.superseded = tag.getBoolean("superseded");
        record.supersededBy = tag.hasUUID("supersededBy") ? tag.getUUID("supersededBy") : null;
        // Absent reads as zero: a record written before story revisions existed has told one story.
        record.storyRevision = Math.max(0L, tag.getLong("storyRevision"));

        ListTag witnessList = tag.getList("witnesses", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < witnessList.size() && record.witnesses.size() < ReputationBounds.MAX_WITNESSES; i++) {
            try {
                record.witnesses.add(net.minecraft.nbt.NbtUtils.loadUUID(witnessList.get(i)));
            } catch (RuntimeException ignored) {
                // one malformed witness entry never costs us the incident
            }
        }

        CompoundTag contextTag = tag.getCompound("context");
        for (String key : contextTag.getAllKeys()) {
            record.putContext(key, contextTag.getString(key));
        }
        return Optional.of(record);
    }

    @Override
    public String toString() {
        return "IncidentRecord[" + type + " " + id + " player=" + player + " community=" + community
                + " base=" + baseDelta + " current=" + currentContribution + " status=" + status + "]";
    }
}

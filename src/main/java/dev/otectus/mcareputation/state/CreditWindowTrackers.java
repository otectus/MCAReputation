package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The repeat-credit accounting state for one player in one community (spec §10.5).
 *
 * <p>These are <b>counters, not meters</b>. They record how many qualifying operations have been
 * accepted in the live window for a credit group, and for one subject inside it, so
 * {@code CreditResolver} can say what the next one is worth. Nothing here is anybody's reputation,
 * and nothing here is rebuilt from retained incidents or receipts: §10.5 forbids that outright,
 * because both expire on their own schedules and a tracker rebuilt from an expired ledger is a
 * tracker that has just handed back a spent allowance.
 *
 * <h2>Three rules that look like details and are not</h2>
 *
 * <ol>
 *   <li><b>Never evict a live tracker.</b> At capacity this records a conservative overflow decision
 *       — zero new positive credit until a window ends — and says so. An LRU that dropped the oldest
 *       live tracker would turn capacity pressure into a farming strategy: fill the map, lose the
 *       counter, collect full credit again (§10.5, I13).</li>
 *   <li><b>The watermark is monotonic.</b> Every tracker remembers the latest acceptance time it has
 *       ever seen, and a window is measured from that rather than from whatever clock the caller
 *       hands in. {@code /time set} into the past, or an old event delivered late, therefore cannot
 *       reset an allowance (§10.3).</li>
 *   <li><b>The window is frozen while it is live.</b> A policy edited on reload changes the
 *       <em>next</em> window, not the one in progress, so removing and re-adding a group preserves
 *       its live state (§10.5).</li>
 * </ol>
 *
 * <p>This class decides nothing about percentages. It reports ordinals and capacity; the schedule
 * lives in {@code CreditPolicy} and the decision in {@code CreditResolver}, which is what lets the
 * schedule be exhaustively tested with no save file.
 */
public final class CreditWindowTrackers {

    /**
     * The ordinal a counter saturates at. Matched to the bound the frozen evidence payload enforces
     * on a stored ordinal, so a saturated counter can always be written to a deed.
     */
    public static final int MAX_OCCURRENCES = 1_000_000;

    /** Maximum length of a stored subject key. */
    public static final int MAX_SUBJECT_KEY_LENGTH = 64;

    /**
     * Maximum length of a stored group id, matched to the bound the frozen evidence payload applies
     * to the same id so the two paths cannot accept different files.
     */
    public static final int MAX_GROUP_ID_LENGTH =
            dev.otectus.mcareputation.profile.IncidentProfileEvidence.MAX_ID_LENGTH;

    private static final String TAG_GROUP_COUNT = "gn";
    private static final String TAG_GROUPS = "groups";
    private static final String TAG_SUBJECT_COUNT = "sn";
    private static final String TAG_SUBJECTS = "subjects";
    private static final String TAG_OVERFLOW = "of";
    private static final String TAG_OVERFLOW_SINCE = "ofs";

    private static final String TAG_GROUP = "g";
    private static final String TAG_SUBJECT = "s";
    private static final String TAG_WINDOW_START = "ws";
    private static final String TAG_WINDOW_TICKS = "wt";
    private static final String TAG_COUNT = "n";
    private static final String TAG_WATERMARK = "wm";

    /** Insertion-ordered so the saved list is stable between writes and a diff stays readable. */
    private final Map<ResourceLocation, Tracker> groups = new LinkedHashMap<>();
    private final Map<String, Tracker> subjects = new LinkedHashMap<>();

    private boolean overflow;
    private long overflowSinceGameTime;

    /**
     * One counter: when its window opened, how long that window runs, how many qualifying operations
     * have been accepted in it, and the highest acceptance time ever seen.
     */
    private static final class Tracker {

        private long windowStart;
        private long windowTicks;
        private int count;
        private long watermark;

        private Tracker(long windowStart, long windowTicks, int count, long watermark) {
            this.windowStart = windowStart;
            this.windowTicks = Math.max(1L, windowTicks);
            this.count = Math.max(0, Math.min(count, MAX_OCCURRENCES));
            this.watermark = watermark;
        }

        /** The time this tracker measures against: never earlier than what it has already seen. */
        private long effective(long acceptanceTime) {
            return Math.max(acceptanceTime, watermark);
        }

        private boolean expired(long acceptanceTime) {
            return effective(acceptanceTime) - windowStart >= windowTicks;
        }

        /** The zero-based ordinal the next accepted operation would take. */
        private int ordinal(long acceptanceTime) {
            return expired(acceptanceTime) ? 0 : count;
        }

        private void accept(long acceptanceTime, long nextWindowTicks) {
            long effective = effective(acceptanceTime);
            if (expired(acceptanceTime)) {
                // A fresh window takes the current policy's duration; the one just finished kept the
                // duration it started with, which is what "frozen while live" means.
                windowStart = effective;
                windowTicks = Math.max(1L, nextWindowTicks);
                count = 1;
            } else if (count < MAX_OCCURRENCES) {
                count++;
            }
            watermark = effective;
        }

        private long windowEnd() {
            return windowStart + windowTicks;
        }
    }

    /**
     * What one operation needs to know before it is accepted: where it sits in its group's window,
     * where it sits in its subject's, and whether capacity forced the conservative decision.
     *
     * @param subjectOrdinal empty when the operation named no usable subject, which §10.2 sends to
     *                       the conservative shared bucket rather than to a fresh allowance
     */
    public record CreditWindow(int groupOrdinal, OptionalInt subjectOrdinal, boolean capacityOverflow) {

        /** The answer for an operation with no applicable policy: occurrence one, nothing tracked. */
        public static final CreditWindow FIRST =
                new CreditWindow(0, OptionalInt.empty(), false);
    }

    public boolean isEmpty() {
        return groups.isEmpty() && subjects.isEmpty() && !overflow;
    }

    public int groupCount() {
        return groups.size();
    }

    public int subjectCount() {
        return subjects.size();
    }

    /** Whether §10.5's conservative overflow decision is currently in force. */
    public boolean isOverflowing() {
        return overflow;
    }

    /** When the overflow decision was first recorded, for the capacity diagnostic. */
    public long overflowSinceGameTime() {
        return overflowSinceGameTime;
    }

    /** The groups currently tracked, in insertion order. Diagnostics only. */
    public List<ResourceLocation> trackedGroups() {
        return List.copyOf(groups.keySet());
    }

    /** The end of a group's live window, or empty when it tracks none. Diagnostics only. */
    public Optional<Long> windowEnd(ResourceLocation group) {
        Tracker tracker = group == null ? null : groups.get(group);
        return tracker == null ? Optional.empty() : Optional.of(tracker.windowEnd());
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * The window an operation would land in, <b>without creating or moving anything</b> (I04).
     *
     * <p>Expired trackers are ignored rather than removed, so this answers exactly what
     * {@link #consume} would: the two must not disagree about capacity, or an operation refused for
     * overflow would then be accepted at full credit by the commit that follows it.
     */
    public CreditWindow peek(ResourceLocation group, Optional<String> subjectKey,
                             boolean subjectRequired, long acceptanceTime) {
        if (group == null) {
            return CreditWindow.FIRST;
        }
        Tracker groupTracker = groups.get(group);
        boolean groupOverflow = groupTracker == null && liveGroupCount(acceptanceTime) >= maxGroups();
        int groupOrdinal = groupTracker == null ? 0 : groupTracker.ordinal(acceptanceTime);

        OptionalInt subjectOrdinal = OptionalInt.empty();
        boolean subjectOverflow = false;
        if (subjectRequired) {
            Optional<String> key = boundKey(subjectKey);
            if (key.isPresent()) {
                Tracker subjectTracker = subjects.get(subjectId(group, key.get()));
                subjectOverflow = subjectTracker == null
                        && liveSubjectCount(acceptanceTime) >= maxSubjects();
                subjectOrdinal = OptionalInt.of(
                        subjectTracker == null ? 0 : subjectTracker.ordinal(acceptanceTime));
            }
        }
        return new CreditWindow(groupOrdinal, subjectOrdinal,
                overflow || groupOverflow || subjectOverflow);
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Consumes one allowance for an accepted operation and returns the window it was accepted in.
     *
     * <p>Called from inside the canonical mutation and nowhere else (§11.1 step 7). A counter moved
     * before the admission decision has been spent by a refused delivery; a counter moved after
     * publication can be observed unspent by a synchronous listener replaying the same operation.
     * Both turn a retry into free credit.
     *
     * @return the ordinals the operation was accepted at, which are what the frozen evidence records
     */
    public CreditWindow consume(ResourceLocation group, long windowTicks, Optional<String> subjectKey,
                                boolean subjectRequired, long acceptanceTime) {
        if (group == null) {
            return CreditWindow.FIRST;
        }
        // Expired trackers go first, and only then: §10.5 allows removing a tracker once it no longer
        // restricts the next operation, and forbids removing one that still does.
        dropExpired(acceptanceTime);

        Tracker groupTracker = groups.get(group);
        boolean groupOverflow = false;
        int groupOrdinal;
        if (groupTracker == null) {
            if (groups.size() >= maxGroups()) {
                groupOverflow = true;
                groupOrdinal = 0;
                recordOverflow(acceptanceTime, "group", group.toString());
            } else {
                groupTracker = new Tracker(acceptanceTime, windowTicks, 0, acceptanceTime);
                groups.put(group, groupTracker);
                groupOrdinal = 0;
                groupTracker.accept(acceptanceTime, windowTicks);
            }
        } else {
            groupOrdinal = groupTracker.ordinal(acceptanceTime);
            groupTracker.accept(acceptanceTime, windowTicks);
        }

        OptionalInt subjectOrdinal = OptionalInt.empty();
        boolean subjectOverflow = false;
        if (subjectRequired) {
            Optional<String> key = boundKey(subjectKey);
            if (key.isPresent()) {
                String id = subjectId(group, key.get());
                Tracker subjectTracker = subjects.get(id);
                if (subjectTracker == null) {
                    if (subjects.size() >= maxSubjects()) {
                        subjectOverflow = true;
                        subjectOrdinal = OptionalInt.of(0);
                        recordOverflow(acceptanceTime, "subject", id);
                    } else {
                        subjectTracker = new Tracker(acceptanceTime, windowTicks, 0, acceptanceTime);
                        subjects.put(id, subjectTracker);
                        subjectOrdinal = OptionalInt.of(0);
                        subjectTracker.accept(acceptanceTime, windowTicks);
                    }
                } else {
                    subjectOrdinal = OptionalInt.of(subjectTracker.ordinal(acceptanceTime));
                    subjectTracker.accept(acceptanceTime, windowTicks);
                }
            }
        }
        return new CreditWindow(groupOrdinal, subjectOrdinal,
                overflow || groupOverflow || subjectOverflow);
    }

    /**
     * Removes every tracker whose window has ended at this evaluation time, and lifts the overflow
     * decision once there is room again.
     *
     * <p>The only removal path there is. It cannot free a slot that still restricts an operation,
     * which is the difference between safe cleanup and the eviction exploit §10.5 names.
     *
     * @return how many trackers were removed
     */
    public int dropExpired(long acceptanceTime) {
        int removed = 0;
        removed += dropExpired(groups.values().iterator(), acceptanceTime);
        removed += dropExpired(subjects.values().iterator(), acceptanceTime);
        if (overflow && groups.size() < maxGroups() && subjects.size() < maxSubjects()) {
            overflow = false;
            overflowSinceGameTime = 0L;
        }
        return removed;
    }

    private static int dropExpired(java.util.Iterator<Tracker> iterator, long acceptanceTime) {
        int removed = 0;
        while (iterator.hasNext()) {
            if (iterator.next().expired(acceptanceTime)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private void recordOverflow(long acceptanceTime, String kind, String id) {
        if (!overflow) {
            overflow = true;
            overflowSinceGameTime = acceptanceTime;
            McaReputation.LOGGER.warn("[MCA: Reputation] repeat-credit {} tracker capacity is exhausted "
                            + "while accounting for {}; new positive credit is zero until a window ends. "
                            + "No live counter was evicted (spec 10.5).", kind, id);
        }
    }

    private int liveGroupCount(long acceptanceTime) {
        return liveCount(groups.values(), acceptanceTime);
    }

    private int liveSubjectCount(long acceptanceTime) {
        return liveCount(subjects.values(), acceptanceTime);
    }

    private static int liveCount(Iterable<Tracker> trackers, long acceptanceTime) {
        int live = 0;
        for (Tracker tracker : trackers) {
            if (!tracker.expired(acceptanceTime)) {
                live++;
            }
        }
        return live;
    }

    private static int maxGroups() {
        return ReputationBounds.MAX_CREDIT_GROUP_TRACKERS;
    }

    private static int maxSubjects() {
        return ReputationBounds.MAX_CREDIT_SUBJECT_TRACKERS;
    }

    private static String subjectId(ResourceLocation group, String subjectKey) {
        return group + "|" + subjectKey;
    }

    /** Bounded and blank-rejecting: a key wider than the loader accepts must never be written. */
    private static Optional<String> boundKey(Optional<String> raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return raw.map(String::strip)
                .filter(key -> !key.isEmpty())
                .map(key -> key.length() <= MAX_SUBJECT_KEY_LENGTH
                        ? key
                        : key.substring(0, MAX_SUBJECT_KEY_LENGTH));
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** Empty when nothing is tracked, so a ledger that never saw a credited deed writes no tag. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (isEmpty()) {
            return tag;
        }
        tag.putInt(TAG_GROUP_COUNT, groups.size());
        if (!groups.isEmpty()) {
            ListTag list = new ListTag();
            groups.forEach((group, tracker) -> {
                CompoundTag entry = saveTracker(tracker);
                entry.putString(TAG_GROUP, group.toString());
                list.add(entry);
            });
            tag.put(TAG_GROUPS, list);
        }
        tag.putInt(TAG_SUBJECT_COUNT, subjects.size());
        if (!subjects.isEmpty()) {
            ListTag list = new ListTag();
            subjects.forEach((id, tracker) -> {
                CompoundTag entry = saveTracker(tracker);
                entry.putString(TAG_SUBJECT, id);
                list.add(entry);
            });
            tag.put(TAG_SUBJECTS, list);
        }
        if (overflow) {
            tag.putBoolean(TAG_OVERFLOW, true);
            tag.putLong(TAG_OVERFLOW_SINCE, overflowSinceGameTime);
        }
        return tag;
    }

    private static CompoundTag saveTracker(Tracker tracker) {
        CompoundTag tag = new CompoundTag();
        tag.putLong(TAG_WINDOW_START, tracker.windowStart);
        tag.putLong(TAG_WINDOW_TICKS, tracker.windowTicks);
        tag.putInt(TAG_COUNT, tracker.count);
        tag.putLong(TAG_WATERMARK, tracker.watermark);
        return tag;
    }

    /**
     * Reads the trackers, bounding every declared length before allocating and dropping nothing that
     * could restore credit.
     *
     * <p>A file carrying more trackers than the bound allows is <em>truncated with the overflow flag
     * set</em>, never silently trimmed: dropping anti-farm state and then granting full credit is the
     * exploit §10.5 is written against, and a load path is no more entitled to do it than a cap sweep.
     */
    public static CreditWindowTrackers load(CompoundTag tag) {
        CreditWindowTrackers trackers = new CreditWindowTrackers();
        if (tag == null || tag.isEmpty()) {
            return trackers;
        }
        trackers.overflow = tag.getBoolean(TAG_OVERFLOW);
        trackers.overflowSinceGameTime = trackers.overflow ? tag.getLong(TAG_OVERFLOW_SINCE) : 0L;

        int declaredGroups = tag.getInt(TAG_GROUP_COUNT);
        ListTag groupList = tag.getList(TAG_GROUPS, Tag.TAG_COMPOUND);
        if (declaredGroups < 0 || declaredGroups != groupList.size()) {
            McaReputation.LOGGER.warn("[MCA: Reputation] repeat-credit group trackers declared {} entries "
                            + "and carry {}; keeping the conservative overflow decision",
                    declaredGroups, groupList.size());
            trackers.overflow = true;
        }
        for (int i = 0; i < groupList.size(); i++) {
            CompoundTag entry = groupList.getCompound(i);
            ResourceLocation group = boundedId(entry.getString(TAG_GROUP));
            if (group == null) {
                continue;
            }
            if (trackers.groups.size() >= maxGroups()) {
                trackers.overflow = true;
                continue;
            }
            trackers.groups.put(group, loadTracker(entry));
        }

        int declaredSubjects = tag.getInt(TAG_SUBJECT_COUNT);
        ListTag subjectList = tag.getList(TAG_SUBJECTS, Tag.TAG_COMPOUND);
        if (declaredSubjects < 0 || declaredSubjects != subjectList.size()) {
            McaReputation.LOGGER.warn("[MCA: Reputation] repeat-credit subject trackers declared {} entries "
                            + "and carry {}; keeping the conservative overflow decision",
                    declaredSubjects, subjectList.size());
            trackers.overflow = true;
        }
        for (int i = 0; i < subjectList.size(); i++) {
            CompoundTag entry = subjectList.getCompound(i);
            String id = entry.getString(TAG_SUBJECT);
            if (id.isEmpty() || id.length() > MAX_SUBJECT_KEY_LENGTH + MAX_GROUP_ID_LENGTH + 1) {
                continue;
            }
            if (trackers.subjects.size() >= maxSubjects()) {
                trackers.overflow = true;
                continue;
            }
            trackers.subjects.put(id, loadTracker(entry));
        }
        if (trackers.overflow && trackers.overflowSinceGameTime == 0L) {
            // An overflow recorded by the loader rather than by an operation still needs a time, or
            // the capacity diagnostic cannot say how long the decision has been in force.
            trackers.overflowSinceGameTime = trackers.groups.values().stream()
                    .mapToLong(t -> t.watermark).max().orElse(0L);
        }
        return trackers;
    }

    private static Tracker loadTracker(CompoundTag tag) {
        return new Tracker(tag.getLong(TAG_WINDOW_START), tag.getLong(TAG_WINDOW_TICKS),
                tag.getInt(TAG_COUNT), tag.getLong(TAG_WATERMARK));
    }

    private static ResourceLocation boundedId(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_GROUP_ID_LENGTH) {
            return null;
        }
        return ResourceLocation.tryParse(raw);
    }

    /** Diagnostic summary for {@code /mcareputation debug credit}. */
    public Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        groups.forEach((group, tracker) -> out.put(group.toString(),
                "count=" + tracker.count + " window=" + tracker.windowStart + ".." + tracker.windowEnd()
                        + " watermark=" + tracker.watermark));
        subjects.forEach((id, tracker) -> out.put("subject:" + id,
                "count=" + tracker.count + " window=" + tracker.windowStart + ".." + tracker.windowEnd()
                        + " watermark=" + tracker.watermark));
        if (overflow) {
            out.put("overflow", "since " + overflowSinceGameTime
                    + " (conservative zero new positive credit)");
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Replaces this set's contents with another's, for the load path: the record that owns the
     * trackers holds one final reference for its whole life, so loading has to fill that instance
     * rather than swap it. Additive nowhere else — every other write goes through {@link #consume}.
     */
    public void absorb(CreditWindowTrackers loaded) {
        groups.clear();
        subjects.clear();
        overflow = false;
        overflowSinceGameTime = 0L;
        if (loaded == null) {
            return;
        }
        groups.putAll(loaded.groups);
        subjects.putAll(loaded.subjects);
        overflow = loaded.overflow;
        overflowSinceGameTime = loaded.overflowSinceGameTime;
    }

    /** A deep copy, for a caller that needs to compare before and after. */
    public CreditWindowTrackers copy() {
        return load(save());
    }

    private CreditWindowTrackers() {
    }

    /** An empty tracker set. */
    public static CreditWindowTrackers empty() {
        return new CreditWindowTrackers();
    }
}

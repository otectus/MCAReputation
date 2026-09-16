package dev.otectus.mcareputation.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The bounded policy epochs profile aging is measured against (spec §12.2).
 *
 * <h2>Why a lazy skip is not enough</h2>
 *
 * <p>The scalar channel pauses by advancing {@code lastReconciledGameTime} and nothing else, which
 * works because the paused interval is skipped <em>at the moment it is observed</em>. That guarantee
 * is only as good as the observation: if profiles are switched off, nobody reads the record for the
 * whole disabled interval, and profiles are switched back on before the next read, the record's own
 * clock cannot tell the difference between "nobody looked" and "the interval counted". Advancing it
 * blindly pays the disabled interval out as a catch-up burst — exactly what §12.2 forbids, and the
 * reason it offers "capture policy transitions for existing loaded records, or retain bounded policy
 * epochs sufficient to calculate the elapsed active interval" as the fix.
 *
 * <p>This is that second option. The reconciliation gate reports the profile-frozen state it observed
 * at every entry, and a config reload reports the transition as it happens; the log turns those
 * observations into closed frozen intervals, and {@link #frozenTicksBetween} subtracts their overlap
 * from any interval a record is about to be aged by. One record that was never read still loses only
 * the ticks the log says were frozen, and keeps the ticks that were genuinely active.
 *
 * <h2>Deliberate limitations, both documented rather than guessed</h2>
 *
 * <ul>
 *   <li><b>Not persisted.</b> Nothing here is written to disk, because game time does not advance
 *       while the world is not running, so the only unobserved intervals are ones this JVM could
 *       have observed. What a restart does lose is the shape of a freeze that spanned it: if the
 *       first observation after a load says frozen, everything before that observation is treated as
 *       frozen too ({@link #UNKNOWN_START}), which cannot pay catch-up. If the first observation says
 *       active, earlier unobserved time is treated as active, which is the pre-profile behaviour and
 *       the honest reading of a world that was never paused.</li>
 *   <li><b>Bounded to {@link #MAX_INTERVALS}.</b> At capacity the two oldest intervals are merged
 *       into the span that covers both, so the log can only ever over-report freezing. Over-reporting
 *       retains evidence slightly longer (§12.3); under-reporting would hand back skipped aging, which
 *       is the failure this class exists to prevent (I13's "conservative capacity result").</li>
 * </ul>
 *
 * <p>Monotonic: an observation at a rewound clock is clamped to the latest watermark, so {@code /time
 * set} into the past cannot open a negative interval or reset a freeze.
 */
public final class ProfileFreezeLog {

    /** How many closed frozen intervals are retained before the oldest two are merged. */
    public static final int MAX_INTERVALS = 32;

    /**
     * The start of a freeze that was already in progress the first time anything observed it — a
     * server that loaded with profiles switched off. Every game time is above it, so the whole
     * unobserved span before that first observation reads as frozen.
     */
    public static final long UNKNOWN_START = Long.MIN_VALUE;

    /** One closed frozen interval, half-open: {@code [start, end)}. */
    private record Interval(long start, long end) {
    }

    private final Deque<Interval> intervals = new ArrayDeque<>();

    private boolean observed;
    private boolean frozen;
    private long watermark;
    private long openStart;

    /**
     * Records the profile-frozen state as of {@code gameTime}.
     *
     * <p>Called from the reconciliation gate on every entry and from any policy-transition hook that
     * knows a config reload happened. Idempotent for an unchanged state: only a transition writes
     * anything, so calling it on every query costs one comparison.
     */
    public void observe(boolean frozenNow, long gameTime) {
        long time = Math.max(gameTime, watermark);
        if (!observed) {
            observed = true;
            frozen = frozenNow;
            watermark = time;
            // A freeze already in progress has no observed start; see UNKNOWN_START.
            openStart = frozenNow ? UNKNOWN_START : 0L;
            return;
        }
        if (frozenNow != frozen) {
            if (frozenNow) {
                openStart = time;
            } else {
                close(openStart, time);
            }
            frozen = frozenNow;
        }
        watermark = time;
    }

    /** Whether the last observation saw profile aging frozen. */
    public boolean frozen() {
        return frozen;
    }

    /** Whether anything has been observed yet; an unobserved log subtracts nothing. */
    public boolean observed() {
        return observed;
    }

    /** How many closed intervals are retained. Bounded by {@link #MAX_INTERVALS}. */
    public int intervalCount() {
        return intervals.size();
    }

    /**
     * How many of the ticks in {@code [from, to)} were frozen, and therefore must not be charged as
     * profile aging.
     *
     * <p>Includes the freeze currently in progress, up to {@code to}: the state is frozen at the
     * evaluation time by definition, so an interval ending there is still frozen at its end.
     */
    public long frozenTicksBetween(long from, long to) {
        if (to <= from) {
            return 0L;
        }
        long total = 0L;
        for (Interval interval : intervals) {
            total += overlap(interval.start(), interval.end(), from, to);
        }
        if (frozen) {
            total += overlap(openStart, to, from, to);
        }
        return Math.max(0L, Math.min(total, to - from));
    }

    /** Forgets every observation. Used when a world unloads and by tests between cases. */
    public void clear() {
        intervals.clear();
        observed = false;
        frozen = false;
        watermark = 0L;
        openStart = 0L;
    }

    private void close(long start, long end) {
        if (end <= start && start != UNKNOWN_START) {
            return;
        }
        Interval previous = intervals.peekLast();
        if (previous != null && previous.end() >= start) {
            // Touching or overlapping: one interval says the same thing with one slot.
            intervals.pollLast();
            intervals.addLast(new Interval(previous.start(), Math.max(previous.end(), end)));
            return;
        }
        intervals.addLast(new Interval(start, end));
        while (intervals.size() > MAX_INTERVALS) {
            mergeTwoOldest();
        }
    }

    /**
     * Collapses the two oldest intervals into the span covering both. The gap between them was active
     * time that is now reported as frozen, which can only retain evidence longer; dropping an
     * interval instead would report frozen time as active and hand back the catch-up this class
     * exists to refuse.
     */
    private void mergeTwoOldest() {
        List<Interval> remaining = new ArrayList<>(intervals);
        intervals.clear();
        Interval first = remaining.get(0);
        Interval second = remaining.get(1);
        intervals.addLast(new Interval(first.start(), Math.max(first.end(), second.end())));
        for (int i = 2; i < remaining.size(); i++) {
            intervals.addLast(remaining.get(i));
        }
    }

    private static long overlap(long start, long end, long from, long to) {
        long low = Math.max(start, from);
        long high = Math.min(end, to);
        return high > low ? high - low : 0L;
    }
}

package dev.otectus.mcareputation.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side pacing for snapshot requests (spec §27.2): at most one answer per player per cooldown,
 * and the newest request inside the window is <em>deferred</em>, never dropped.
 *
 * <h2>Why deferred</h2>
 *
 * <p>This used to discard anything inside the window. The client paces itself to the same number of
 * ticks ({@code RequestThrottle}), but it measures them on its own level clock while this measures the
 * server's, and the two are not the same clock: network jitter delivers two requests closer together
 * than they were sent, and a server below 20 TPS runs fewer ticks than the client counted. Either way
 * an honestly paced request landed inside the window, got no reply, and the standing screen kept
 * showing the previous village's tier and progress with nothing to say it was stale. A dedicated
 * server with any lag reproduced it; singleplayer, which shares one clock and has no jitter, did not.
 *
 * <p>Deferring keeps the cost bound the drop was there for — one snapshot per player per cooldown,
 * whatever the client sends — and makes the limiter lossless: the newest wish is answered as soon as
 * the window allows, exactly the discipline the client already applies on its side. It is the same
 * end-of-tick flush {@link ReputationFeedback} uses for merged feedback, not a poll: nothing is asked
 * again, a request that arrived is simply answered late instead of not at all.
 *
 * <p>Pure, with the clock supplied by the caller, so {@code RequestPacingTest} covers every branch
 * with no server running.
 *
 * @param <K> the requester's identity
 * @param <R> the request
 */
final class RequestPacing<K, R> {

    private final int cooldownTicks;
    private final Map<K, Long> lastAnswered = new HashMap<>();
    private final Map<K, R> deferred = new LinkedHashMap<>();

    RequestPacing(int cooldownTicks) {
        this.cooldownTicks = cooldownTicks;
    }

    /**
     * Offers a request. Returns true when it may be answered now, and stamps the answer; otherwise
     * parks it for {@link #due}, replacing anything already parked for this requester, because only
     * the newest wish is still being asked.
     */
    boolean offer(K requester, R request, long now) {
        if (mayAnswer(requester, now)) {
            deferred.remove(requester);
            lastAnswered.put(requester, now);
            return true;
        }
        deferred.put(requester, request);
        return false;
    }

    /**
     * Removes and returns every parked request whose cooldown has now passed, stamping each as
     * answered. Requests still inside their window stay parked.
     */
    List<Map.Entry<K, R>> due(long now) {
        if (deferred.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<K, R>> out = new ArrayList<>();
        Iterator<Map.Entry<K, R>> iterator = deferred.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, R> entry = iterator.next();
            if (mayAnswer(entry.getKey(), now)) {
                iterator.remove();
                lastAnswered.put(entry.getKey(), now);
                out.add(Map.entry(entry.getKey(), entry.getValue()));
            }
        }
        return out;
    }

    /** Whether anything is parked; the tick flush returns on this before reading the clock. */
    boolean hasDeferred() {
        return !deferred.isEmpty();
    }

    /** Drops one requester's stamp and parked request, on disconnect. */
    void forget(K requester) {
        lastAnswered.remove(requester);
        deferred.remove(requester);
    }

    /** Drops everything, on server stop. */
    void clear() {
        lastAnswered.clear();
        deferred.clear();
    }

    private boolean mayAnswer(K requester, long now) {
        Long last = lastAnswered.get(requester);
        // A clock that went backwards (a second world in the same JVM, before clear()) answers now
        // rather than waiting out a window measured on a clock that no longer exists.
        return last == null || now - last >= cooldownTicks || now < last;
    }
}

package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.community.CommunityKey;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;

/**
 * Client-side pacing for snapshot requests (spec §27.2, §28.2).
 *
 * <p>The server answers at most one request per player per cooldown and defers the newest one inside
 * that window ({@code network/RequestPacing}); it used to drop it, which left the screen "asking
 * around…" forever or showing the previous village. This class is the client's matching discipline:
 * at most one send per cooldown, the newest wish parked until it may go out, and an answer that never
 * comes times out into the retryable empty state instead of an eternal spinner. The two cooldowns are
 * measured on different clocks — this one on the client level's, the server's on its own — which is
 * exactly why the server side must not be lossy.
 *
 * <p>Pure on purpose: the caller supplies the clock, so {@code RequestThrottleTest} exercises every
 * branch with no client running.
 */
final class RequestThrottle {

    /** Mirrors the server's {@code REQUEST_COOLDOWN_TICKS}; sending faster only queues behind itself. */
    static final int COOLDOWN_TICKS = 10;

    /** After this long with no reply the request is considered lost and the UI may say so. */
    static final int TIMEOUT_TICKS = 60;

    /**
     * What the caller wanted to ask. Latest wins while parked.
     *
     * <p>The page is part of the request, not a client-side view of one reply: a page turn asks the
     * server for a different slice of the community list, so two requests that differ only by page are
     * two different questions and the newer one must not be mistaken for a repeat of the older.
     */
    record Request(int contextEntityId, Optional<CommunityKey> community, int page) {

        Request(int contextEntityId, Optional<CommunityKey> community) {
            this(contextEntityId, community, 0);
        }
    }

    private final int cooldownTicks;
    private final int timeoutTicks;

    private long lastSentTick = -Long.MAX_VALUE / 2;
    private long awaitingSinceTick;
    private boolean awaiting;
    @Nullable
    private Request parked;

    RequestThrottle() {
        this(COOLDOWN_TICKS, TIMEOUT_TICKS);
    }

    RequestThrottle(int cooldownTicks, int timeoutTicks) {
        this.cooldownTicks = cooldownTicks;
        this.timeoutTicks = timeoutTicks;
    }

    /**
     * Offers a request. Returns it when it may be sent right now (stamping the send); otherwise parks
     * it — replacing anything already parked, because only the newest wish matters — for {@link #due}.
     */
    Optional<Request> offer(Request request, long now) {
        if (now - lastSentTick >= cooldownTicks) {
            markSent(now);
            return Optional.of(request);
        }
        parked = request;
        return Optional.empty();
    }

    /** The parked request, if the cooldown has passed. Call once per client tick and send the result. */
    Optional<Request> due(long now) {
        if (parked == null || now - lastSentTick < cooldownTicks) {
            return Optional.empty();
        }
        Request request = parked;
        parked = null;
        markSent(now);
        return Optional.of(request);
    }

    private void markSent(long now) {
        lastSentTick = now;
        awaitingSinceTick = now;
        awaiting = true;
        parked = null;
    }

    /** A reply landed; whatever was outstanding is answered. */
    void onReply() {
        awaiting = false;
    }

    /** True while a request is outstanding and not yet timed out. */
    boolean awaiting(long now) {
        if (awaiting && now - awaitingSinceTick > timeoutTicks) {
            awaiting = false;
        }
        return awaiting;
    }

    /** Full reset on disconnect: the next world starts with a clean slate. */
    void reset() {
        lastSentTick = -Long.MAX_VALUE / 2;
        awaiting = false;
        parked = null;
    }
}

package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.community.CommunityKey;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Client-side pacing for snapshot requests (spec §27.2, §28.2).
 *
 * <p>The server silently drops requests that arrive within its per-player cooldown — deliberately, so
 * a spammed button costs one map lookup. The client must therefore never fire-and-forget inside that
 * window, or the request simply vanishes: the screen that asked stays "asking around…" forever and a
 * fast community cycle leaves the header showing one village while the footer counts another. This
 * class is the client's matching discipline: at most one send per cooldown, the newest wish parked
 * until it may go out, and an answer that never comes times out into the retryable empty state
 * instead of an eternal spinner.
 *
 * <p>Pure on purpose: the caller supplies the clock, so {@code RequestThrottleTest} exercises every
 * branch with no client running.
 */
final class RequestThrottle {

    /** Mirrors the server's {@code REQUEST_COOLDOWN_TICKS}; sending faster is sending into a void. */
    static final int COOLDOWN_TICKS = 10;

    /** After this long with no reply the request is considered lost and the UI may say so. */
    static final int TIMEOUT_TICKS = 60;

    /** What the caller wanted to ask. Latest wins while parked. */
    record Request(int contextEntityId, Optional<CommunityKey> community) {
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

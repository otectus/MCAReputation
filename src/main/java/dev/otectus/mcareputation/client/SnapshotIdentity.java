package dev.otectus.mcareputation.client;

/**
 * Which snapshot reply is still the answer to a question on screen (spec §18.3).
 *
 * <h2>The failure this exists to prevent</h2>
 *
 * <p>Requests are paced, replies are not ordered, and the player can change what they are looking at
 * between the two. 0.5.0 handled that for the villager's opinion with a "stale" flag on the screen,
 * which works because the opinion is redrawn from scratch every frame. A profile pane cannot be
 * rescued the same way: it carries recognition, traits and an observer's reading of them, and a reply
 * that arrives after the player has selected another village would quietly replace all of it with a
 * different village's answer. §18.3 therefore requires a request identity, and this is it.
 *
 * <h2>The rule</h2>
 *
 * <p>Every outgoing request is stamped with a fresh id. A reply is accepted only when it carries the
 * id of the newest request sent, or {@link #UNSOLICITED} — the server pushing a snapshot nobody asked
 * for, which is a fresh answer by definition and must not be discarded as stale. Anything else
 * answers a question that is no longer on screen and is dropped.
 *
 * <p>Pure and separate from {@link RequestThrottle} because the two answer different questions —
 * "may I send yet" and "is this reply still wanted" — and because a stamp that wrapped, skipped zero
 * incorrectly or compared the wrong way round is precisely the kind of defect that is invisible in
 * play and trivial in a unit test.
 */
final class SnapshotIdentity {

    /** The stamp of a server-pushed snapshot: no request, so nothing to be stale against. */
    static final int UNSOLICITED = 0;

    /** Ids stay well inside a varint and never reach zero again by accident. */
    static final int MAX_ID = 1 << 24;

    private int next = 1;
    private int outstanding = UNSOLICITED;

    /** Stamps the next request and remembers it as the only reply now worth applying. */
    int stamp() {
        outstanding = next;
        next = next >= MAX_ID ? 1 : next + 1;
        return outstanding;
    }

    /** Whether a reply carrying this stamp still answers what the screen is asking. */
    boolean accepts(int replyId) {
        return replyId == UNSOLICITED || replyId == outstanding;
    }

    /** The stamp of the request currently outstanding; {@link #UNSOLICITED} before the first one. */
    int outstanding() {
        return outstanding;
    }

    /** Full reset on disconnect, so a reply from the previous world cannot be accepted in the next. */
    void reset() {
        next = 1;
        outstanding = UNSOLICITED;
    }
}

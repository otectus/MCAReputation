package dev.otectus.mcareputation.network;

/**
 * How the community list is cut into pages (spec §27.3, §28.2).
 *
 * <p>A class of its own, beside {@link SnapshotSelection}, and for the same two reasons: it is pure
 * arithmetic rather than plumbing, and both the server and the screen have to agree about it without
 * either of them being reachable from the other's tests. {@link ReputationNetwork}'s static
 * initialiser builds a Forge channel, and the client package is unreachable from common code, so the
 * shared answer lives here.
 *
 * <h2>What paging must not do</h2>
 *
 * <p>Only the summary list is paged. The selected community's detail is sent with every reply
 * whichever page was asked for, so turning a page can never lose the selection — which would be a
 * fourth way to reproduce the read-key/write-key drift DIAGNOSIS.md §2 hop 7b describes, after the
 * three that class already guards.
 */
public final class SnapshotPaging {

    private SnapshotPaging() {
    }

    /** How many pages a list of this size needs at this per-page cap; never fewer than one. */
    public static int pageCount(int total, int perPage) {
        if (total <= 0 || perPage <= 0) {
            return 1;
        }
        return Math.max(1, (total + perPage - 1) / perPage); // ceil; Math.ceilDiv is Java 18+
    }

    /** A requested page clamped into {@code [0, pageCount - 1]}. Never throws, never wraps. */
    public static int clampPage(int page, int pageCount) {
        if (page <= 0) {
            return 0;
        }
        return Math.min(page, Math.max(0, pageCount - 1));
    }

    /** The index the given page starts at, already clamped against the list length. */
    public static int pageStart(int page, int perPage, int total) {
        if (perPage <= 0 || total <= 0) {
            return 0;
        }
        return Math.min(Math.max(0, clampPage(page, pageCount(total, perPage)) * perPage),
                Math.max(0, total));
    }

    /** The index one past the end of the given page. */
    public static int pageEnd(int page, int perPage, int total) {
        return Math.min(Math.max(0, total), pageStart(page, perPage, total) + Math.max(0, perPage));
    }

    /** Whether page controls are worth drawing at all. */
    public static boolean hasMultiplePages(int pageCount) {
        return pageCount > 1;
    }

    /** The next page in a direction, wrapping like the community selector does. */
    public static int nextPage(int page, int pageCount, int direction) {
        int pages = Math.max(1, pageCount);
        return Math.floorMod(clampPage(page, pages) + direction, pages);
    }
}

package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.profile.LegacyEnrichmentManifest;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * How far the §19.2 historical enrichment has got, persisted (spec §19.3).
 *
 * <h2>Why a cursor exists at all</h2>
 *
 * <p>Enrichment cannot run to completion at load time, and pretending otherwise is the failure mode
 * §19.3 is written against. Two independent reasons: the profile content it needs comes from the
 * datapack reload, which may not have published when the saved data is first read; and a save with
 * many players is a bounded-work problem that must not be done in one blocking pass. So the structural
 * migration lands first, the cursor records where the enrichment pass stopped, and the pass resumes.
 *
 * <p>The cursor is progress and budget accounting only. Correctness of a repeat run does <b>not</b>
 * depend on it: enrichment upgrades a {@code LEGACY_UNENRICHED} payload and nothing else, so running
 * the pass twice, or being interrupted half way and restarted, can only ever produce the same result.
 * That matters because {@code SavedData#setDirty} is not a durable commit — a pass whose progress was
 * never written to disk is simply performed again, and must be harmless.
 *
 * <h2>Honest coverage</h2>
 *
 * <p>{@link Coverage#COMPLETE} is never reported while work remains. §19.3 requires a partially
 * enriched history to read as {@code PARTIAL_LEGACY} so that absence-based gates respect it: "no
 * evidence of violence" from a half-migrated save is not a clean record (I08).
 */
public final class ProfileMigrationState {

    /** How much of this save's profile history can be trusted as complete (§19.3). */
    public enum Coverage {

        /** No enrichment was ever needed: every payload was created live. */
        COMPLETE,

        /** A budgeted pass is still owed. Profile answers are provisional. */
        MIGRATING,

        /**
         * The pass has finished, and some records could not be spoken for: custom or ambiguous
         * types, or a payload that had to be quarantined. Positive evidence is still displayable;
         * absence proves nothing.
         */
        PARTIAL_LEGACY;

        public String jsonName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final String TAG_MANIFEST = "manifest";
    private static final String TAG_PENDING = "pending";
    private static final String TAG_CURSOR = "cursor";
    private static final String TAG_STUBBED = "stubbed";
    private static final String TAG_ENRICHED = "enriched";
    private static final String TAG_UNENRICHABLE = "unenrichable";
    private static final String TAG_PASSES = "passes";

    private int manifestVersion;
    private boolean pending;
    @Nullable
    private UUID cursor;
    private long stubbed;
    private long enriched;
    private long unenrichable;
    private long passes;

    private ProfileMigrationState() {
    }

    /** The state for a save that never needed migrating: nothing owed, nothing legacy. */
    public static ProfileMigrationState complete() {
        ProfileMigrationState state = new ProfileMigrationState();
        state.manifestVersion = LegacyEnrichmentManifest.VERSION;
        return state;
    }

    /** The state the v2 to v3 upgrade opens: a pass is owed, starting from the first player. */
    public static ProfileMigrationState opened(long stubbed) {
        ProfileMigrationState state = new ProfileMigrationState();
        state.manifestVersion = LegacyEnrichmentManifest.VERSION;
        state.pending = true;
        state.stubbed = Math.max(0L, stubbed);
        return state;
    }

    public int manifestVersion() {
        return manifestVersion;
    }

    /** Whether a budgeted pass is still owed. */
    public boolean pending() {
        return pending;
    }

    /** The player the next pass resumes after, or empty to start from the beginning. */
    public Optional<UUID> cursor() {
        return Optional.ofNullable(cursor);
    }

    public long stubbed() {
        return stubbed;
    }

    /** How many stubs have been upgraded. Best-effort; see {@link #advanced}. */
    public long enriched() {
        return enriched;
    }

    /** How many records the manifest cannot speak for. Best-effort; see {@link #advanced}. */
    public long unenrichable() {
        return unenrichable;
    }

    public long passes() {
        return passes;
    }

    /**
     * Records progress after one budgeted pass, leaving the cursor where it stopped.
     *
     * <p>{@link #enriched()} and {@link #unenrichable()} are <b>best-effort counters</b>, and this is
     * the method that makes them so. {@code setDirty} is not a durable commit, so a pass whose
     * progress never reached disk runs again after a restart and counts the same records a second
     * time. That is deliberate: the alternative is either a per-record "counted" marker on every
     * incident, which is real save weight for a diagnostic, or making a repeated pass skip records —
     * and a pass that skips is a pass that can leave a stub unenriched forever, which is the
     * correctness property this design gives up nothing else to keep.
     *
     * <p>Both over-counts are safe in the direction that matters. Nothing decides an <em>answer</em>
     * from these numbers: {@link #coverage} reads {@code stubbed} and {@code unenrichable} only as
     * "was there ever legacy history", which over-counting can only keep {@code PARTIAL_LEGACY} —
     * never turn into a false {@code COMPLETE} (I08). They are an operator's progress report, and
     * {@code /mcareputation debug profilemigration} is where they are read.
     */
    public void advanced(@Nullable UUID nextCursor, long newlyStubbed, long newlyEnriched,
                         long newlyUnenrichable) {
        this.cursor = nextCursor;
        this.stubbed += Math.max(0L, newlyStubbed);
        this.enriched += Math.max(0L, newlyEnriched);
        this.unenrichable += Math.max(0L, newlyUnenrichable);
        this.passes++;
        this.pending = nextCursor != null;
    }

    /**
     * Whether this save's profile history is complete, and if not, why.
     *
     * @param quarantinedPayloads payloads the loader could not read; each one makes coverage partial
     *                            regardless of how far enrichment got (§19.4)
     */
    public Coverage coverage(int quarantinedPayloads) {
        if (pending) {
            return Coverage.MIGRATING;
        }
        // Any legacy history keeps this save partial for good, even after a finished pass. §19.2's
        // enrichment is deliberately conservative — an evaluative channel is never reconstructed and
        // an ambiguous type is never spoken for — so "the pass finished" is not "the history is
        // complete", and an absence gate must keep respecting the difference (I08).
        boolean legacy = stubbed > 0L || unenrichable > 0L || quarantinedPayloads > 0;
        return legacy ? Coverage.PARTIAL_LEGACY : Coverage.COMPLETE;
    }

    /** A one-line operator summary for {@code /mcareputation debug profilemigration}. */
    public String report(int quarantinedPayloads) {
        return "manifest v" + manifestVersion + ", coverage " + coverage(quarantinedPayloads).jsonName()
                + ", " + stubbed + " stubbed, " + enriched + " enriched, " + unenrichable
                + " unenrichable, " + passes + " pass(es)"
                + cursor().map(id -> ", resuming after " + id).orElse("")
                + (quarantinedPayloads > 0 ? ", " + quarantinedPayloads + " quarantined payload(s)" : "");
    }

    /**
     * Empty when there is nothing to say, so a save that never migrated writes no tag.
     *
     * <p>Note on the three counters that are written here: {@code stubbed} is exact (the structural
     * migration creates each stub once), while {@code enriched} and {@code unenrichable} are
     * <b>best-effort</b> and may over-count a record examined by a pass that was performed twice
     * because its progress was never durably written. See {@link #advanced} for why that is the
     * chosen trade and why no answer depends on it.
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (!pending && stubbed == 0L && enriched == 0L && unenrichable == 0L && passes == 0L) {
            return tag;
        }
        tag.putInt(TAG_MANIFEST, manifestVersion);
        if (pending) {
            tag.putBoolean(TAG_PENDING, true);
        }
        if (cursor != null) {
            tag.putUUID(TAG_CURSOR, cursor);
        }
        if (stubbed != 0L) {
            tag.putLong(TAG_STUBBED, stubbed);
        }
        if (enriched != 0L) {
            tag.putLong(TAG_ENRICHED, enriched);
        }
        if (unenrichable != 0L) {
            tag.putLong(TAG_UNENRICHABLE, unenrichable);
        }
        if (passes != 0L) {
            tag.putLong(TAG_PASSES, passes);
        }
        return tag;
    }

    /**
     * Reads the cursor back. A manifest version from a different build leaves the pass owed rather
     * than claiming the old one finished: the mapping decides what history says, so a changed mapping
     * is a changed answer and has to be re-examined.
     */
    public static ProfileMigrationState load(CompoundTag tag) {
        ProfileMigrationState state = new ProfileMigrationState();
        if (tag == null || tag.isEmpty()) {
            return complete();
        }
        state.manifestVersion = tag.getInt(TAG_MANIFEST);
        state.pending = tag.getBoolean(TAG_PENDING);
        state.cursor = tag.hasUUID(TAG_CURSOR) ? tag.getUUID(TAG_CURSOR) : null;
        state.stubbed = Math.max(0L, tag.getLong(TAG_STUBBED));
        state.enriched = Math.max(0L, tag.getLong(TAG_ENRICHED));
        state.unenrichable = Math.max(0L, tag.getLong(TAG_UNENRICHABLE));
        state.passes = Math.max(0L, tag.getLong(TAG_PASSES));
        if (state.manifestVersion != LegacyEnrichmentManifest.VERSION) {
            state.manifestVersion = LegacyEnrichmentManifest.VERSION;
            state.pending = true;
            state.cursor = null;
        }
        return state;
    }
}

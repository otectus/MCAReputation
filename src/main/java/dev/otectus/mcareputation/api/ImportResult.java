package dev.otectus.mcareputation.api;

import java.util.List;

/**
 * The outcome of a {@link LegacyImportRequest} (spec §25, §32.2).
 *
 * <p>{@link Reason#ALREADY_MIGRATED} is the expected answer for every login after the first and is
 * not a failure — it is the guarantee that legacy standing is never added twice, working correctly.
 */
public record ImportResult(
        boolean applied,
        Reason reason,
        int communitiesImported,
        int baselineTotal,
        int titlesImported,
        int highWaterImported,
        List<String> notes) {

    public enum Reason {
        APPLIED,
        /** The marker for this source already exists on this player's record. */
        ALREADY_MIGRATED,
        /** The request carried nothing to import. */
        NOTHING_TO_IMPORT,
        /** Migration is switched off in config. */
        DISABLED,
        /** Reported what would happen; nothing was written. */
        DRY_RUN,
        /** An unexpected failure was contained; nothing was written. */
        ERROR
    }

    public ImportResult {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static ImportResult applied(int communities, int baselineTotal, int titles, int highWater,
                                       List<String> notes) {
        return new ImportResult(true, Reason.APPLIED, communities, baselineTotal, titles, highWater, notes);
    }

    public static ImportResult dryRun(int communities, int baselineTotal, int titles, int highWater,
                                      List<String> notes) {
        return new ImportResult(false, Reason.DRY_RUN, communities, baselineTotal, titles, highWater, notes);
    }

    public static ImportResult notApplied(Reason reason) {
        return new ImportResult(false, reason, 0, 0, 0, 0, List.of());
    }

    /** One line summarising the import for the migration log and {@code /mcareputation migrate status}. */
    public String summary() {
        return switch (reason) {
            case APPLIED -> "imported " + communitiesImported + " communit(ies), baseline total "
                    + baselineTotal + ", " + titlesImported + " title(s), " + highWaterImported
                    + " tier high-water mark(s)";
            case DRY_RUN -> "would import " + communitiesImported + " communit(ies), baseline total "
                    + baselineTotal + ", " + titlesImported + " title(s), " + highWaterImported
                    + " tier high-water mark(s)";
            case ALREADY_MIGRATED -> "already migrated; nothing to do";
            case NOTHING_TO_IMPORT -> "no legacy standing found";
            case DISABLED -> "legacy migration is disabled in config";
            case ERROR -> "failed; see the server log";
        };
    }
}

package dev.otectus.mcareputation.api;

/**
 * The low-level MCA deeds that MCA: Reputation detects itself, and that another mod may take
 * ownership of through {@link CoreIncidentAuthority}.
 *
 * <p>These are deliberately <em>not</em> incident ids. An incident id names a story
 * ({@code mcareputation:villager_assaulted}); a kind names the raw gameplay event a detector races
 * for. Two mods can both want to record "a villager was assaulted"; only one may be the one that
 * notices the {@code LivingHurtEvent} and turns it into a deed, or the player pays twice for one
 * swing.
 *
 * <p>This enum only ever grows at the end, and only for a deed Reputation actually detects natively.
 * Adding a value is not a breaking API change; reordering or removing one is.
 */
public enum CoreIncidentKind {

    /** A player damaged a living MCA villager without killing them. */
    MCA_VILLAGER_ASSAULT,

    /** A player killed an MCA villager. */
    MCA_VILLAGER_KILL
}

package dev.otectus.mcareputation.api;

/**
 * Why a player's standing moved (§6 "Standing change").
 *
 * <p>Every canonical change carries one, so a listener can tell an earned deed from background
 * ageing without guessing from the incident id. {@link #isQuiet()} names the causes that must not
 * replay deed feedback: they still update the displayed tier and every condition that reads standing,
 * but they never toast, never write an action bar line, and never claim a first-time milestone.
 *
 * @since MCA: Reputation 0.4.1
 */
public enum ChangeCause {

    /** A recorded deed. */
    DEED,

    /** An incident moved to a stronger status. */
    RESOLUTION,

    /** A precursor incident was folded into a successor. */
    SUPERSEDE,

    /** Contribution ageing caught up with the clock. */
    DECAY,

    /** An administrator set or adjusted the baseline. */
    ADMIN,

    /** A one-time import of pre-Reputation standing. */
    IMPORT,

    /** A config or datapack reload recomputed standing. */
    RELOAD,

    /** A saved-data format migration. */
    MIGRATION;

    /** Whether changes from this cause suppress player-facing deed feedback. */
    public boolean isQuiet() {
        return this == DECAY || this == RELOAD || this == MIGRATION;
    }
}

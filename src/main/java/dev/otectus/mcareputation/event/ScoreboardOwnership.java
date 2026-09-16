package dev.otectus.mcareputation.event;

/**
 * Whether the configured scoreboard objective is ours to write, adopt, refuse or remove (DD13).
 *
 * <p>A pure decision table, and a class of its own for the same reason {@code SelectorMath} is one:
 * every branch is checkable without a server, a scoreboard, or a config.
 *
 * <h2>What ownership is proved by</h2>
 *
 * <p>Two things together: the criteria is {@code dummy}, and the display name is our own translatable
 * marker. Either alone is not enough — a server operator's manual {@code /scoreboard objectives add
 * standing dummy "Whatever"} is a dummy objective this mod did not create, and repurposing it would
 * overwrite a display an operator set up on purpose. A foreign objective is therefore never written to
 * and, crucially, never removed either: refusing is a smaller failure than deleting someone's data.
 */
public final class ScoreboardOwnership {

    /** What to do about the objective with the configured name. */
    public enum Decision {
        /** No such objective yet, and the feature is on: create ours and write to it. */
        CREATE,
        /** The objective is ours by both marks: write to it. */
        ADOPT,
        /** Someone else's objective wears this name: leave it entirely alone, and log once. */
        REFUSE,
        /** Ours, and the feature has been turned off: remove it and our rows with it. */
        REMOVE,
        /** Nothing to do — no objective of ours exists and none is wanted. */
        NOTHING
    }

    private ScoreboardOwnership() {
    }

    /**
     * @param exists             whether an objective with the configured name is already present
     * @param criteriaIsDummy    whether that objective's criteria is {@code dummy}
     * @param displayNameMatches whether its display name is our own translatable marker
     * @param featureEnabled     whether the scoreboard display is switched on right now
     */
    public static Decision decide(boolean exists, boolean criteriaIsDummy, boolean displayNameMatches,
                                  boolean featureEnabled) {
        if (!exists) {
            return featureEnabled ? Decision.CREATE : Decision.NOTHING;
        }
        boolean ours = criteriaIsDummy && displayNameMatches;
        if (!ours) {
            return Decision.REFUSE;
        }
        return featureEnabled ? Decision.ADOPT : Decision.REMOVE;
    }

    /** True when this decision permits writing a score. */
    public static boolean mayWrite(Decision decision) {
        return decision == Decision.CREATE || decision == Decision.ADOPT;
    }
}

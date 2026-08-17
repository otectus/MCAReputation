package dev.otectus.mcareputation.incident;

import com.mojang.serialization.Codec;
import dev.otectus.mcareputation.util.EnumCodecs;

import java.util.Optional;

/**
 * The lifecycle state of an incident record (spec §14, §15.2).
 *
 * <p>Resolution never deletes history: a resolved incident keeps its record, its subjects, and its
 * witnesses, and only its <em>current contribution</em> to the score moves. That is what lets a
 * villager still say "you did hurt Anna, but you made it right" instead of forgetting entirely.
 *
 * <p>Progression is <b>monotonic by strength</b> ({@link #strength()}). Re-applying the same or a
 * weaker status is a no-op, so a repeatable quest cannot ratchet an apology into a free pardon; a
 * genuinely stronger resolution may reduce the contribution further. {@link #DISPROVEN} is terminal —
 * a deed shown never to have happened cannot be downgraded back into a real one.
 */
public enum IncidentStatus {

    /** Standing, contributing its current value. The state every incident is created in. */
    ACTIVE(0),

    /** The player publicly acknowledged it. Softens the penalty; does not erase the deed. */
    APOLOGIZED(1),

    /** The player did restitution work. A large reduction, still short of forgiveness. */
    ATONED(2),

    /** The community let it go. Contribution normally drops to zero, record retained. */
    FORGIVEN(3),

    /** It never happened, or was wrongly attributed. Terminal and irreversible. */
    DISPROVEN(4),

    /** Decay ran the contribution to zero and retention elapsed; kept only as chronology. */
    EXPIRED(1);

    private final int strength;

    IncidentStatus(int strength) {
        this.strength = strength;
    }

    public static final Codec<IncidentStatus> CODEC = EnumCodecs.codec(IncidentStatus.class);

    public static Optional<IncidentStatus> byName(String raw) {
        return EnumCodecs.byName(IncidentStatus.class, raw);
    }

    /** Lenient saved-data read (§13.6): an unknown status degrades to {@link #ACTIVE}. */
    public static IncidentStatus byNameOr(String raw, IncidentStatus fallback) {
        return byName(raw).orElse(fallback);
    }

    public String jsonName() {
        return EnumCodecs.lower(this);
    }

    /**
     * How final this status is. Only a strictly greater strength may replace the current one, which is
     * what makes {@code resolve} idempotent under duplicate quest turn-ins and packet replay.
     *
     * <p>{@link #EXPIRED} deliberately shares {@link #APOLOGIZED}'s strength: expiry is a bookkeeping
     * outcome, and a real apology or atonement arriving afterwards should still be recordable.
     */
    public int strength() {
        return strength;
    }

    /** True when {@code next} is a legal transition from this status. */
    public boolean canTransitionTo(IncidentStatus next) {
        if (this == DISPROVEN) {
            return false; // terminal
        }
        if (this == EXPIRED) {
            // Expiry is bookkeeping, not a resolution. Any genuine resolution may still land —
            // including APOLOGIZED, whose shared strength the plain comparison below would refuse.
            return next != ACTIVE && next != EXPIRED;
        }
        return next.strength() > this.strength();
    }

    /** True for any status reached by an authored/administrative resolution rather than by decay. */
    public boolean isResolved() {
        return this != ACTIVE && this != EXPIRED;
    }
}

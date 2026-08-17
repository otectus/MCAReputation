package dev.otectus.mcareputation.incident;

import com.mojang.serialization.Codec;
import dev.otectus.mcareputation.util.EnumCodecs;

import java.util.Optional;

/**
 * How weighty an incident is for presentation, notification, and pruning order (spec §13.5, §14).
 * Severity deliberately does <b>not</b> scale the score — the delta already carries that. It exists so
 * that when a ledger is full, a trivial errand is discarded before a killing, and so the UI and
 * Conversations can pick an appropriate voice.
 */
public enum IncidentSeverity {

    TRIVIAL,
    MINOR,
    MODERATE,
    MAJOR,
    SEVERE;

    public static final Codec<IncidentSeverity> CODEC = EnumCodecs.codec(IncidentSeverity.class);

    public static Optional<IncidentSeverity> byName(String raw) {
        return EnumCodecs.byName(IncidentSeverity.class, raw);
    }

    public String jsonName() {
        return EnumCodecs.lower(this);
    }

    /** Ascending weight, {@code 0..4}. Used only for ordering, never as a multiplier. */
    public int weight() {
        return ordinal();
    }

    /** {@code MAJOR} and above are worth a distinct UI treatment and are pruned last. */
    public boolean isNotable() {
        return weight() >= MAJOR.weight();
    }
}
